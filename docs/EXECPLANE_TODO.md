# ExecPlane 实现 TODO

> 依据：`PLAN.md` v0.3（2026-08-09）  
> 原则：先打通 Droidspaces 反向 `exec` 最小闭环，再扩展文件、正向连接、调度和降级；优先复用项目现有实现。

## 可复用资产盘点

- [x] OkHttp 4.12 已引入；`VoiceProviderVendors.kt` 有 `newWebSocket`、监听、收发和关闭范例，可复用于正向 WS 与连接生命周期。
- [x] kotlinx.serialization-json 已引入，可用于协议消息编解码，避免引入第二套 JSON 框架。
- [x] `ExecutionCoordinator` 已支持 PRoot 命令执行、超时、逐行回调和 session 隔离；直接作为降级执行后端。
- [x] `DebugServer` 已有 JSON-RPC、常量时间 token 比较、按安装生成 token 的实现经验；复用安全设计，不复用仅限 debug 的服务本身。
- [x] `AgentForegroundService` 已处理 Android 后台存活、通知与 wake lock；优先共用现有前台服务，不新建第二个常驻 FGS。
- [ ] 复用现成 WS 代码前，提取最小公共生命周期封装；不要复制 `VoiceProviderVendors` 的业务协议代码。

## 阶段 1：Droidspaces 反向 exec 最小闭环

### 1.1 协议核心（纯 Kotlin，可单测）

- [ ] 新建 `execplane/protocol`：协议版本、请求、响应、事件、错误码。
- [ ] 固定 v0.1 消息格式：`id/method/params/target/ts`。
- [ ] 实现 `register` 参数校验：`name/caps/resources/trust/tags`。
- [ ] 实现 `exec` 参数校验：`cmd/cwd/env/timeout`。
- [ ] 区分 `CHANNEL_*` 与 `EXEC_*`，禁止用字符串猜测是否降级。
- [ ] 对未知字段向前兼容，对缺失必填字段返回稳定错误码。
- [ ] 单测：合法/非法请求、未知方法、版本不兼容、错误码分类。

### 1.2 连接与注册表

- [ ] 新建 `ConnectionManager`，维护 executor 快照：名称、方向、能力、资源、信任、在线状态、最近心跳。
- [ ] 明确同名 executor 重连策略：新连接替换旧连接，旧连接关闭并留日志。
- [ ] 连接关闭时原子标记离线，并清理该连接上的 pending requests。
- [ ] 提供只读 `StateFlow`，供设置页、调度器和通知观察。
- [ ] 单测：注册、重复注册、断开、并发更新、快照不可变。

### 1.3 反向 WS 服务端

- [ ] 选定轻量 WS 服务端实现；只监听 loopback，默认端口可配置且避免与 DebugServer 5321 冲突。
- [ ] WS 握手阶段校验 token；失败使用 1008/HTTP 401，不进入协议层。
- [ ] 连接建立后必须先 `register`，注册超时即关闭。
- [ ] 实现 ping/pong 与连续 3 次、每次 5 秒的失联判定。
- [ ] 将启动/停止接入 `AgentForegroundService` 生命周期。
- [ ] 禁止 release 日志打印 token、完整环境变量或敏感命令参数。

### 1.4 Guard 最小安全闭环

- [ ] 创建每安装随机 token，使用 Android 安全存储持久化。
- [ ] 方法白名单首期仅开放 `register/ping/status/exec/cancel`。
- [ ] 命令白名单首期采用“可执行文件 + 参数规则”，禁止只做字符串前缀匹配。
- [ ] 拒绝换行注入、shell 拼接和未批准的 cwd 越界。
- [ ] 审计记录：时间、executor、method、命令摘要、决策、退出码、耗时；敏感值脱敏。
- [ ] 单测：错误 token、禁用方法、命令绕过样例、cwd 越界、脱敏。

### 1.5 执行体参考实现

- [ ] 在 `tools/execplane/` 新建单文件 Python `ws-agent.py`，优先标准库之外仅依赖 `websockets`。
- [ ] 支持反向连接、token、register、exec、stdout/stderr/exit 事件、timeout、cancel。
- [ ] 指数退避重连（带 jitter，上限 30 秒），成功注册后重置退避。
- [ ] 提供 `requirements.txt`、示例配置和 Droidspaces 启动脚本。
- [ ] 默认命令执行不用 `shell=True`；如协议明确请求 shell，必须由 Guard 单独授权。
- [ ] Python 端协议测试与 Android 端共享 JSON 测试向量。

### 1.6 最小闭环验收

- [ ] Droidspaces 启动 agent 后自动向 Minis 报到。
- [ ] Minis 发起 `uname -a`，输出来自 Droidspaces 而非 PRoot。
- [ ] 错误 token 100% 无法注册。
- [ ] `exit != 0` 返回 `EXEC_*`，不得触发 PRoot 重跑。
- [ ] 断开 agent 后 pending 请求以 `CHANNEL_DISCONNECTED` 结束。

## 阶段 2：文件传输与完整流式输出

- [ ] `file_get/file_put` 使用分块二进制或分块 base64；禁止整文件一次读入内存。
- [ ] 每块带序号与校验，整文件带 SHA-256；支持取消和超时。
- [ ] 限制单文件大小、允许根目录和目标路径，防止 `..`/符号链接逃逸。
- [ ] stdout/stderr 分流，事件包含 request id、sequence、stream。
- [ ] 对背压设置有界队列，慢消费者不能拖垮 app。
- [ ] 验收：执行体编译 APK，实时回传日志，APK 校验后落到 `/var/minis/attachments/`。

## 阶段 3：正向 WS 与远程执行体

- [ ] 提取并复用 OkHttp WS 客户端公共连接封装。
- [ ] 配置 endpoint、TLS、token、证书策略和重连策略。
- [ ] 禁止远程明文 `ws://`，仅 loopback 可例外。
- [ ] 正向/反向连接统一映射为同一个 `ExecutorConnection` 接口。
- [ ] 验收：仅改配置接入 VPS，无协议代码改动。

## 阶段 4：多执行体调度

- [ ] `Scheduler` 按 required caps、tags、trust、在线状态筛选。
- [ ] 先实现确定性排序，不做复杂负载均衡：显式 target > 本地高信任 > 远程 > PRoot。
- [ ] 显式 target 离线时返回错误，不自动改投其他执行体。
- [ ] 记录每次路由选择及未选择原因。
- [ ] 验收：同一命令可指定任意在线执行体，自动路由结果稳定可解释。

## 阶段 5：降级链与自愈

- [ ] 定义任务所需 caps，建立 PRoot 本地能力快照。
- [ ] 仅 `CHANNEL_*` 触发降级；`EXEC_*` 原样返回。
- [ ] 首选不可用且 PRoot 能力满足时，通过 `ExecutionCoordinator` 执行并标记 `degraded=true`。
- [ ] PRoot 能力不足时挂起任务并通知用户，不静默失败。
- [ ] executor 恢复后只回切后续任务，不迁移进行中任务。
- [ ] 降级/回切通知去重，避免网络抖动造成通知风暴。
- [ ] 验收：离线→PRoot→恢复→WS 全链路自动完成且可审计。

## 阶段 6：设置、运维与加固

- [ ] 设置页：总开关、监听地址/端口、token 重置、执行体列表、连接方向、白名单、审计查看。
- [ ] token 只允许复制/重置，不在普通日志或备份中明文导出。
- [ ] 高危命令接入人在回路确认；确认绑定命令摘要和 executor，过期失效。
- [ ] 远程双向 TLS/证书固定。
- [ ] 连接数、并发任务数、输出速率、文件大小全部设硬上限。
- [ ] 断线 30 秒内恢复；Doze、切后台、进程重建覆盖仪器测试。
- [ ] 补充用户文档、协议文档、部署文档和故障排查。

## 首轮提交边界

首轮只提交以下内容，保持可审查：

1. 本 TODO；
2. v0.1 协议模型与错误分类；
3. `ConnectionManager` 的纯逻辑骨架；
4. 共享 JSON 测试向量及 JVM 单测。

**首轮不启动网络监听、不暴露命令执行入口。** 等协议与安全边界经测试固定后，再接 WS 服务端和 Droidspaces agent，避免先做出一个未经 Guard 约束的远程 shell。
