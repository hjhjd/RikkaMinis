# Android WebSocket 沙箱与 ExecPlane 0.2

## 1. 定位

VCPMinis 的 WebSocket 沙箱是一个远程执行平面，不是 Android 内核级沙箱：

```text
Agent 工具 / 终端
        ↓
ExecutionCoordinator
        ↓
SandboxCommandRouter
        ↓
ExecPlaneBridge
        ↓ WebSocket JSON-RPC 0.2
Linux / 容器中的 Python 执行端
```

执行端以其 Linux 运行用户权限执行命令。`--allow-root` 只限制文件 RPC 和 Push/Pull，不限制 Shell；因此 WS Token 等价于该运行用户的远程 Shell 凭据。生产使用应配合独立低权限用户、容器权限、挂载和网络策略。

执行端部署和参数详见 [`tools/execplane/README.md`](../tools/execplane/README.md)，改进进度和剩余发布条件见 [`EXECPLANE_IMPROVEMENT_TODO.md`](./EXECPLANE_IMPROVEMENT_TODO.md)。

## 2. 连接方向

### 2.1 正向

```text
Android App → ws-server.py
```

适合 App 能访问执行端端口的环境。默认情况下远程地址必须使用 `wss://`，明文 `ws://` 仅允许 loopback。设置页可显式开启“允许局域网明文 WS”，开启后额外接受 RFC1918、IPv4 链路本地和 IPv6 ULA/链路本地的**字面 IP**；公网地址和主机名仍必须使用 `wss://`。

该开关只影响 App 主动连接执行端，不会把 Android 反向监听从 `127.0.0.1` 改到 `0.0.0.0`。明文连接会在局域网传输 Token、命令、输出和文件内容，应仅在可信、隔离的网络使用。

### 2.2 反向

```text
ws-agent.py → Android App 127.0.0.1:8765
```

适合执行端主动注册。Android 监听只绑定 loopback；跨主机使用时需要受控隧道、共享网络命名空间或端口转发，不应直接把无 TLS 的远程 Shell 服务监听到 `0.0.0.0`。

反向执行端名称不得与已保存的正向服务器重名。同名注册会被拒绝，避免替换已有连接。

## 3. 配置和路由

设置入口：

```text
设置 → 沙箱配置
```

可配置：

- PRoot 或 WebSocket 默认模式；
- 默认正向 WS 沙箱；
- 反向监听开关和端口；
- 正向服务器 URL 和 Token；
- 每个执行端独立命令并发上限（1–256，默认 4）；
- 环境变量注入策略。

执行端另有 `--max-exec` 硬上限，实际并发取 App 每沙箱上限和执行端硬上限的较小值。

路由规则：

1. `sandbox=proot` 始终使用内置 PRoot；
2. 显式指定 WS 名称时，只执行该目标；目标离线或未知时直接失败，禁止改投 PRoot；
3. 未显式指定且默认模式为 WS 时，只有结构化 `CHANNEL_*` 通道错误允许降级到 PRoot；
4. `EXEC_*`、`FS_*`、`TRANSFER_*` 不触发降级，避免副作用命令重复执行；
5. 禁用的正向配置不会成为默认目标。

## 4. ExecPlane 0.2

0.2 与 0.1 不静默兼容。版本或 capability 握手失败的执行端不会进入在线列表。

### 4.1 握手

执行端报告：

```json
{
  "protocol": "0.2",
  "serverId": "stable-id",
  "name": "executor-name",
  "caps": ["exec", "cancel", "fs.read", "transfer.push"],
  "limits": {
    "maxStdoutBytes": 16777216,
    "maxStderrBytes": 8388608,
    "maxTotalOutputBytes": 20971520,
    "maxTransferBytes": 536870912,
    "maxConcurrentCommands": 256,
    "maxTimeoutMs": 3600000
  }
}
```

Android 校验协议、identity、capability 和 limits 后才标记在线。Shell 目标必须声明 `exec`。

### 4.2 命令模型

Shell 工具显式使用 argv：

```json
{
  "cmd": ["/bin/sh", "-lc", "git status | cat"],
  "shell": true,
  "cwd": null,
  "env": {},
  "envMode": "overlay",
  "timeoutMs": 600000
}
```

Python 端统一调用 `create_subprocess_exec`，不隐式调用 `create_subprocess_shell`。超时、取消和输出超限会终止完整进程组。

### 4.3 流式输出

执行端实时发送：

```json
{
  "event": "exec.output",
  "data": {
    "requestId": 12,
    "sequence": 3,
    "stream": "stdout",
    "data": "building...\n"
  }
}
```

stdout/stderr 分离，事件按 request ID 关联。最终响应包含退出码、耗时、输出字节数和截断状态。响应可乱序返回，Android 通过 request ID 匹配。

### 4.4 取消

Android coroutine 取消或请求超时时发送：

```json
{
  "method": "cancel",
  "params": {"requestId": 12}
}
```

取消绑定连接 identity 和 request ID；一个连接不能取消另一个连接的命令。重复取消返回 `cancelled=false`。

## 5. 跨平台与自定义设备接入

ExecPlane 的 `sandbox_dispatch` 控制面不绑定 Linux、POSIX 路径或特定 Shell。Android 只选择稳定 sandbox ID，并将 `payload` 作为不透明 UTF-8 字符串逐字发送；payload 的语法、路径规则、权限和业务行为全部由目标执行端的 DSL 定义。因此，仅需远程操作时，接入 Windows、macOS、NAS、路由器、IoT 或其他支持 WebSocket 的设备，通常**不需要修改 Android 端代码**。

一个新的设备执行端至少需要实现：

- 正向连接的 `capabilities`，或反向连接的 `register`；
- `dispatch` 和最终 RPC response；
- `cancel`，以及可选的 `dispatch.output` 流式事件；
- 设备自己的 DSL/业务处理器；
- 描述 DSL、路径语义和安全边界的 `instructionSet`。

```text
Android
  └─ ExecPlane WebSocket 控制信封
       └─ dispatch(payload)
            └─ 设备自定义 DSL
```

DSL 不必是 Shell。例如 Windows 节点可定义 PowerShell DSL，NAS 可定义卷和快照操作，路由器可定义网络管理命令，IoT 节点可定义传感器或 GPIO 操作；payload 也可以是由服务端约定的 JSON 文本。Android 不得猜测、补全或改写其中的 `/`、`\`、盘符、命令名或参数。

需要区分以下能力范围：

| 目标能力 | 是否需要修改 Android | 设备端要求 |
|---|---:|---|
| 使用 `sandbox_dispatch` 执行自定义 DSL | 通常不需要 | 实现握手、dispatch、cancel 和指令集 |
| 流式输出、超时和取消 | 不需要 | 按 ExecPlane 事件和 request ID 契约实现 |
| 用户手动复制设备指令集给 Agent | 不需要 | 握手返回 `instructionSet` |
| 使用设备 DSL 自行读写文件 | 不需要 | DSL 自行定义路径和文件行为 |
| 使用通用 `sandbox_file_push/pull` | Android 通常不需要 | 还需实现 `transfer.open/chunk/commit/resume/abort` |
| 自动向模型注入平台、路径风格或 DSL | 需要额外产品设计 | 握手需声明对应元数据并获得用户授权 |
| 使用旧结构化远端 `exec` | 非 POSIX 平台不应依赖 | 当前兼容链固定使用 `/bin/sh -lc` |

当前仓库的 Python 参考执行端和示例 DSL 是 **Linux/POSIX 实现**，其中包含 `/bin/sh -lc`、POSIX 进程组和信号语义；它们不是 ExecPlane 对所有设备的强制要求。Windows 等平台应实现独立的 dispatch 插件和进程树取消机制，而不是简单把 `\` 替换成 `/`。特别是 Windows 文件能力还需在设备端处理盘符、UNC、设备路径、NTFS ADS、junction/reparse point 和允许根校验。

远端通过握手返回的 `instructionSet` 默认只在沙箱设置页展示和复制，不会自动进入系统提示词、Agent 提示词或会话。若不修改 Android，用户需要把对应设备的指令集放入 Agent 提示词或当前对话，模型才能可靠构造该设备的 payload。

## 6. 文件系统和传输

WS 与 App/PRoot 不共享文件系统。同名 `/var/minis` 路径也不是同一文件，跨边界必须使用：

- 小文件：`fs.read/fs.write/fs.mkdir/fs.remove/fs.move`；
- 大文件和目录：`transfer.open/chunk/commit/resume/abort`；
- 工具：`sandbox_file_push`、`sandbox_file_pull`。

传输特性：

- 256 KiB 分块；
- 每块 SHA-256；
- 完整载荷 SHA-256；
- 单文件 256 MiB、目录 512 MiB；
- 默认活动传输 8、临时空间 1 GiB；
- 30 分钟无活动自动清理；
- commit 幂等，响应丢失后可通过 resume 查询。

覆盖采用事务切换：

```text
下载/解压到 stage
→ 原目标移动到 backup
→ stage 替换 target
→ 远端确认 COMMITTED
→ 删除 backup
```

失败时恢复原目标并清理 `.part/.stage/.backup`。目录传输拒绝符号链接；`fs.list` 通过 `lstat` 报告 `symlink`，不跟随读取链接目标。

## 7. 资源保护

默认限制：

| 项目 | 默认值 |
|---|---:|
| stdout | 16 MiB |
| stderr | 8 MiB |
| 总输出 | 20 MiB |
| App 每沙箱并发 | 4 |
| 执行端硬并发 | 256 |
| 正向 WS 连接 | 8 |
| 活动传输 | 8 |
| 传输临时空间 | 1 GiB |
| 命令最长超时 | 1 小时 |

达到输出上限返回 `EXEC_OUTPUT_LIMIT`，资源不足返回 `EXEC_RESOURCE_LIMIT`。超限命令不会破坏 WS 通道，后续请求仍可执行。

## 8. 鉴权与环境变量

- Token 使用 Android 加密偏好存储；
- Token 至少 32 字符；
- Python 使用 `hmac.compare_digest`；
- 鉴权失败关闭码为 1008；
- Token 不应进入日志、仓库或聊天记录；
- Token 泄漏后应立即在 App 中重置并重启 Agent；
- 环境变量按 `NONE/SELECTED/ALL` 策略发送；
- 注入仅对单次子进程生效；
- `EXECPLANE_TOKEN`、`LD_PRELOAD` 等保留变量不会从 App 注入远端。

## 9. 已验证范围

已完成：

- 正向和反向 0.2 握手；
- argv Shell、管道和重定向；
- 同一 WS 连接并发命令；
- stdout/stderr 流式输出；
- 输出超限后通道继续可用；
- timeout/cancel 后无孤儿进程；
- 文件和目录 Push/Pull；
- 中文文件名和空目录；
- 事务覆盖、commit 幂等、符号链接拒绝；
- Android 单测、Debug 编译和 APK 打包；
- Python Runtime 回归测试。

验证命令：

```sh
cd src/android
export JAVA_HOME=/home/nova/tools/jdk-17.0.20+8
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew :app:testDebugUnitTest :app:assembleDebug

cd ../../tools/execplane
python3 -m py_compile runtime.py transfer_runtime.py ws-server.py ws-agent.py
python3 -m unittest -v test_runtime.py
```

## 10. 已知边界和延期项

当前可用于个人可信环境，但以下项目在扩大部署范围前必须完成：

1. 反向执行端默认信任等级从 `LOCAL` 改为 `RESTRICTED`，由用户显式提升；
2. 增加脱敏执行审计；
3. 自动化 Android ↔ Python 端到端和故障注入；
4. 发布门禁覆盖网络切换、响应丢失、磁盘不足和重连竞态。

高对抗同机文件系统场景还应使用 `openat/dir_fd/O_NOFOLLOW` 消除剩余 TOCTOU。真正的安全隔离依赖容器、Linux 用户、挂载、seccomp/AppArmor/SELinux 和网络策略，而不是 Python Runtime 自身。
