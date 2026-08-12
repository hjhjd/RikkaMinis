# Android VCPToolBox 级联停止

## 1. 背景与目标

Minis 原有的停止操作主要发生在客户端：取消 Agent 协程、关闭流式 HTTP 请求，并停止当前会话中的本地 Shell 命令。这能立即停止 Android 端的等待和渲染，但对于作为中转层运行的 VCPToolBox，仅关闭客户端任务并不能在所有场景下明确表达“用户要求终止服务端上游请求和工具链”。

VCPToolBox 提供了非标准的请求中断协议：

```http
POST /v1/interrupt
Authorization: Bearer <与聊天请求相同的凭据>
Content-Type: application/json

{
  "requestId": "<聊天请求携带的 requestId>"
}
```

本功能在现有本地停止机制之上增加服务端通知，使一次停止同时覆盖：

1. Android Agent 协程；
2. Android 到模型服务的 HTTP/SSE 连接；
3. 当前会话中的本地 Shell/PRoot 命令；
4. VCPToolBox 维护的活动请求、上游请求和工具链。

该协议是 **VCPToolBox 专属扩展**，不是 OpenAI 标准 API 的一部分。

## 2. 用户侧行为

### 2.1 设置入口

在符合条件的 Provider 实例的“API 与连接”页面显示：

- 标题：`VCPToolBox 级联停止`
- 说明：`停止生成时，同时通知 VCPToolBox 终止服务端的上游请求和工具链。`
- 页脚：`仅适用于支持 /v1/interrupt 协议的 VCPToolBox 服务端。`

设置项只在以下条件全部满足时显示：

- Provider 类型为 OpenAI；
- 使用 API Key 凭据；
- 配置了自定义 Base URL；
- API 格式为 Chat Completions；
- 未启用 Azure OpenAI 模式。

默认关闭，避免向普通 OpenAI 兼容服务发送非标准字段。

### 2.2 Agent 授权范围

开启后可选择：

- `所有 Agent`：该 Provider 下所有 Agent 会话都可使用级联停止；
- `指定 Agent`：只有选中的 Agent 会话使用级联停止。

选择“指定 Agent”但列表为空时，没有 Agent 获得权限；所有会话继续使用默认停止方式。

授权关系属于 **Provider 实例 × Agent**，而不是 Agent 的全局布尔属性。这样同一个 Agent 可以在 VCPToolBox Provider 上使用级联停止，同时在普通 Provider 上保持默认停止。

## 3. 协议与请求生命周期

### 3.1 请求标识

每次真实的 Chat Completions HTTP 请求独立生成：

```text
minis-<UUID>
```

并写入请求体：

```json
{
  "model": "...",
  "messages": [],
  "stream": true,
  "requestId": "minis-55fd676f-4164-4652-9991-d6d198a9c28a"
}
```

requestId 绑定单次 HTTP 请求，不复用会话 ID、聊天消息 ID 或 Agent ID。原因是一次 Agent 运行可能包含多轮模型调用、工具结果续答、自动重试和 Provider fallback。每次网络请求使用独立 ID，才能与 VCPToolBox 的 `activeRequests` 精确对应。

### 3.2 Agent 请求上下文

`LLMRequestContext` 将当前 `agentId` 作为单次调用上下文传给 Provider：

```kotlin
data class LLMRequestContext(
    val agentId: String? = null,
)
```

OpenAIProvider 在请求开始时根据以下条件计算本次请求是否启用级联停止：

```text
Provider 已开启 VCPToolBox 级联停止
AND 当前请求有 agentId
AND（授权所有 Agent OR agentId 在授权列表）
AND 使用 Chat Completions
AND 非 Azure
AND 非 OAuth/Codex
```

权限在请求启动时固化。即使之后 UI 状态或 Agent 发生变化，正在运行的请求仍按启动时保存的级联属性停止，避免竞态。

### 3.3 中断地址推导

中断地址从最终 Chat Completions URL 推导，而不是简单拼接固定主机：

```text
/v1/chat/completions          → /v1/interrupt
/api/v1/chat/completions      → /api/v1/interrupt
/proxy/api/v1/chat/completions → /proxy/api/v1/interrupt
```

这样可以保留反向代理的路径前缀。非 `/chat/completions` 地址不会生成中断地址。

### 3.4 鉴权

中断请求复制原聊天请求最终生成的：

- `Authorization`；
- `User-Agent`。

不会重新猜测 API Key，也不会复制 Content-Length、Transfer-Encoding 等与原请求体绑定的头。

## 4. 停止实现

### 4.1 总体链路

点击停止后执行：

```text
ChatScreen / ToolCallPill
  └─ ChatViewModel.cancelStream()
      ├─ currentProvider.cancelActiveRequest()
      │   ├─ 异步 POST /v1/interrupt（已授权时）
      │   └─ OkHttp Call.cancel()
      ├─ streamJob.cancel()
      ├─ flushAllStreamingDeltas()
      ├─ 清理 SessionActivityTracker
      ├─ ExecutionCoordinator.stopCurrentCommand()
      ├─ handleUserCancelledCleanup()
      └─ 如有排队消息，延迟恢复队列 drain
```

级联停止不会替代本地停止，而是在默认停止之上增加服务端中断通知。即使 `/v1/interrupt` 请求失败，本地停止仍立即执行。

### 4.2 主动取消 Provider

同步 `OkHttp Call.execute()` 和阻塞式 `reader.readLine()` 不一定能及时观察协程取消。若只调用 `streamJob.cancel()`，生产协程可能继续阻塞到服务器返回或读取超时。

因此 `LLMProvider` 增加：

```kotlin
fun cancelActiveRequest() = Unit
```

OpenAI、Anthropic 和 Gemini Provider 都保存当前流式 `Call` 的原子引用。停止时直接调用 `Call.cancel()` 撕裂 Socket，使阻塞 I/O 立即退出。

活动请求使用 `AtomicReference` 和 compare-and-set 清理，避免旧请求的延迟收尾错误清除新请求引用。

### 4.3 VCPToolBox 中断请求

OpenAIProvider 对已授权请求保存：

- 原始聊天 `Request`；
- 当前 OkHttp `Call`；
- `requestId`；
- 防重复发送的 `AtomicBoolean`。

停止时使用独立的短超时 OkHttp 请求异步调用 `/interrupt`：

- connect timeout：2 秒；
- write timeout：2 秒；
- read timeout：3 秒；
- call timeout：3 秒。

处理策略：

- HTTP 200：中断成功；
- HTTP 404：请求已完成或已由断连中止，按幂等结果处理；
- 其他状态：仅记录警告；
- 网络异常或超时：仅记录警告；
- 不显示聊天错误，不触发模型 fallback，不阻塞 Stop UI。

### 4.4 为什么后端可能记录 ClientDisconnect

当前实现先异步排队 `/v1/interrupt`，随后立即调用聊天 `Call.cancel()`。关闭原 SSE 连接通常比新的中断 HTTP 请求更快到达服务端，因此 VCPToolBox 日志可能首先出现：

```text
[ClientDisconnect] Request minis-... aborted due to response_close_before_finish.
Upstream cascade abort triggered.
```

这表示 VCPToolBox 已通过客户端断连触发上游级联终止，功能是成功的。随后到达的 `/interrupt` 可能返回 404，因为对应活动请求已经结束。

与 VCPMobile 的差异主要是 requestId 格式：VCPMobile 使用带业务前缀和时间信息的 ID，Minis 使用随机 UUID。VCPToolBox 只把它作为 `activeRequests` 的唯一键，两种格式在协议语义上没有差异。

当前选择“异步中断 + 立即断连”的理由是停止速度和可靠性优先。若未来希望后端日志优先显示 `[Interrupt]`，可以等待 `/interrupt` 响应或设置约 150–250ms 的短暂宽限后再关闭原连接，但这会增加停止延迟。

## 5. 默认停止与降级行为

以下情况不会发送 VCPToolBox 中断：

- Provider 未开启级联停止；
- 当前 Agent 未获授权；
- 未提供 Agent 上下文；
- Provider 不是自定义 OpenAI Chat Completions；
- 使用 Responses API；
- 使用 Azure；
- 使用 OAuth/Codex；
- 无法从最终 URL 推导 `/interrupt`。

这些情况仍会执行：

- Provider HTTP Call 取消；
- Agent streamJob 取消；
- 本地 Shell 停止；
- 流式增量落盘和取消状态对账。

## 6. 配置持久化

`ProviderInstance` 新增：

```kotlin
var vcpCascadeStopEnabled: Boolean = false
var vcpCascadeStopScope: CascadeStopScope = CascadeStopScope.allAgents
var vcpCascadeStopAgentIds: Set<String> = emptySet()
```

`CascadeStopScope`：

```kotlin
enum class CascadeStopScope {
    allAgents,
    selectedAgents,
}
```

Provider 配置以 Room 为权威，同时维护旧版 JSON 镜像。数据库从版本 5 升级到 6，新增：

```sql
vcp_cascade_stop_enabled INTEGER NOT NULL DEFAULT 0
vcp_cascade_stop_scope TEXT NOT NULL DEFAULT 'allAgents'
vcp_cascade_stop_agent_ids TEXT
```

Agent ID 集合以 JSON 数组保存。迁移是纯增量 ALTER TABLE，旧用户升级后默认关闭，不改变现有停止行为。

## 7. 文件变动

### 数据模型与数据库

- `data/model/ProviderConfig.kt`
  - 新增 `CascadeStopScope`；
  - 为 `ProviderInstance` 增加启用状态、授权范围和 Agent ID 集合。
- `data/db/ProviderInstanceEntity.kt`
  - 增加三个 Room 字段。
- `data/db/ProviderDatabase.kt`
  - 数据库版本升级到 6；
  - 新增 `MIGRATION_5_6`。
- `data/db/ProviderConfigMapping.kt`
  - 补齐 ProviderInstance 与 Room 实体的双向映射；
  - Agent ID 集合使用 JSON 编解码。

### Provider 与请求链路

- `provider/LLMProvider.kt`
  - 新增 `LLMRequestContext`；
  - 流式调用接受单次请求上下文；
  - 新增 `cancelActiveRequest()`。
- `provider/ProviderFactory.kt`
  - 将 Provider 实例的级联配置注入 OpenAIProvider。
- `provider/openai/OpenAIProvider.kt`
  - 按 Agent 权限决定是否生成 requestId；
  - 请求体写入 requestId；
  - 保存和主动取消活动 Call；
  - 推导中断 URL；
  - 异步调用 VCPToolBox `/interrupt`；
  - 防止重复中断与旧请求清理竞态。
- `provider/anthropic/AnthropicProvider.kt`
  - 保存活动 Call，并支持主动取消底层 HTTP 请求。
- `provider/gemini/GeminiProvider.kt`
  - 保存活动 Call，并支持主动取消底层 HTTP 请求。

### Chat 与设置 UI

- `ui/chat/ChatViewModel.kt`
  - 模型请求携带当前 Agent ID；
  - Stop 时先主动取消当前 Provider 的 HTTP 请求。
- `ui/settings/ProviderConnectionScreen.kt`
  - 增加 VCPToolBox 级联停止设置；
  - 增加“所有 Agent / 指定 Agent”范围选择；
  - 增加 Agent 多选列表和空授权提示。
- `ui/navigation/AppNavigation.kt`
  - 向 Provider 连接设置页传入 AgentRepository。
- `res/values/strings.xml`
  - 增加英文设置文案。
- `res/values-zh/strings.xml`
  - 增加中文设置文案。

### 测试

- `src/test/.../OpenAIProviderTest.kt`
  - 验证反向代理路径前缀得以保留；
  - 验证 Responses API 不生成中断地址；
  - 验证授权 Agent 停止时确实发送 `/v1/interrupt`；
  - 验证聊天请求与中断请求使用相同 requestId；
  - 验证中断请求复用 Bearer 鉴权。

## 8. 验证方式

编译：

```sh
cd src/android
export JAVA_HOME=/home/nova/tools/jdk-17.0.20+8
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew :app:assembleDebug
```

相关单元测试：

```sh
./gradlew :app:testDebugUnitTest \
  --tests 'com.openminis.app.provider.OpenAIProviderTest' \
  --tests 'com.openminis.app.data.repository.ProviderConfigSnapshotTest'
```

手工验证建议：

1. 创建指向 VCPToolBox 的自定义 OpenAI Provider；
2. 保持 Chat Completions、关闭 Azure 和 Responses API；
3. 开启“VCPToolBox 级联停止”；
4. 分别测试“所有 Agent”和“指定 Agent”；
5. 发起一个长响应或长工具链，在运行期间点击 Stop；
6. 确认 Android UI 立即停止；
7. 确认 VCPToolBox 出现 `[Interrupt]` 或 `[ClientDisconnect] ... Upstream cascade abort triggered`；
8. 对未授权 Agent 重复测试，确认不附加级联 requestId，仍能使用默认本地停止。

## 9. 安全与兼容性

- 功能默认关闭；
- 只在用户明确配置的自定义 OpenAI Chat Completions Provider 上开放；
- 不记录或输出 Authorization 值；
- requestId 使用随机 UUID，不携带 Agent 名称、会话内容或用户信息；
- 中断失败不会影响本地停止；
- 数据库迁移保留已有 Provider、模型组和凭据配置；
- 普通 OpenAI、Anthropic、Gemini、Azure、Responses API 和 Codex OAuth 不会收到 VCPToolBox 专属字段。
