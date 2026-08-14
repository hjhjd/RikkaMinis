# Android VCP Log Service 实施说明

## 1. 目标与结论

VCPMinis Android 已实现与 VCPMobile `vcp_log_service.rs` 协议和生命周期对应的 VCP Log Service：连接桌面端 VCPToolBox 的 `/VCPlog` WebSocket，接收 VCP 系统事件，支持主动发送 JSON、状态观测、运行时配置切换、动态心跳、指数退避、网络恢复重连及前后台策略。

当前实现包含应用级连接管理器、有界事件 Store、聊天页右侧事件中心、日志管理详情页、未读提醒和单元测试。VCPLog 仍严格作为远端系统事件通道，不会自动上传 AppLogger、logcat、崩溃文件或认证数据。

### 1.1 明确边界

VCPLog 是 **VCPToolBox 系统事件通道**，不是 AppLogger 的远程日志上传器。VCPMobile 只把 `send_vcp_log_message(payload)` 显式提交的 JSON 发往服务端；它没有自动 tail 本地日志并上传。

因此 VCPMinis 首版：

- 接收桌面端 `vcp_log` 等 JSON 事件并在应用内展示；
- 提供显式 `send(payload)`；
- 不自动上传 `AppLogger`、logcat、崩溃文件、LLM 请求、OAuth 信息；
- 不改变现有 `AppLogger` 的文件轮转、logcat 捕获和日志管理页面能力。

这是安全边界，不建议为了“看起来像日志同步”而扩大范围。

## 2. 参考基线

主参考：

- `VCPMobile-main/docs/modules/21_基础设施杂项.md` 第 3 节
- `VCPMobile-main/src-tauri/src/vcp_modules/infra/vcp_log_service.rs`
- `VCPMobile-main/src-tauri/src/vcp_modules/infra/lifecycle_manager.rs`
- `VCPMobile-main/src-tauri/src/vcp_modules/infra/lifecycle_controller.rs`
- `VCPMobile-main/src-tauri/src/vcp_modules/infra/settings_manager.rs`
- `VCPMobile-main/src/core/composables/useNotificationProcessor.ts`

VCPMinis 可复用基线：

- `logging/AppLogger.kt`：本地诊断日志，不承担 VCPLog 传输
- `vcpinfo/VcpInfoConnectionManager.kt`：OkHttp WebSocket、状态流、退避和 generation 范式
- `vcpinfo/VcpInfoStore.kt`：有界进程内消息缓存范式
- `distributed/DistributedSettingsRepository.kt`：加密凭据持久化
- `ui/settings/LogManagementScreen.kt`：日志设置主入口
- `MinisApp.kt`：应用级长连接持有者

## 3. 从 VCPMobile 迁移的核心设计

### 3.1 状态模型

Android 使用强类型状态替代 Rust 字符串：

```kotlin
enum class VcpLogConnectionState {
    CLOSED,
    CONNECTING,
    CONNECTED,
    ERROR,
}

data class VcpLogConnectionStatus(
    val state: VcpLogConnectionState = CLOSED,
    val lastError: String? = null,
    val reconnectDelaySeconds: Long? = null,
    val connectedUrl: String? = null, // 必须脱敏
)
```

`StateFlow<VcpLogConnectionStatus>` 是连接状态唯一数据源。UI 不持有第二份状态，也不通过轮询判断 socket。

状态转换：

```text
CLOSED
  └─ 配置有效且启用 → CONNECTING
CONNECTING
  ├─ onOpen → CONNECTED
  ├─ 连接失败/超时 → ERROR → 退避 → CONNECTING
  └─ 禁用/配置清空 → CLOSED
CONNECTED
  ├─ 服务端断开/读写失败 → CLOSED/ERROR → 退避 → CONNECTING
  ├─ URL 或 Key 变化 → 关闭旧连接 → CONNECTING
  └─ 禁用 → CLOSED
```

VCPLog 没有业务层 `connection_ack`，WebSocket `onOpen` 即视为已连接；这一点与分布式节点不同。

### 3.2 单任务所有权与配置热切换

VCPMobile 用 `watch::channel<Option<Url>>` 保存最新地址，并用原子标志防止重复 listener。Kotlin 对应设计：

- 一个应用级 `VcpLogConnectionManager`；
- 一个 `MutableStateFlow<VcpLogConfig>` 保存最新期望配置；
- 一个 `SupervisorJob + Dispatchers.IO` 连接协调器；
- `generation: AtomicLong` 隔离旧 socket 的延迟回调；
- `start/reconcile` 幂等，不允许重复连接协程；
- 配置变化时取消连接超时、心跳和重试 Job，关闭旧 socket，使用最新配置连接；
- 配置相同则不抖动重连。

这等价于 VCPMobile 的 `watch + LOG_CONNECTION_ACTIVE`，但更符合现有 Kotlin 工程范式。

### 3.3 URL 协议

以源码而不是文档文字为准。VCPMobile 实际地址形态是：

```text
{base}/VCPlog/VCP_Key={key}?deviceName={deviceName}
```

规则：

1. 只接受 `ws://` 和 `wss://`；
2. 去掉基础地址尾部 `/`；
3. 地址尚未包含 `/VCPlog` 时补齐，路径大小写保持 `VCPlog`；
4. 以 path segment 形式追加 `VCP_Key={key}`；
5. 追加 URL 编码后的 `deviceName` query；
6. 日志、状态和错误中始终把 Key 替换为 `***`；
7. 禁止 userInfo 和 fragment；
8. Key 必须通过 URL builder 编码，禁止原始字符串拼接。

兼容 VCPMobile 的握手回退：

- 首先尝试带 `deviceName` 的地址；
- 仅当服务端已返回 HTTP 握手错误（例如 400/404）时，尝试不带 `deviceName` 的旧地址；
- DNS、拒绝连接和超时不做第二次无意义尝试。

设备名使用用户配置值，空值回退 `VCPMinis`，不硬编码 `VCPChat-Mobile`。

### 3.4 握手头

对齐 VCPMobile：

- `Origin`：`ws → http`、`wss → https`，保留 host/port；
- `User-Agent`：使用稳定的 VCPMinis Android UA；
- `Host` 交给 WebSocket 库正确生成，不手工覆盖，避免 IPv6、代理及 TLS SNI 不一致。

连接超时按当前源码采用 **5 秒**，不是文档表格中的 10 秒。测试慢网络后若误报明显，再统一调整为 10 秒。

### 3.5 心跳

对齐 VCPMobile 的 WebSocket Ping 控制帧：

- 前台：15 秒；
- 后台：120 秒；
- 前后台变化后立即重置计时器；
- 不因心跳周期变化断线重连；
- Ping 失败视为连接失效并进入重连。

技术选择：优先使用工程已经依赖的 `org.java-websocket:Java-WebSocket:1.6.0`，因为其公开 `sendPing()`，可真正实现动态控制帧心跳。OkHttp 的 `pingInterval` 固定在 `OkHttpClient`，运行时调整通常意味着重建 client/socket，不满足“无需重连动态调整”的原设计。

在编码前应做一个最小协议探针，验证 Java-WebSocket 对目标 VCPToolBox 的 `Origin`、TLS 和关闭回调行为；若存在兼容问题，再退回 OkHttp，并接受“前后台切换时重连”的降级，不使用伪造 JSON 心跳。

### 3.6 重连

指数退避完全对齐：

```text
1s → 2s → 4s → 8s → 16s → 32s → 60s → 60s…
```

- 成功 `onOpen` 后重置为 1 秒；
- 配置变化立即打断退避等待；
- 用户禁用后绝不重连；
- 无网络时可以继续低频退避，后续可接 `NetworkMonitor` 在网络恢复时立即重试；
- 每一代连接回调必须通过 socket identity + generation 双检查。

### 3.7 收发消息

接收：

- 文本是合法 JSON：原样进入事件 Store；
- 非 JSON 文本：包装为 `{ "type": "raw_text", "data": text }`；
- 二进制消息：首版忽略并记录一条不含 payload 的 warning；
- 单条消息上限建议 512 KiB，超过即丢弃，防止桌面端异常载荷拖垮进程。

发送：

```kotlin
fun send(payload: JSONObject): Result<Unit>
```

- 只有 `CONNECTED` 且当前 socket 有效时发送；
- 未连接返回明确错误，不做无界离线积压；
- JSON 序列化失败不影响连接；
- 首版不向外暴露任意字符串发送，避免协议边界失控。

## 4. 事件模型与本地消息中心

VCPMobile 把事件发给 Vue 的 `vcp-system-event`。Compose 不需要事件总线桥接，使用有界 `StateFlow` Store：

```kotlin
data class VcpLogEvent(
    val id: String,
    val type: String,
    val status: String?,
    val toolName: String?,
    val content: String,
    val source: String?,
    val rawJson: String,
    val receivedAtMs: Long,
)
```

解析优先支持：

- `type = vcp_log`
- `type = vcp-log-message`
- `type = vcp-log-status`
- `type = raw_text`
- 未知 JSON 类型保留为通用事件，不能因前端不认识而丢失协议数据。

Store 约束：

- 最多 500 条；
- 单条最多 512 KiB；
- 最新在前；
- 支持清空、未读计数和按 id 读取原始 JSON；
- 不落 Room，进程被回收后历史消失；
- 不与 `AppLogger` 文件列表混合。

## 5. 前后台策略

使用 `ProcessLifecycleOwner` 注册应用级 `DefaultLifecycleObserver`：

### 5.1 进入后台

- 心跳切换为 120 秒；
- 继续接收事件，但只写入有界 Store；
- 不触发 Toast、导航或需要 Activity 的 UI 副作用；
- 启动 10 分钟 linger；
- linger 到期仍在后台时主动关闭 VCPLog；
- 标记为 `lingerDisconnected`，不进入常规重连。

### 5.2 返回前台

- 取消 linger；
- 心跳恢复 15 秒并立即重置；
- 若因 linger 断开且配置仍启用，立即重连；
- Store 中事件自然由 `StateFlow` 呈现，无需逐条“补发”到 WebView。

### 5.3 对上游缺陷的修正

当前 `vcp_log_service.rs` 的注释声称后台缓存可防止内存泄漏，但实现是无界 `Vec<Value>`；长时间高流量会反而导致 OOM。文档又称后台事件会丢弃，与源码不一致。

Android 不复制这个缺陷：统一使用容量 500、单条 512 KiB 的有界 Store。前后台只改变 UI 副作用和连接 linger，不建立第二个无界缓存。

## 6. 复用现有 VCPToolBox 服务器配置

### 6.1 直接复用 `DistributedSettingsRepository`

VCPMinis 已经在设置中持久化了一份可用的 VCPToolBox 服务器配置：

```kotlin
data class DistributedConnectionConfig(
    val enabled: Boolean,
    val wsUrl: String,
    val vcpKey: String,
    val deviceName: String,
)
```

`VcpInfoConnectionManager` 已经直接注入 `DistributedSettingsRepository`，并根据同一份 `wsUrl/vcpKey` 派生 `/vcpinfo` 地址。VCPLog 应沿用这一模式，根据同一份配置派生 `/VCPlog` 地址。

首版不新增 `VcpCoreSettingsRepository`，不迁移或复制凭据，也不增加第二套 VCP URL/Key 表单。这样可以避免两个配置源漂移，并保持现有 VCPInfo 行为不变。

复用范围：

- 复用 `enabled`、`wsUrl`、`vcpKey`、`deviceName`；
- 复用 `DistributedSettingsRepository.isValidWsUrl()` 校验；
- 复用 `EncryptedPrefsFactory` 已提供的加密存储；
- 设置保存后同时调用 Distributed、VCPInfo、VCPLog 三个 manager 的 `reconcile()`；
- 冷启动时三个 manager 都读取同一个 Repository 快照。

### 6.2 不能复用同一条 WebSocket

可以复用服务器配置，不能复用 `DistributedConnectionManager.socket`：

| 通道 | Endpoint | 协议完成条件 | 职责 |
|---|---|---|---|
| Distributed | `/vcp-distributed-server/VCP_Key=...` | 收到 `connection_ack` | 分布式节点注册与工具调用 |
| VCPInfo | `/vcpinfo/VCP_Key=...` | WebSocket `onOpen` | 只读认知广播 |
| VCPLog | `/VCPlog/VCP_Key=...` | WebSocket `onOpen` | VCP 系统事件收发 |

三个 endpoint 无法在一条 socket 上复用，且心跳、消息解析、状态和重连条件不同。正确结构是“一份服务器配置，三个独立连接管理器”。

首版继续跟随现有 `enabled` 总开关：关闭 VCPToolBox 服务器连接时，三条连接全部停止；开启并且 URL/Key 有效时分别调和。以后若确有独立启停需求，再增加通道级开关，不在本次引入。

## 7. UI 规划

### 7.1 入口

在 `设置 → 日志` 页面把现有二段切换扩展为三段：

```text
本地日志 | VCP Log | 配置变更
```

理由：VCP Log 与本地日志语义相关，但数据源不同；放在同一顶级页面、不同 Tab 比混入文件列表更清楚。

### 7.2 VCP Log 页面

包括：

1. **连接状态卡**：未启用、连接中、已连接、错误、重试倒计时；
2. **服务器配置摘要**：显示复用当前 VCPToolBox 服务器及脱敏地址，并提供“前往服务器连接设置”；
3. **操作**：立即重连、清空当前事件；
4. **过滤**：全部、工具、成功、错误、原始；
5. **事件列表**：时间、来源、tool name、status、摘要；
6. **详情**：格式化 JSON，支持复制；
7. **安全说明**：不会自动上传本地 AppLogger/logcat。

VCP Log 页面不重复提供 URL、Key 和设备名输入框，所有连接参数仍在现有 VCPToolBox 服务器连接页面统一维护。

连接错误在页面和状态流中展示，不对每一次退避都弹 Toast，避免桌面端关闭时每分钟骚扰用户。仅用户主动保存并连接失败时可给一次即时提示。

## 8. 拟新增与修改文件

### 8.1 新增

| 文件 | 职责 |
|---|---|
| `vcplog/VcpLogModels.kt` | 状态、事件模型和协议解析 |
| `vcplog/VcpLogStore.kt` | 500 条有界队列、未读、详情 |
| `vcplog/VcpLogConnectionManager.kt` | 注入现有 `DistributedSettingsRepository`；负责 WebSocket、URL、Ping、热切换、退避和 generation |
| `vcplog/VcpLogLifecycleObserver.kt` | 前后台心跳和 10 分钟 linger |
| `ui/settings/VcpLogScreen.kt` | 状态、服务器摘要、过滤、列表和操作 |
| `ui/settings/VcpLogDetailScreen.kt` | 结构化详情和原始 JSON |
| `test/.../vcplog/VcpLogUrlTest.kt` | URL、编码、脱敏、回退条件 |
| `test/.../vcplog/VcpLogStoreTest.kt` | JSON/raw、容量、大小限制、未读 |
| `test/.../vcplog/VcpLogConnectionManagerTest.kt` | 状态、代际隔离、重试、配置热切换 |

### 8.2 修改

| 文件 | 修改 |
|---|---|
| `MinisApp.kt` | 使用已有 `distributedSettingsRepository` 创建 VCPLog Manager/Store 和生命周期 Observer，冷启动 reconcile |
| `ui/settings/LogManagementScreen.kt` | 增加 VCP Log Tab |
| `ui/settings/SettingsScreen.kt` | 可选增加 VCPLog 连接/未读摘要 |
| `ui/settings/DistributedSettingsScreen.kt` | 保存、开关和手动重连后同时 reconcile Distributed、VCPInfo、VCPLog |
| `ui/navigation/AppNavigation.kt` | VCPLog 详情路由，以及从 VCPLog 页跳往现有服务器连接页 |
| `res/values*/strings.xml` | 中英文状态、错误和 UI 文案 |

以下文件首版不需要修改：

- `DistributedSettingsRepository.kt`：字段、加密存储和 URL 校验已经满足 VCPLog；
- `VcpInfoConnectionManager.kt`：继续复用现有服务器配置与独立 socket；
- `ConfigBuiltins.kt`：不新增第二套 VCP 凭据字段。

`AppLogger.kt` 首版无需修改。后续若要统一日志门面，只能增加显式 VCP 事件 API，不能暗中把所有本地日志转发到网络。

## 9. 分阶段实施顺序

### 阶段 A：协议与配置接入

1. 复用 `DistributedSettingsRepository`，明确 VCPLog 的启停和配置变化判定；
2. 完成 URL builder、Key 编码和脱敏；
3. 完成状态/事件模型和有界 Store；
4. 写纯 JVM 单元测试。

验收：所有特殊字符 Key、IPv4/IPv6、ws/wss、已有路径和 deviceName 均构造正确，任何输出不泄露 Key。

### 阶段 B：连接管理器

1. 完成单 socket 所有权和 generation；
2. 完成 5 秒连接超时；
3. 完成带 deviceName 优先、HTTP 握手错误才回退；
4. 完成 1–60 秒退避；
5. 完成文本收发及大小限制；
6. 用本地测试 WebSocket 验证 Ping、断线和配置切换。

验收：反复保存相同配置不重连；快速切换地址时旧回调不污染新状态；禁用后无重试 Job。

### 阶段 C：生命周期

1. 接入 `ProcessLifecycleOwner`；
2. 前台 15 秒、后台 120 秒动态 Ping；
3. 10 分钟后台 linger 与回前台恢复；
4. 验证 observer 不持有 Activity。

验收：短暂切后台不重连；超过 10 分钟冷断开；返回前台只建立一个连接。

### 阶段 D：UI

1. 日志页增加 VCP Log Tab；
2. 状态、配置、重连、清空、过滤和详情；
3. 未读状态；
4. 中英文资源和无障碍描述。

验收：桌面端停机时不重复 Toast；错误可诊断；原始 JSON 可查看复制；Key 从不回显。

### 阶段 E：三通道联调

1. 在现有服务器设置保存、开关和手动重连动作中加入 VCPLog reconcile；
2. 验证同一份 URL/Key/deviceName 可正确派生三个 endpoint；
3. 验证 VCPLog、VCPInfo、Distributed 三条独立 socket 同时工作，单条连接故障不会污染其他通道状态。

## 10. 测试矩阵

### 10.1 单元测试

- 基础 URL 有/无尾斜杠；
- 已含 `/VCPlog` 不重复追加；
- Key 含空格、`/`、`?`、`&`、Unicode；
- deviceName URL 编码；
- ws/wss 与 Origin 映射；
- URL 和异常脱敏；
- JSON、raw text、未知类型；
- 500 条淘汰和 512 KiB 拒绝；
- 状态转换和退避上限；
- generation 忽略旧回调；
- 配置相同不重连，配置变化立即重连；
- stop 后不重试。

### 10.2 集成测试

使用本地 WebSocket 测试服务：

- 验证握手 path、query、Origin、UA；
- 服务端收 Ping/Pong；
- 服务端推 JSON 和纯文本；
- 客户端主动发送 JSON；
- 服务端正常 Close、异常断开和拒绝握手；
- 第一次带 deviceName 返回 400，第二次旧地址成功；
- 连接过程中切换 URL；
- 退避中禁用；
- 超大消息不会导致 OOM。

### 10.3 真机测试

- Android 8 至当前目标版本至少各一台/模拟器；
- Wi-Fi 切蜂窝、飞行模式、锁屏、切后台 1 分钟与 11 分钟；
- MIUI/ColorOS 等激进后台策略；
- VCPToolBox 实机协议兼容；
- 长时间高频事件下 Store 内存稳定；
- Release 构建混淆后回调和 JSON 解析正常。

## 11. 安全与隐私

- Key 只存加密 SharedPreferences；
- 日志、状态、异常、调试 RPC 和配置读取均不返回 Key；
- `minis-config get` 对 Key 只返回 `configured: true/false`；
- 默认不上传本地日志、崩溃文件、OAuth token、Provider Key、提示词或聊天内容；
- VCPLog 收到的远端内容视为不可信输入：限制大小、仅展示文本、禁止执行 HTML/Intent；
- `ws://` 允许局域网兼容但 UI 明示未加密；公网地址应推荐 `wss://`；
- 连接失败文案不得包含完整 URL query/path 中的凭据。

## 12. 构建与提交检查

编码阶段使用指定环境：

```sh
cd /home/nova/workspace/VCPMinis/src/android
export JAVA_HOME=/home/nova/tools/jdk-17.0.20+8
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

提交前：

- 只提交源码、资源、测试和本文档；
- 不提交当前工作区中的 `provider-customization.properties`、`proot-aarch64` 和本地 `jniLibs` 未跟踪产物；
- 提交信息使用中文；
- 检查 `git diff --check`；
- grep 确认测试输出和源码日志不存在真实 VCP Key。

## 13. 验收定义

满足以下条件才算 VCP Log Service 首版完成：

1. 与真实 VCPToolBox `/VCPlog` 成功握手并接收事件；
2. URL/Key 运行时修改无需重启应用，且只有一个有效连接；
3. 前台 15 秒、后台 120 秒 Ping，10 分钟 linger 行为正确；
4. 断线按 1–60 秒退避自动恢复；
5. UI 可查看状态、错误、事件和原始 JSON；
6. 消息缓存有界，不存在后台无界积压；
7. Key 在存储外均脱敏；
8. 不自动外传 AppLogger/logcat；
9. 三个通道共享同一份服务器配置，但 socket、协议状态和故障处理相互独立；
10. 服务器总开关关闭后，Distributed、VCPInfo、VCPLog 均停止且不再重连；
11. 单元测试、集成测试与 Debug 构建通过。
