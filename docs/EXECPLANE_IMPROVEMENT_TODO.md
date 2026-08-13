# ExecPlane / WS 沙箱改进 TODO

> 创建日期：2026-08-13
>
> 状态：施工中（阶段 1 已完成，2026-08-13）
>
> 原则：先修正确性与数据安全，再扩展协议能力；每批改动都必须有回归测试。

## 1. 总体目标

将当前 WS 远程执行平面从“功能可用”提升到：

1. 故障行为确定：断线不长时间挂起，不错误降级，不重复执行；
2. 资源有硬上限：输出、并发、连接和临时文件不可无限增长；
3. 数据覆盖安全：传输失败不破坏已有文件；
4. 正向与反向连接行为统一：均可被明确选择，使用同一套路由和错误模型；
5. 协议模型、线上消息和测试向量保持一致；
6. 明确 WS 是远程执行平面，`--allow-root` 只约束文件 RPC，不约束 Shell。

## 2. 实施规则

- 显式指定 WS 沙箱失败时，禁止静默改在 PRoot 执行；
- 只有结构化 `CHANNEL_*` 错误允许默认路由降级；
- `EXEC_*`、`FS_*`、`TRANSFER_*` 错误不得触发降级或重试执行；
- 覆盖已有文件或目录时，禁止先删除原目标再开始写入；
- Token、环境变量值和完整敏感命令不得写入日志；
- 协议变更必须同时更新 Kotlin、Python、共享测试向量和文档；
- 不混入当前工作区内与 ExecPlane 无关的已有改动。

---

# 阶段 0：冻结边界并补齐基线

## T0.1 更新文档和过时注释

涉及：

- `docs/EXECPLANE_TODO.md`
- `tools/execplane/README.md`
- `ExecPlaneBridge.kt`
- `MinisApp.kt`

任务：

- [x] 删除“remote exec 尚未开放”等过时描述；
- [x] 明确正向和反向模式当前能力；
- [x] 明确 `--allow-root` 不限制 Shell；
- [x] 明确 WS Token 等价于执行端运行用户的远程执行凭据；
- [x] 明确 Android 反向监听只绑定 `127.0.0.1`；
- [x] 明确当前命令结果不是实时流式返回。

验收：文档与真实攻击面、路由和执行行为一致。

## T0.2 建立测试分组和统一入口

- [ ] 整理协议、连接、路由、Runtime、传输安全和端到端测试分组；
- [ ] 保留并扩展现有 Kotlin 单元测试；
- [ ] 将 Python 测试整理到可 discover 的测试目录；
- [ ] 形成统一验证命令。

基线命令：

```sh
./gradlew :app:testDebugUnitTest
python3 -m unittest discover -s tools/execplane/tests -v
```

---

# 阶段 1：连接故障与路由正确性

> 第一优先级，建议作为 PR 1 实施。

## T1.1 pending request 按连接隔离

- [x] 将反向 pending map 下沉到 `Peer`，或使用 `(connectionId, requestId)` 作为键；
- [x] 响应只能完成同一连接发出的请求；
- [x] send 失败、超时和 coroutine 取消后清理 pending；
- [x] 增加测试可观察的 pending 数量或内部断言。

验收：两个 peer 的请求互不干扰，错误 peer 无法伪造响应完成其他连接请求。

## T1.2 所有断线路径立即终止 pending

覆盖路径：

- [x] `onClosed`；
- [x] `onFailure`；
- [x] 主动 `close`；
- [x] 同名新连接替换旧连接；
- [x] Bridge stop；
- [x] 配置重载。

要求：

- [x] pending 以结构化 `CHANNEL_DISCONNECTED` 失败；
- [x] 命令和传输不继续等待原超时；
- [x] 显式目标断线不降级到 PRoot；
- [x] 默认目标只按规定的通道错误策略降级。

## T1.3 打通反向 executor Shell 路由

- [x] 显式目标优先从 `ConnectionManager` 在线注册表查找；
- [x] 正向配置只负责连接生命周期和默认选择，不作为全部 executor 的唯一来源；
- [x] 正向与反向 executor 均可按名称显式执行；
- [x] 未知目标、离线目标明确失败；
- [x] 同名正向配置和反向连接采用明确的新连接替换旧连接规则；
- [x] 文件 RPC 与 Shell 路由使用一致的 identity 规则。

验收：反向 Agent 注册后可通过 `shell_execute(sandbox=agent-name)` 执行；断线后不转投 PRoot。

## T1.4 默认目标只选择 enabled 配置

- [x] `selectedForwardServer()` 过滤 `enabled=false`；
- [x] 默认目标禁用后选择下一个 enabled 配置，或明确返回空；
- [x] 无可用 WS 配置时 UI 给出明确状态；
- [x] 禁止因默认目标选中 disabled 配置而持续隐式降级。

## T1.5 使用结构化远端异常

建议模型：

```kotlin
class RemoteExecutionException(
    val code: ExecPlaneErrorCode,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
```

- [x] 完整保留 Python 返回的错误码；
- [x] 删除通过 message 字符串前缀判断错误类型的逻辑；
- [x] 只有 `CHANNEL_*` 可触发默认路由降级；
- [x] `EXEC_*`、`FS_*`、`TRANSFER_*` 原样返回。

验收矩阵：

| 错误 | 默认路由是否可降级 |
|---|---:|
| `CHANNEL_DISCONNECTED` | 是 |
| `CHANNEL_TIMEOUT` | 是 |
| `EXEC_TIMEOUT` | 否 |
| `EXEC_FAILED` | 否 |
| `FS_PERMISSION_DENIED` | 否 |
| `TRANSFER_CHECKSUM_MISMATCH` | 否 |

---

# 阶段 2：资源上限和进程清理

> 建议作为 PR 2 实施。

## T2.1 限制命令输出

建议初始限制：

```text
stdout：16 MiB
stderr：8 MiB
总输出：20 MiB
```

- [x] Python Runtime 不再使用无界 `communicate()` 聚合输出；
- [x] 达到总输出上限后终止命令，而不是无限丢弃并继续运行；
- [x] 增加 `EXEC_OUTPUT_LIMIT` 错误码；
- [x] Android 显示稳定、可理解的超限错误；
- [x] 禁止生成超大 WS 文本响应。

## T2.2 正确终止整个进程组

- [x] 子进程启动独立 session/process group；
- [x] 超时或取消时先 `SIGTERM`，宽限后 `SIGKILL`；
- [x] 终止 Shell 产生的子进程和孙进程；
- [x] 增加孤儿进程回归测试。

验收命令：

```sh
sh -c 'sleep 300 & wait'
```

超时后不得遗留 `sleep`。

## T2.3 增加连接和命令并发限制

建议默认值：

```text
最大 WS 连接数：8
全局并发命令：4
单连接并发命令：1
全局并发传输：2
```

- [x] 支持可配置参数；
- [x] 使用 semaphore 或等价机制；
- [x] 资源不足时返回稳定错误；
- [x] 异常、取消和断线后释放配额。

> 单连接请求现已通过独立 Task 并发处理，响应按 request ID 关联、允许乱序返回；实测同一反向连接上的两条 `sleep 2` 均约 2 秒完成。App 可在沙箱配置中为每个正向或反向执行端独立设置 1–256 的命令并发上限，默认 4；执行端保留 `--max-exec` 硬上限，默认 256，实际并发取两端较小值。

## T2.4 增加传输临时空间限制

建议默认值：

```text
全局临时空间：1 GiB
最大活动 transfer：8
单连接活动 transfer：2
```

- [x] 对未完成 push 和目录 pull ZIP 统一计费（staging 将在阶段 3 引入时纳入）；
- [x] 超限前拒绝创建新临时文件；
- [x] abort、commit 和超时清理后释放配额。

## T2.5 周期清理过期 transfer

- [x] Runtime 启动后台 cleanup task；
- [x] 每 5 分钟扫描；
- [x] 30 分钟无活动则 abort；
- [x] 服务关闭时清理自身临时状态；
- [x] 不依赖下一次 `transfer.open()` 才触发清理。

---

# 阶段 3：文件操作事务安全

> 建议作为 PR 3 实施。

## T3.1 Android Pull 使用 staging 落盘

文件流程：

```text
.part 下载 → SHA-256 校验 → 原目标备份 → stage 替换 → 删除备份
```

目录流程：

```text
ZIP 下载 → 同级 stage 解压与校验 → 原目标备份 → stage 替换 → 删除备份
```

- [ ] 任一步失败时恢复原目标；
- [ ] 不留下半成品目录；
- [ ] 处理磁盘不足、解压异常和 rename 失败；
- [ ] 文件和目录共享明确的 rollback 逻辑。

## T3.2 Python Push commit 使用可恢复替换

- [ ] 校验临时载荷后再构建 stage；
- [ ] 目标先改名为 backup，不直接删除；
- [ ] stage 替换失败时恢复 backup；
- [ ] 成功后清理 backup、stage 和 part；
- [ ] 启动时或周期任务安全清理确认过期的自身临时文件。

## T3.3 明确拒绝目录符号链接

- [ ] Android 打包端拒绝文件和目录 symlink；
- [ ] Python 打包与解包端保持相同策略；
- [ ] 即使符号链接仍指向允许根内部也拒绝；
- [ ] 不依赖遍历 API 是否默认跟随链接。

## T3.4 修复文件路径解析

短期：

- [ ] 根目录启动时必须存在并 canonicalize；
- [ ] 最终操作前再次验证 canonical parent；
- [ ] `fs.list` 使用 `lstat`，不跟随 symlink；
- [ ] symlink 显式显示为 `symlink`；
- [ ] 修复 `createParents=true` 无法创建多级新目录。

长期：

- [ ] Linux 使用 `openat`/`dir_fd`；
- [ ] 逐级打开并使用 `O_NOFOLLOW`；
- [ ] 消除路径校验与实际操作之间的 TOCTOU。

## T3.5 Pull commit 幂等化

状态：

```text
OPEN → TRANSFERRING → READY → COMMITTED
                         ↘ ABORTED
```

- [ ] 重复 commit 返回相同结果；
- [ ] commit 响应丢失后可查询或重试；
- [ ] abort 后返回稳定错误；
- [ ] 本地已落盘但远端确认失败时，不笼统报告为全部失败。

---

# 阶段 4：协议统一和流式执行

> 建议升级为 ExecPlane v0.2，作为 PR 4 实施。

## T4.1 统一真实 exec 参数模型

目标格式：

```json
{
  "cmd": ["/bin/sh", "-lc", "git status | cat"],
  "shell": true,
  "cwd": "/workspace",
  "env": {},
  "envMode": "overlay",
  "timeoutMs": 600000
}
```

- [ ] Android 将 `shell_execute` 字符串显式包装为 `[/bin/sh, -lc, command]`；
- [ ] Python 默认使用 `create_subprocess_exec`；
- [ ] 删除隐式字符串 `create_subprocess_shell` 分支；
- [ ] `ProtocolValidator.validateExec()` 用于真实生产路径；
- [ ] Kotlin 与 Python 共用 JSON 测试向量。

## T4.2 capabilities 加入协议握手

建议响应：

```json
{
  "protocol": "0.2",
  "serverId": "stable-id",
  "name": "sandbox-name",
  "caps": [],
  "limits": {
    "maxOutputBytes": 20971520,
    "maxTransferBytes": 268435456,
    "maxTimeoutMs": 3600000
  }
}
```

- [ ] 先验证版本、identity、capability 和 limits，再标记 online；
- [ ] 错误协议版本不进入连接注册表；
- [ ] 缺少 `exec` capability 时拒绝 Shell；
- [ ] UI 展示关键限制。

## T4.3 实现流式输出事件

事件示例：

```json
{
  "event": "exec.output",
  "data": {
    "requestId": 12,
    "sequence": 3,
    "stream": "stdout",
    "data": "..."
  }
}
```

- [ ] stdout/stderr 分离；
- [ ] 每块有 sequence；
- [ ] Android 使用有界队列；
- [ ] 消费慢时不能无限堆积；
- [ ] 最终响应包含 exitCode、duration、字节数和截断状态；
- [ ] 长构建可实时展示输出。

## T4.4 实现 cancel

- [ ] `cancel` 绑定原 request ID 和连接 identity；
- [ ] 取消对应进程组；
- [ ] 返回稳定 `EXEC_CANCELLED`；
- [ ] 重复取消幂等；
- [ ] peer A 不得取消 peer B 的任务；
- [ ] Android coroutine 取消时尝试发送 cancel；
- [ ] 连接断开时取消该连接启动的命令。

---

# 阶段 5：认证、信任和审计

> 建议作为 PR 5 实施。

## T5.1 Python 使用常量时间 Token 比较

- [ ] 使用 `hmac.compare_digest()`；
- [ ] 限制 Token 最短长度；
- [ ] Token 不进入异常和日志；
- [ ] 鉴权失败统一使用关闭码 1008。

## T5.2 反向连接默认 RESTRICTED

- [ ] 删除所有反向连接强制设为 `LOCAL` 的逻辑；
- [ ] 新反向连接默认 `RESTRICTED`；
- [ ] executor 自报 trust 不直接采信；
- [ ] 用户可持久化提升为 `STANDARD` 或 `TRUSTED`；
- [ ] `LOCAL` 仅用于真正的 App 本地执行端。

## T5.3 增加执行审计记录

记录：

- [ ] 时间和 sessionId；
- [ ] executor 名称与连接方向；
- [ ] 显式/默认目标；
- [ ] 是否降级；
- [ ] 命令 SHA-256 和安全截断预览；
- [ ] 环境变量名称，不记录值；
- [ ] 开始/结束时间和耗时；
- [ ] 退出码、错误码；
- [ ] 输出字节数和截断状态。

禁止记录：Token、环境变量值、完整敏感命令和文件内容。

## T5.4 设置页增加风险信息

- [ ] 提示 `ws://` 仅允许 loopback；
- [ ] 提示 `wss://` 仍依赖证书和服务身份；
- [ ] `EnvironmentPolicy.ALL` 增加二次确认；
- [ ] 明确 WS 执行端拥有其运行用户的 Shell 权限；
- [ ] 展示连接方向、trust、capabilities 和在线状态；
- [ ] 明确 WS 与 App/PRoot 不共享文件系统。

---

# 阶段 6：端到端验证和发布门禁

## T6.1 Android ↔ Python 端到端测试

- [ ] 鉴权成功和失败；
- [ ] capability/version 握手；
- [ ] 普通命令和非零退出；
- [ ] timeout、cancel 和输出超限；
- [ ] 连接中断和自动重连；
- [ ] 文件读写；
- [ ] 文件与目录 push/pull；
- [ ] 校验失败；
- [ ] 覆盖失败恢复。

## T6.2 故障注入

- [ ] 响应发送前断线；
- [ ] chunk 发送一半断线；
- [ ] commit 成功但响应丢失；
- [ ] 磁盘空间不足；
- [ ] ZIP 解压中途失败；
- [ ] 重连期间旧 socket 回调；
- [ ] 同名 executor 同时上线；
- [ ] Android 网络切换和应用切后台。

## T6.3 发布门禁

每次 ExecPlane 相关改动至少运行：

```sh
./gradlew :app:testDebugUnitTest
./gradlew :app:compileDebugKotlin
python3 -m unittest discover -s tools/execplane/tests -v
```

协议或传输重要变更还必须通过：

- [ ] 正向 WS 真机测试；
- [ ] 反向 WS 真机测试；
- [ ] 文件和目录传输测试；
- [ ] 默认 WS → PRoot 降级测试；
- [ ] 显式 WS 禁止降级测试。

---

# 3. 推荐提交批次

## PR 1：连接正确性

范围：`T0.1`、`T1.1`～`T1.5`。

建议提交描述：

```text
修复 WS 沙箱断线清理与正反向路由
```

## PR 2：资源保护

范围：`T2.1`～`T2.5`。

建议提交描述：

```text
增加 WS 执行端资源上限与进程清理
```

## PR 3：事务化文件传输

范围：`T3.1`～`T3.5`。

建议提交描述：

```text
加固 WS 文件传输与覆盖事务
```

## PR 4：ExecPlane v0.2

范围：`T4.1`～`T4.4`，同时更新共享协议测试向量。

建议提交描述：

```text
升级 ExecPlane 协议并支持流式执行
```

## PR 5：信任和审计

范围：`T5.1`～`T5.4`、`T6` 发布门禁。

建议提交描述：

```text
完善 WS 沙箱信任策略与执行审计
```

---

# 4. 优先级总表

| ID | 任务 | 优先级 | 阻塞生产使用 |
|---|---|---:|---:|
| T1.1 | pending 按连接隔离 | P1 | 是 |
| T1.2 | 断线立即清理 pending | P1 | 是 |
| T1.3 | 反向 executor 路由 | P1 | 宣称支持反向时是 |
| T1.5 | 结构化错误和降级 | P1 | 是 |
| T2.1 | 输出硬上限 | P1 | 是 |
| T2.2 | 进程组清理 | P1 | 是 |
| T3.1 | Android Pull 事务替换 | P1 | 是 |
| T3.2 | Python Push 事务替换 | P1 | 是 |
| T1.4 | disabled 默认目标 | P2 | 否 |
| T2.3 | 并发限制 | P2 | 远程部署时是 |
| T2.4 | 临时空间配额 | P2 | 远程部署时是 |
| T3.3 | 符号链接拒绝 | P2 | 是 |
| T4.1 | 协议模型统一 | P2 | 长期是 |
| T4.2 | 协议握手 | P2 | 长期是 |
| T4.3 | 流式输出 | P2 | 否 |
| T4.4 | cancel | P2 | 否 |
| T5.2 | 信任策略 | P2 | 自动调度前必须 |
| T5.3 | 审计日志 | P2 | 扩大使用范围前必须 |

# 5. 第一轮施工范围

第一轮严格限定为：

1. pending 生命周期和连接隔离；
2. 反向 executor Shell 路由；
3. 结构化错误与仅 `CHANNEL_*` 降级；
4. disabled 默认目标修复；
5. 同步相关文档和注释；
6. 补充对应 Kotlin 回归测试。

第一轮不混入输出流式化、协议 v0.2 或文件传输重构，避免单次改动过大。