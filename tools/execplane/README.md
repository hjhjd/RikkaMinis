# ExecPlane WebSocket 执行端

`tools/execplane/` 提供 Minis WS 沙箱的参考执行端，支持两种连接方向：

| 入口 | 连接方向 | 适用场景 |
|---|---|---|
| `ws-server.py` | Minis App → 容器 | App 能访问容器端口；当前推荐并完成完整实测的模式 |
| `ws-agent.py` | 容器 → Minis App | 容器在 NAT 后、不方便暴露端口，或需要主动注册到 App |

两个入口复用相同的数据面实现：

- `runtime.py`：不透明 dispatch、旧命令执行、状态、远程文件 RPC、单次环境变量注入；
- `dispatch-plugin-example.py`：参考 DSL 插件，当前实现 `help/status/exec`；
- `transfer_runtime.py`：文件和目录 Push/Pull、分块校验、冲突处理和断点状态。

仓库保留两个入口，但单个容器通常只运行其中一个。不要让正向和反向入口以不同名称同时代表同一个容器，否则 App 会看到两个实际指向同一文件系统的沙箱。

## 功能

当前执行端可声明并实现以下 capabilities（`dispatch` 仅在配置插件时声明）：

```text
dispatch
exec
status
fs.stat
fs.list
fs.read
fs.write
fs.mkdir
fs.remove
fs.move
transfer.push
transfer.pull
env.inject
```

对应用户功能：

- `sandbox_dispatch` 将不透明 payload 发送给服务端 DSL；参考插件支持 `help/status/exec`；
- 旧兼容路径在指定 WS 沙箱执行 Shell 命令；
- `file_read`、`file_write`、`file_edit` 显式操作 WS 文件；
- `sandbox_file_push` 将 App/PRoot 文件或目录推入 WS；
- `sandbox_file_pull` 将 WS 文件或目录拉回 App/PRoot；
- `read_image(sandbox=...)` 直接读取远程图片；
- 将 App 授权的环境变量仅注入单次子进程。

WS 与手机 App/PRoot **不共享文件系统**。即使两边都有 `/var/minis`，同名路径也不是同一文件。跨边界移动文件必须显式 Push/Pull。

## 最小适配条件

新容器只需要完成以下四项，不要求 systemd：

1. 安装 Python 和 `websockets`；
2. 部署对应入口及两个公共运行时文件，并配置 `--allow-root`；
3. 配置高强度 `EXECPLANE_TOKEN`；
4. 在 Minis App 中添加连接，或让反向 Agent 注册到 App。

systemd、OpenRC、supervisord、s6、runit、Docker restart policy 等都只是可选的进程守护方式，不属于 ExecPlane 协议要求。

## 1. 安装依赖

建议使用独立虚拟环境：

```sh
mkdir -p "$HOME/.local/lib/execplane"
python3 -m venv "$HOME/.local/lib/execplane/venv"
"$HOME/.local/lib/execplane/venv/bin/pip" install 'websockets>=15,<16'
```

验证：

```sh
"$HOME/.local/lib/execplane/venv/bin/python" -c \
  'import websockets; print(websockets.__version__)'
```

推荐 Python 3.10 或更高版本。

## 2. 部署文件

三个文件必须来自同一个 Git 提交，避免协议和运行时不匹配。当前协议版本为 **ExecPlane 0.2**；0.1 与 0.2 不做静默兼容，版本或 capability 握手失败的执行端不会进入在线注册表。

### 正向模式最小文件集

```text
ws-server.py
runtime.py
transfer_runtime.py
```

### 反向模式最小文件集

```text
ws-agent.py
runtime.py
transfer_runtime.py
```

推荐安装位置：

```text
~/.local/lib/execplane/
```

示例：

```sh
install -m 0755 tools/execplane/ws-server.py "$HOME/.local/lib/execplane/ws-server.py"
install -m 0755 tools/execplane/ws-agent.py  "$HOME/.local/lib/execplane/ws-agent.py"
install -m 0644 tools/execplane/runtime.py "$HOME/.local/lib/execplane/runtime.py"
install -m 0644 tools/execplane/transfer_runtime.py "$HOME/.local/lib/execplane/transfer_runtime.py"
```

## 3. Token

生成独立随机 Token：

```sh
python3 -c 'import secrets; print(secrets.token_urlsafe(48))'
```

将结果设置为：

```sh
export EXECPLANE_TOKEN='生成的随机令牌'
```

Token 相当于该容器的远程执行凭据。不要使用容器登录密码、sudo 密码或多个容器共用的 Token，也不要提交到 Git。

Token 的持久化方式由容器平台决定，例如：

- Docker/Podman 环境变量或 secret；
- Compose `environment`/`secrets`；
- OpenRC、supervisord、s6、runit；
- systemd `EnvironmentFile`；
- 容器平台自己的环境变量设置。

## 4. 允许根目录

两个入口都要求至少一个 `--allow-root`。文件 RPC 和 Push/Pull 只能访问这些根目录及其子目录。

准备工作目录：

```sh
mkdir -p "$HOME/workspace"
```

推荐：

```text
--allow-root "$HOME/workspace"
```

可重复指定：

```text
--allow-root "$HOME/workspace" --allow-root /data/projects
```

要求：

- 根目录在启动前已经存在；
- 运行服务的用户具有相应读写权限；
- 不要使用 `--allow-root /`，否则失去文件路径隔离；
- 指向允许根之外的符号链接不能绕过隔离；
- 目录传输拒绝符号链接，防止归档逃逸。

## 5. 正向模式：App 连接容器

启动：

```sh
EXECPLANE_TOKEN='随机令牌' \
"$HOME/.local/lib/execplane/venv/bin/python" \
"$HOME/.local/lib/execplane/ws-server.py" \
  --host 0.0.0.0 \
  --port 8767 \
  --allow-root "$HOME/workspace"
```

如果 App 与执行端共享网络命名空间，优先监听：

```text
127.0.0.1
```

只有 App 需要从其他容器或主机访问时才监听 `0.0.0.0`，并配置端口映射和防火墙。

在 Minis 的沙箱设置中添加：

```text
名称：自定义唯一名称
URL：ws://容器地址:8767
Token：与 EXECPLANE_TOKEN 完全相同
```

非可信网络不要直接暴露明文 `ws://`。服务端本身不终止 TLS，应通过 Caddy/Nginx/平台网关提供 `wss://`。

## 6. 反向模式：容器连接 App

先在 Minis 中启用反向 WS 监听并复制 App Token，然后启动：

```sh
EXECPLANE_TOKEN='App 中复制的令牌' \
"$HOME/.local/lib/execplane/venv/bin/python" \
"$HOME/.local/lib/execplane/ws-agent.py" \
  --uri ws://APP地址:8765 \
  --name my-container \
  --allow-root "$HOME/workspace"
```

`--name` 必须在 App 中唯一，允许字符为字母、数字、点、下划线和短横线。

反向 Agent 会：

- 主动连接 App；
- 注册名称、能力、OS、架构和 CPU 核心数；
- 断线后指数退避重连，最大约 30 秒；
- 复用与正向服务端相同的命令、文件和传输运行时。

注意：反向模式要求容器能访问 App 地址。App 仅监听 loopback 时，其他容器或主机无法直接连接。

## 7. 进程守护（可选）

ExecPlane 不依赖 systemd。选择容器已有的守护机制即可，关键要求只有：

- 服务崩溃后能重启；
- Token 不出现在公开日志；
- 工作目录和允许根在启动前存在；
- 使用固定的三个同版本文件；
- 正向服务的端口可被 App 访问，或反向 Agent 可访问 App。

### 后台测试启动

仅用于临时测试：

```sh
EXECPLANE_TOKEN='随机令牌' \
nohup "$HOME/.local/lib/execplane/venv/bin/python" \
  "$HOME/.local/lib/execplane/ws-server.py" \
  --host 0.0.0.0 --port 8767 \
  --allow-root "$HOME/workspace" \
  > "$HOME/execplane.log" 2>&1 &
```

生产容器应使用其原生 init/守护机制，而不是依赖 `nohup`。

## 网络代理（可选）

代理主要影响 WS 中执行的 Git、curl、pip 等子进程。将代理变量设置到 WS 服务进程环境：

```sh
export HTTP_PROXY=http://代理地址:端口
export HTTPS_PROXY=http://代理地址:端口
export http_proxy="$HTTP_PROXY"
export https_proxy="$HTTPS_PROXY"
export NO_PROXY=localhost,127.0.0.1,::1
export no_proxy="$NO_PROXY"
```

务必保留 `NO_PROXY`，避免本机 WS 连接误入代理。

## sudo（可选，高风险）

普通 WS 功能不需要 sudo。如果运行用户拥有免密 sudo，远程 Shell 也可以执行系统管理命令。

启用后，WS Token 实际上具有 root 等级风险。应使用独立高强度 Token、可信网络，并优先只授权必要命令，而不是 `NOPASSWD: ALL`。

某些守护器启用了 `NoNewPrivileges` 或类似安全机制时，即使 sudoers 正确，子进程也无法提权；是否关闭由容器管理员决定。

## 传输限制

| 项目 | 限制 |
|---|---:|
| 单次 `fs.read/fs.write` | 1 MiB |
| 单个文件 Push/Pull | 256 MiB |
| 目录 Push/Pull | 512 MiB |
| 分块大小 | 256 KiB |
| WS 入站消息上限 | 2 MiB |
| stdout 上限 | 16 MiB |
| stderr 上限 | 8 MiB |
| 单命令总输出上限 | 20 MiB，超限终止整个进程组 |
| 单命令最长超时 | 1 小时，超时终止整个进程组 |
| App 每沙箱并发 | 默认 4，可在“沙箱配置”中为每个正向/反向执行端独立设置 1–256 |
| 执行端全局硬并发 | 默认 256，`--max-exec` 可调；实际并发取 App 与执行端限制的较小者 |
| 正向 WS 连接数 | 默认 8，`--max-connections` 可调 |
| 活动传输数 | 默认 8，`--max-transfers` 可调 |
| 传输临时空间 | 默认 1 GiB，`--temp-limit-mb` 可调 |
| 未完成传输状态清理时间 | 30 分钟，后台每 5 分钟扫描 |

目录传输保留嵌套目录、空目录、Unicode 文件名和普通文件。目录内出现符号链接时会拒绝传输。

覆盖策略：

```text
fail
replace_file
replace_directory
merge_directory
```

当前 `merge_directory` 在客户端规范化为完整目录替换，以避免部分合并产生旧文件残留。

## 新容器验收

连接后建议完成以下检查：

1. App 显示 `exec`、`fs.read`、`fs.write`、`transfer.push`、`transfer.pull`、`env.inject`；
2. `shell_execute(sandbox=...)` 返回正确容器的 `uname -a` 和 `pwd`；
3. `file_write/read/edit(sandbox=...)` 与 WS Shell 看到同一个文件；
4. Push 一个超过 256 KiB 的文件，远端和本地 SHA-256 一致；
5. Push/Pull 一个含中文文件名、嵌套目录和空目录的目录树；
6. 测试 `overwrite=fail`、`replace_file`、`replace_directory`；
7. `read_image(sandbox=...)` 能读取远程 PNG/JPEG；
8. 访问允许根之外的路径返回 `FS_PERMISSION_DENIED`；
9. 冲突或失败后没有遗留 `.part` 或 `.minis-*.dir`。

检查临时残留：

```sh
find "$HOME/workspace" /tmp \( -name '*.part' -o -name '*.minis-*.dir' \) -print
```

## 安全边界

- `exec` 可以运行任意 Shell 命令，不受 `--allow-root` 限制；`--allow-root` 只限制文件 RPC 和 Push/Pull。
- 命令输出、并发、连接数、活动传输和临时空间均有硬上限；超时、取消或输出超限会终止命令的整个进程组。
- 同一 WebSocket 连接可并发处理多个请求，响应允许乱序返回并通过 request ID 关联；连接断开会取消该连接尚未完成的任务。
- 命令统一使用 argv；Shell 工具由 App 显式发送 `["/bin/sh", "-lc", command]`，执行端不再隐式调用 `create_subprocess_shell`。
- stdout/stderr 通过 `exec.output` 事件实时回传，每块带 request ID、sequence 和 stream；最终响应携带退出码、耗时、输出字节数和截断状态。
- App 协程取消会发送 `cancel(requestId)`，执行端取消对应任务并终止其完整进程组。
- App 与执行端必须同步升级错误码。旧 App 遇到新版执行端返回的 `EXEC_OUTPUT_LIMIT`/`EXEC_RESOURCE_LIMIT` 时可能无法解码响应并表现为请求超时。
- App 显式指定 WS 沙箱失败时不会静默改在 PRoot 执行。
- Minis 环境变量默认不会自动发送到 WS；只有 App 中明确授权的变量才会随单次 `exec` 注入。
- 注入变量不会写入远端 profile 或配置文件，子进程结束后失效。
- 文件 Pull 完成并通过 SHA-256 校验后，才会落入 App 文件空间并成为可预览的 `minis://` 资源。
- 文件和目录覆盖采用同级 `stage → backup → target` 事务切换；解压、移动或远端确认失败时恢复原目标，并清理 `.stage/.backup/.part`。
- `transfer.commit` 可幂等重试；响应丢失后客户端通过 `transfer.resume` 查询 `COMMITTED` 状态。
- 文件 RPC 与目录传输拒绝符号链接；`fs.list` 仅通过 `lstat` 报告 `symlink` 类型，不跟随读取链接目标。

## 文件说明

```text
ws-server.py          正向入口：App 主动连接容器
ws-agent.py           反向入口：容器主动连接 App
dispatch-plugin-example.py  参考不透明 DSL：help/status/exec
instructions-example.md      可复制到 AI 提示词的 VCPMinis 指令集
runtime.py            公共 dispatch、旧命令、文件 RPC 和环境注入运行时
transfer_runtime.py   公共文件/目录传输状态机
test_runtime.py       Python 运行时单元和安全回归测试
test_dispatch_plugin.py DSL 解析、流式输出和进程组取消测试
```


## 不透明 Dispatch（新架构）

Android 新路径只发送 `dispatch` RPC：

```json
{"id":12,"method":"dispatch","params":{"payload":"任意服务端 DSL","timeoutMs":600000}}
```

Android 不解析、不 trim、不补前缀，也不知道 payload 中是否存在 `exec`、`push`、`pull`。业务语法完全由执行端插件负责。启用方式：

```sh
python3 ws-server.py \
  --allow-root "$HOME/workspace" \
  --instructions ./instructions-example.md \
  --dispatch-plugin ./dispatch-plugin-example.py
```

反向入口使用相同的 `--instructions` 与 `--dispatch-plugin` 参数。插件必须导出：

```python
async def dispatch(payload: str, emit):
    await emit("流式文本")
    return {"output": "最终文本", "truncated": False}
```

`--instructions` 文件在握手中作为 `instructionSet` 返回。Android 只展示并在用户主动点击时原样复制，不会自动注入任何 AI 提示词。未配置插件时执行端不会声明 `dispatch` capability。

当前限制：payload 256 KiB、单个流式事件 1 MiB、最终文本 1 MiB。旧 `exec`、`fs.*`、`transfer.*` 暂留作兼容路径，新业务不应继续依赖 Android 为具体 method 编写分支。
