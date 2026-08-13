# Android 沙箱命令执行器审计整改 TODO

> 创建日期：2026-08-13
>
> 审计基线：`44f3a53`
>
> 状态：持续施工；不透明 Dispatch 主链路已完成，旧协议与 PRoot 整改待继续。

## 目标

将 Android 的 WS ExecPlane 从“内置 `exec`、`fs.*`、`transfer.*` 等业务指令及参数语义”改造成**单一不透明指令发送器**。Android 只负责选择沙箱、发送 payload、关联请求、限制资源、取消和展示结果；`exec`、`push`、`pull`、`query` 等全部业务语法由 WS 服务端定义和解析。

WS 服务端连接后回传一份已经构造好的 AI 指令集。Android 只展示版本和内容并提供“复制全部”；不解析、不自动注入模型上下文。用户审阅后自行粘贴、修改和编排到 Agent/System Prompt，模型据此构造不透明 payload。

本次方向先限定于 **WS 沙箱**。Android 原生工具和内置 PRoot 暂时保留，随后再拆分生命周期并修复审计发现的超时失控、路径逃逸、输出 OOM 和回收竞态。

## 目标架构

```text
WS 服务端 ──握手──> instructionSet（纯展示、可复制）
                             ↓ 用户审阅/复制/粘贴
LLM ── sandbox_dispatch(sandbox, payload) ──> Android
                                                    ↓ 不解析 payload
                                              WS dispatch 通道
                                                    ↓
                                      服务端解析 exec/push/pull/任意 DSL
```

Android 固定控制面暂定：

```text
capabilities / dispatch / cancel
事件：dispatch.output / dispatch.completed
握手元数据：instructionSet
后续基础设施：通用 resource 上传/下载通道
```

其中 `dispatch`、`cancel` 和事件信封属于传输协议，不是服务端业务命令；Android 永远不解析 payload 中的首词和参数。

## 原则

- 先解耦，后修复；每一步保持可编译、可回滚。
- Android 对 WS payload 完全不透明：不得识别、改写或按 `exec/push/pull` 等 verb 分发。
- 模型侧只暴露一个通用 `sandbox_dispatch`，参数至少包含 `sandbox`、`payload`、`timeout`、`tool_title`。
- 服务端指令集只展示和复制，绝不自动注入 AI 提示词；用户是唯一的注入和编排决策者。
- 指令集按稳定 sandbox/server identity 归属，显示名称不能作为路由身份。
- 指令集属于不可信远端文本：限制大小，展示来源、revision、更新时间；更新时不自动覆盖用户已有提示词。
- Android 保留最小固定传输控制面、鉴权、请求关联、超时、取消、并发及输入输出上限。
- 业务级权限和 DSL 校验由 WS 服务端承担；Android 只控制某个会话是否允许访问整个沙箱。
- 显式指定沙箱失败时不得静默改投其他沙箱或 PRoot；取消和有副作用调用不得重放。
- 文本 dispatch 先落地；跨 Android/WS 的文件搬运后续抽象成通用 resource 通道，而不是 Android 内置 `push/pull`。
- PRoot 不是强安全边界；文档和 UI 不做超出 Android App UID 能力的承诺。
- 本地与远端可共用调用事件、结果、取消和截断模型，但不强求共享业务语义。
- 文件路径解析集中实现，禁止本地工具自行拼接宿主路径。
- 新逻辑先补可在 JVM 跑的单元测试，设备相关行为再补 instrumentation test。

---

## 阶段 0：建立回归基线

- [ ] 记录当前 `shell_execute` 本地/远端行为矩阵：cwd、环境变量、profile、后台任务、超时、取消、stdout/stderr、退出码、截断。
- [ ] 为关键缺陷增加失败测试或最小复现：
  - [ ] 超时后 guest 命令仍继续运行。
  - [ ] marker 跨读取块无法识别。
  - [ ] UTF-8 多字节字符跨读取块损坏。
  - [ ] `/var/minis/workspace/../../...` 路径逃逸。
  - [ ] 符号链接逃逸。
  - [ ] 空闲清理误杀长命令。
  - [ ] Shell 回收时同 session 创建第二把 mutex。
  - [ ] 远端持续输出导致无界内存增长。
  - [ ] 远端 `truncated=true` 未传播。
- [ ] 保存一组端到端 smoke commands，供每阶段验证。

## 阶段 1：建立通用调用骨架（已启动，按新方向收敛）

### 1.1 已完成的可复用基础

- [x] 定义 `ToolProviderId`、`ToolIdentity`、`ToolInvocationEvent`、`ToolInvocationResult`。
- [x] 定义 `ToolProvider` / `ToolRegistry` 初版并接管现有工具定义聚合。
- [x] 将现有硬编码工具包装成临时兼容 Provider，保持行为不变。
- [x] 为 Registry 增加聚合、禁用过滤、重名拒绝和移除测试。

### 1.2 按不透明 dispatch 精简抽象

- [x] WS 路径不再使用“一条服务端命令一个 `ToolDescriptor`”模型。
- [x] 将 WS 调用参数收敛为 sandbox、opaque payload、timeout；request ID 由传输层生成和关联。
- [x] 将 WS 结果收敛为流式 Output 与最终成功/失败结果。
- [ ] 评估并移除仅为动态多工具 manifest 引入的 `ToolManifest` 复杂度；本地 Provider/Registry 可继续服务 Android/PRoot 工具。
- [x] 模型侧 WS 工具固定为一个 `sandbox_dispatch` 定义，不随服务端业务指令变化。

### 1.3 从 ChatViewModel 解耦

- [x] `ChatViewModel` 已接入唯一 `sandbox_dispatch`，只提交 sandbox、payload、timeout/delay 并消费文本结果。
- [ ] 用 `Flow`/`Channel` 传递事件，替代 WebSocket 线程直接修改 UI。
- [ ] 输出按 50–100 ms 聚合刷新，禁止每行启动一个 Main coroutine。
- [x] UI 预览保留有限尾部，完整结果由通道层限制为 1 MiB。
- [x] 旧本地工具执行分发暂时保留；WS 新功能禁止继续增加业务命令分支。

**阶段 1 完成条件**

- WS 调用入口只接受 sandbox identity 与不透明 payload。
- Android 不再为 WS 的新增业务指令增加 schema、参数类或 `when(toolName)` 分支。
- 现有本地工具和 PRoot 行为暂时保持不变。

---

## 阶段 2：引入 WS 不透明 dispatch 与可复制指令集

### 2.1 最小协议控制面

- [x] 设计并实现 ExecPlane 最小协议：
  - `capabilities`：协议、identity、limits 与指令集元数据；
  - `dispatch`：发送不透明 UTF-8 payload；
  - `cancel`：按 request ID 取消；
  - `dispatch.output`：流式事件，最终结果使用对应 RPC response。
- [x] 请求信封保留 request ID、timeout 和时间戳；业务 payload 不参与 Android 路由。（session ID 暂由 Android 工具层持有，不发送给服务端）
- [x] Android 不 trim、拆词、补前缀、Shell 转义或识别 payload verb。
- [x] 显式 sandbox 离线、未知或协议不兼容时直接失败，不回退、不重放。
- [x] 为 payload、单事件、累计输出、最终响应和并发设置客户端硬上限。

### 2.2 指令集回传与复制 UI

- [x] 握手增加 `instructionSet`：title、revision、content、updatedAt。
- [x] 指令集只是服务端提供的纯文本/Markdown，不生成动态工具 schema。
- [x] Android 在对应 WS 沙箱详情中展示来源沙箱、revision 和完整内容。
- [x] 提供“复制全部指令”按钮，复制内容逐字保持，不自动添加或删改提示。
- [x] 指令集不会自动进入 system prompt、Agent prompt、会话记忆或剪贴板。
- [ ] 指令集更新提示 revision 变化和更新时间/长度展示；不得覆盖用户已有内容。
- [x] 限制指令集为 256 KiB；反向注册异常内容会拒绝，正向异常握手不会上线。

### 2.3 单一模型工具

- [x] 增加固定 `sandbox_dispatch` 工具定义：sandbox、payload、timeout、delay、tool_title。
- [ ] 将工具参数从名称解析升级为稳定 server/sandbox ID；当前兼容层仍接受精确名称或连接解析名。
- [ ] 允许会话/Agent 决定是否暴露 `sandbox_dispatch` 以及允许访问哪些沙箱。
- [x] Android 不对 payload 做业务级权限判断；WS 服务端插件负责 DSL 权限和执行限制。
- [x] 接通正向/反向流式输出与协程超时/取消；后续再迁入统一 Provider event Flow。

**阶段 2 完成条件**

- [x] 服务端新增或修改 `exec/push/pull/query` 等语法时，无需修改或重新编译 Android。
- [x] 用户能查看并一键复制服务端完整指令集，但 App 不会自动注入。
- [x] 模型通过唯一 `sandbox_dispatch` 将不透明字符串发送给明确指定的 WS 沙箱。
- [x] 端到端验证：VCPMinis 启用示例插件后，`payload="exec ls"` 被服务端原样回显，证明 Android 未解析或偷偷执行。
- [x] 默认中文工具提示词已区分 PRoot 与 WS，禁止猜测服务端 DSL、错误降级或混用文件系统。

---

## 阶段 3：收敛本地执行生命周期与旧协议适配

### 3.1 本地执行模型

- [ ] PRoot 执行实现为 `PRootToolProvider` 的内部能力，不再要求动态 WS 工具实现 `SandboxExecutor`。
- [ ] 定义本地 Shell 统一结果：
  - stdout/stderr 或规范化 output；
  - exitCode；
  - durationMs；
  - timedOut；
  - cancelled；
  - truncated。
- [ ] 定义本地 `ActiveExecutionHandle.cancel()`。
- [ ] `ExecutionCoordinator` 只管理 PRoot session 状态和执行生命周期，不再承担 WS 路由或 UI 文本格式化。

### 3.2 PRoot 会话状态

- [ ] 用单一 `SessionExecutionState` 聚合：
  - session mutex；
  - 当前 Shell；
  - 当前 execution handle；
  - isExecuting；
  - lastActivity；
  - recycleRequested；
  - injectedEnvKeys。
- [ ] Shell 回收与 mutex 生命周期分离。
- [ ] 只有会话真正结束且无活动执行时才删除 session state。
- [ ] 资源监控只请求回收，由持锁执行路径完成最终清理。

### 3.3 旧 ExecPlane 迁移

- [x] 冻结 Android 端现有 `exec`、`fs.*`、`transfer.*` 业务功能，不再新增调用点。
- [x] 新版服务端可通过可配置 Python dispatch 插件定义任意 payload DSL；Android 新路径只调用 `dispatch`。
- [ ] 迁移期旧协议放入单独适配层；主业务层不得直接调用 `SandboxFileService` 或具体 RPC method。
- [ ] 明确旧协议弃用窗口和服务端最低兼容版本。
- [ ] 文本 dispatch 稳定后，移除 WS `shell_execute`、远端 `file_read/write/edit` 的 Android 特殊分支。
- [ ] `transfer.*` 在通用 resource 通道落地前保留，仅用于兼容旧客户端/服务端，不作为新架构入口。
- [ ] 显式 WS dispatch 永不降级到 PRoot；通道失败只返回通道错误。

**阶段 3 完成条件**

- PRoot 生命周期与 WS dispatch 互不耦合。
- 新 WS 路径只有一个不透明 dispatch 业务入口。
- 旧 ExecPlane RPC 只存在于兼容层。
- 用户停止操作能通过通用 invocation handle 取消实际调用。
- PRoot session 串行保证不依赖可被执行中途删除的 mutex map。

---

## 阶段 4：修复高优先级缺陷

### P0

- [ ] **超时后立即终止本地执行**：回收对应 PRoot Shell，确保命令不再继续修改文件或占用资源。
- [ ] **限制远端输出内存**：
  - [ ] 单个 WebSocket 输出事件硬上限；
  - [ ] 流式累计输出硬上限；
  - [ ] 最终 stdout/stderr 合计硬上限；
  - [ ] 超限后继续排空但不继续累计；
  - [ ] 正确返回 `truncated=true`。
- [ ] 移除 `SandboxCommandRouter` 中无界 `StringBuilder streamed`，只保留“是否收到流事件”或有界缓冲。

### P1

- [ ] **统一安全路径解析器**：对所有 Linux → host 路径执行 canonical containment。
- [ ] 拒绝 NUL、绝对 relative tail、`..` 逃逸及不允许的符号链接。
- [ ] 文件读写编辑、图片、媒体、调试接口和 transfer 共用同一 resolver。
- [ ] 对 sessionId 使用安全格式或不可伪造的内部目录键。
- [ ] 空闲回收必须检查 `isExecuting=false`，命令开始和结束都更新活动时间。
- [ ] 修复执行中回收导致同 session 两把 mutex 的竞态。
- [ ] marker 解析改为跨 chunk 增量状态机。
- [ ] 使用 `CharsetDecoder` 正确处理跨 chunk UTF-8。

**阶段 4 完成条件**

- 超时/取消返回后，对应命令已停止。
- 普通文件工具无法越出允许根目录。
- 恶意或异常远端输出不能造成 App 无界内存增长。
- 同 session 命令在回收和取消场景下仍严格串行。

---

## 阶段 5：明确本地 Shell 语义与工具契约

- [ ] 决定最终 Shell 模型：
  - 方案 A：每次调用独立进程；
  - 方案 B：每 session 持久 Shell。
- [ ] 优先评估方案 A。它能删除 marker 协议、持久 Shell 状态泄漏和大量回收竞态。
- [ ] 若保留方案 B：
  - [ ] 工具描述明确 cwd、export 和后台任务会在同 session 延续；
  - [ ] timeout/cancel 明确会重建 Shell；
  - [ ] 切换 sandbox 不共享 Shell 状态；
  - [ ] 增加状态重置命令或 API。
- [ ] WS payload 不承诺任何 `/bin/sh` 语义；具体 DSL 及行为仅由用户复制的服务端指令集说明。
- [ ] 对旧 WS `exec` 兼容路径记录 login/profile、stderr 和独立进程行为，避免伪装成与 PRoot 完全相同。
- [ ] 将 WS 入口从 `shell_execute` 中移出，使用固定 `sandbox_dispatch`；`shell_execute` 最终只代表本地 PRoot（若保留）。
- [ ] 通用展示层统一 timeout、cancelled、truncated、sandboxName；WS dispatch 禁止 fallback。

---

## 阶段 6：通用 Resource 通道与纵深防御

### 6.1 通用 Resource 通道（文本 dispatch 稳定后）

- [ ] 定义与业务 verb 无关的 resource descriptor：resourceId、name、size、sha256、mimeType。
- [ ] dispatch 可附带零个或多个 resource 引用；Android 只搬运字节，不解释 payload 如何引用资源。
- [ ] 服务端结果可返回 resource；Android 提供通用下载、保存和预览能力。
- [ ] 支持分块、校验、大小上限、临时空间上限、取消和失败清理。
- [ ] 禁止让模型把大文件 Base64 塞入 payload 或工具结果。
- [ ] resource 通道完成后再移除旧 `transfer.*`。

### 6.2 纵深防御

- [ ] 只读外部挂载在 UI/文档中标明为软限制，不宣称为安全边界。
- [ ] 挂载配置从磁盘/备份恢复时重新校验名称、数量、URI、重复项和 resolved path。
- [ ] PRoot PID 获取失败时输出一次诊断警告，不得静默关闭 RSS 监控。
- [ ] 为远端 WebSocket 增加消息、payload、指令集和协议字段长度限制。
- [ ] 审核日志，确保 token、环境变量值、完整敏感 payload 和代理认证信息不会写入日志。
- [ ] 删除或隔离已弃用的 `ShellExecutor`，避免生产代码重新走旧路径。
- [ ] 更新架构文档、服务端指令集规范和故障排查文档。

---

## 建议拆分提交

1. `重构：收敛 WS 调用模型为不透明 SandboxDispatch`
2. `协议：增加 dispatch cancel 和流式事件模型`
3. `协议：握手增加可复制 AI 指令集元数据`
4. `界面：在 WS 沙箱详情展示并复制完整指令集`
5. `功能：增加唯一 sandbox_dispatch 模型工具`
6. `重构：移除 ViewModel 中 WS 业务命令分发`
7. `重构：隔离旧 exec fs transfer 协议适配器`
8. `修复：限制 WebSocket payload 和输出内存占用`
9. `重构：集中管理 PRoot 会话执行状态和取消句柄`
10. `修复：超时和取消时终止本地 PRoot 命令`
11. `修复：阻止沙箱文件路径穿越和符号链接逃逸`
12. `修复：避免空闲回收和内存回收破坏会话串行`
13. `修复：支持跨块 marker 与 UTF-8 增量解析`
14. `功能：增加不透明通用 Resource 传输通道`
15. `测试：补充 dispatch 指令集取消限额和沙箱安全用例`
16. `文档：说明不透明指令通道和旧协议迁移策略`

## 暂不处理

- 第一版 dispatch 只传文本，不同时重做二进制 resource 通道。
- 不自动把服务端指令集注入任何 AI 提示词、记忆或会话。
- 不解析服务端指令集，也不为 `exec/push/pull` 生成动态工具 schema。
- 不在 Android 对不透明 payload 实施业务级 verb 权限；该边界属于服务端。
- 不在 dispatch 解耦前重写完整 PRoot 生命周期。
- 不立即删除旧 ExecPlane `exec`、`fs.*`、`transfer.*`；先放入兼容适配层。
- 不把 shell wrapper 只读保护升级描述为强隔离。
- 不在没有行为基线和测试的情况下直接删除 PersistentShell。
- 不顺带调整无关 UI、Provider 或备份模块。

## 验收命令

```sh
cd /home/nova/workspace/RikkaMinis/src/android
export JAVA_HOME=/home/nova/tools/jdk-17.0.20+8
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew :app:testDebugUnitTest --no-daemon
./gradlew :app:assembleDebug --no-daemon
```

设备可用时补跑：

```sh
./gradlew :app:connectedDebugAndroidTest --no-daemon
```
