# Android 分布式节点连接设计与进展

## 1. 文档目的

本文记录 VCPMinis Android 端接入 VCPToolBox 分布式节点能力的设计思路、模块边界、关联文件、当前完成情况和后续计划。

当前阶段只实现**连接通信基础设施**，不包含工具注册、工具调用、静态占位符和设备遥测。这样可以先独立验证鉴权路径、WebSocket 生命周期、服务端确认和断线恢复，再在稳定连接之上增加工具协议。

## 2. 参考实现

设计参考：

```text
/home/nova/workspace/VCPMobile-main/src/features/distributed/DistributedSettingsSection.vue
/home/nova/workspace/VCPMobile-main/src/features/distributed/composables/useDistributed.ts
/home/nova/workspace/VCPMobile-main/src-tauri/src/distributed/client.rs
/home/nova/workspace/VCPMobile-main/src-tauri/src/distributed/types.rs
/home/nova/workspace/VCPMobile-main/src-tauri/src/distributed/tool_registry.rs
```

参考的是架构和协议思路，而不是 Vue、TypeScript 或 Rust 的具体写法。

从 VCPMobile 提取出的关键原则：

1. 手机作为节点主动连接 VCPToolBox，而不是等待外部直接访问手机端口。
2. 持久化设置表示用户期望状态，应用级连接管理器负责调和实际状态。
3. WebSocket 握手成功不等于业务连接成功；收到 `connection_ack` 才进入已连接状态。
4. 连接状态是单一数据源，UI 只订阅状态，不自行维护连接副本。
5. 断线后自动重连，并防止旧连接回调污染新连接状态。
6. 工具协议和连接生命周期解耦，可分阶段实现。

## 3. 当前范围

### 3.1 已实现

- 分布式连接配置持久化。
- VCP Key 加密保存。
- `ws://` 和 `wss://` 地址校验。
- VCPToolBox 兼容的鉴权连接地址。
- 应用级 OkHttp WebSocket 连接。
- `DISCONNECTED / CONNECTING / CONNECTED / DISCONNECTING` 状态机。
- 解析 VCPToolBox 的 `connection_ack`。
- 保存并展示 `serverId` 和 `clientId`。
- WebSocket Ping 保活。
- ACK 等待超时。
- 断线指数退避重连。
- generation 隔离旧连接回调。
- 修改配置后主动重建连接。
- 应用冷启动时恢复已启用连接。
- Compose 设置入口、配置表单和状态显示。
- 地址构造与协议地址校验单元测试。

### 3.2 明确未实现

- `register_tools` 工具注册。
- `execute_tool` 请求处理。
- `tool_result` 结果回传。
- 单工具启用、禁用和授权策略。
- `report_ip` IP 上报。
- `update_static_placeholders` 静态占位符推送。
- Android 设备能力和传感器采集。
- 工具调用确认 UI。
- 分布式调用审计日志。
- 独立前台服务和专用 WakeLock 策略。

当前客户端会忽略除 `connection_ack` 之外的文本消息，不会执行服务端发来的工具请求。

## 4. 总体架构

```text
┌──────────────────────────────────────────────┐
│ DistributedSettingsScreen                   │
│ 配置输入、开关、连接状态                     │
└──────────────────────┬───────────────────────┘
                       │ save / reconcile
                       ▼
┌──────────────────────────────────────────────┐
│ DistributedSettingsRepository               │
│ EncryptedSharedPreferences + StateFlow       │
│ enabled / wsUrl / vcpKey / deviceName        │
└──────────────────────┬───────────────────────┘
                       │ 期望配置
                       ▼
┌──────────────────────────────────────────────┐
│ DistributedConnectionManager                │
│ 应用级 WebSocket、状态机、ACK、重连           │
│ StateFlow<DistributedConnectionStatus>       │
└──────────────────────┬───────────────────────┘
                       │ OkHttp WebSocket
                       ▼
┌──────────────────────────────────────────────┐
│ VCPToolBox distributed server               │
│ /vcp-distributed-server/VCP_Key=...          │
└──────────────────────────────────────────────┘
```

`MinisApp` 持有 Repository 和 ConnectionManager，确保连接生命周期不依赖设置页面是否处于组合树中。

## 5. 配置设计

配置模型：

```kotlin
data class DistributedConnectionConfig(
    val enabled: Boolean,
    val wsUrl: String,
    val vcpKey: String,
    val deviceName: String,
)
```

字段含义：

| 字段 | 含义 | 当前用途 |
|---|---|---|
| `enabled` | 用户期望是否启用节点 | 决定启动或停止连接 |
| `wsUrl` | VCPToolBox WebSocket 基础地址 | 构造连接 URL |
| `vcpKey` | VCPToolBox 鉴权密钥 | 构造兼容协议的鉴权路径 |
| `deviceName` | 节点名称 | 已持久化，等待工具注册阶段作为 `serverName` 上报 |

配置使用 `EncryptedPrefsFactory.safeCreate()` 保存。这样 VCP Key 不会进入普通明文 SharedPreferences；同时沿用项目已有的 Android Keystore 损坏自恢复策略。

开启连接时必须满足：

- URL scheme 是 `ws` 或 `wss`。
- URL 存在有效 host。
- URL 不含 userInfo 和 fragment。
- VCP Key 非空。

开关本身始终允许点击。配置有效性在用户尝试开启时校验，避免首次进入页面出现无法操作且没有错误说明的灰色开关。

## 6. 连接协议

### 6.1 连接地址

为兼容现有 VCPToolBox，连接地址为：

```text
{wsUrl}/vcp-distributed-server/VCP_Key={vcpKey}
```

示例：

```text
ws://192.168.1.2:5800/vcp-distributed-server/VCP_Key=example-key
```

实现不会直接拼接原始 Key，而是使用 OkHttp `HttpUrl` 的 path segment 编码规则处理斜杠、空格等特殊字符，再恢复 `ws/wss` scheme。

连接日志中的最后一个鉴权路径会替换为：

```text
VCP_Key=***
```

### 6.2 服务端确认

WebSocket `onOpen` 只表示传输层建立，状态仍保持 `CONNECTING`。只有收到：

```json
{
  "type": "connection_ack",
  "data": {
    "serverId": "...",
    "clientId": "..."
  }
}
```

客户端才进入 `CONNECTED`。

如果 WebSocket 建立后 15 秒内没有收到 ACK，客户端取消该连接，随后进入自动重连流程。这可以避免连接到了错误的 WebSocket 服务后永久显示“连接中”。

## 7. 状态机

```text
DISCONNECTED
    │ 用户开启 / 冷启动恢复
    ▼
CONNECTING
    │ 收到 connection_ack
    ▼
CONNECTED
    │ 用户关闭
    ▼
DISCONNECTING
    │
    ▼
DISCONNECTED
```

异常断线时：

```text
CONNECTED 或 CONNECTING
    │ onFailure / onClosed / ACK 超时
    ▼
CONNECTING（携带 lastError 和重试倒计时）
```

状态快照：

```kotlin
data class DistributedConnectionStatus(
    val state: DistributedConnectionState,
    val serverId: String?,
    val clientId: String?,
    val lastError: String?,
    val reconnectDelaySeconds: Long?,
)
```

UI 通过 `StateFlow` 订阅此快照，不轮询 WebSocket。

## 8. 重连与并发安全

### 8.1 指数退避

重试间隔：

```text
5 秒 → 10 秒 → 20 秒 → 40 秒 → 60 秒
```

达到 60 秒后保持 60 秒上限。建立 WebSocket 后退避重置为 5 秒。

### 8.2 Generation 隔离

每次创建连接时递增 generation。所有 Listener 回调都必须同时满足：

- 当前未被用户停止。
- 回调对应的 socket 仍是管理器当前 socket。
- 回调 generation 等于管理器当前 generation。

因此，用户修改配置并立即重连时，旧 socket 延迟到达的 `onClosed` 或 `onFailure` 不会覆盖新连接状态，也不会额外安排重连。

### 8.3 任务清理

启动、重连和停止过程中会清理：

- 旧 WebSocket。
- 旧重试 Job。
- 旧 ACK 超时 Job。

用户主动关闭后，`stopped` 标志会阻止任何后续自动重连。

## 9. 应用生命周期

关联逻辑位于 `MinisApp.onCreate()`：

```text
创建 DistributedSettingsRepository
    ↓
创建 DistributedConnectionManager
    ↓
调用 reconcile()
    ↓
enabled=false：保持停止
或
enabled=true 且配置有效：恢复连接
```

选择 Application 级生命周期的原因：

- 设置页面退出后连接不能断开。
- Compose 重组不能重复创建 WebSocket。
- 应用进程重启后需要恢复用户期望状态。
- 后续工具注册表和调用执行器也需要稳定的应用级宿主。

当前没有额外启动 Android Service。进程存活时连接会保持；进程被系统杀死后，要等应用进程再次启动才会恢复。长期后台存活策略留待后续阶段评估。

## 10. UI 设计

设置入口：

```text
设置 → Agent Runtime → 分布式连接
```

页面提供：

- 分布式节点总开关。
- 实时连接状态。
- WebSocket 地址。
- VCP Key 密码输入框。
- 节点名称。
- 保存配置。
- 启用状态下保存并重新连接。
- 服务端错误与重试倒计时。
- 当前阶段不支持工具调用的明确说明。

显示状态包括：

- 未连接。
- 连接中，等待服务器确认。
- N 秒后重试。
- 已连接及 `serverId`。
- 断开中。
- `clientId`。
- 最近一次连接错误。

## 11. 关联文件

### 11.1 核心连接模块

| 文件 | 职责 |
|---|---|
| `src/android/app/src/main/java/com/openminis/app/distributed/DistributedSettingsRepository.kt` | 加密配置持久化、配置 StateFlow、URL 校验 |
| `src/android/app/src/main/java/com/openminis/app/distributed/DistributedConnectionManager.kt` | WebSocket、状态机、ACK、超时、重连和 generation 隔离 |

### 11.2 应用与 UI 接线

| 文件 | 职责 |
|---|---|
| `src/android/app/src/main/java/com/openminis/app/MinisApp.kt` | 创建应用级 Repository 和 ConnectionManager，冷启动调和 |
| `src/android/app/src/main/java/com/openminis/app/ui/settings/DistributedSettingsScreen.kt` | 分布式连接设置页面 |
| `src/android/app/src/main/java/com/openminis/app/ui/settings/SettingsScreen.kt` | 主设置页入口 |
| `src/android/app/src/main/java/com/openminis/app/ui/navigation/AppNavigation.kt` | `distributed` 路由和页面导航 |
| `src/android/app/src/main/res/values/strings.xml` | 默认英文文案 |
| `src/android/app/src/main/res/values-zh/strings.xml` | 简体中文文案 |

### 11.3 测试

| 文件 | 职责 |
|---|---|
| `src/android/app/src/test/java/com/openminis/app/distributed/DistributedConnectionUrlTest.kt` | 鉴权路径编码和 WebSocket URL 校验 |

## 12. 验证情况

已经完成：

```text
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest --tests \
  'com.openminis.app.distributed.DistributedConnectionUrlTest'
./gradlew :app:assembleDebug
```

结果均为：

```text
BUILD SUCCESSFUL
```

真机已经验证：

- 设置页可保存服务器地址和 Key。
- 分布式节点开关可正常开启、关闭。
- 客户端可以连接 VCPToolBox。
- 收到 `connection_ack` 后页面显示连接成功。

## 13. 当前进展

截至本文编写时，连接通信基础阶段已经完成：

```text
[完成] 配置模型与加密持久化
[完成] 设置入口与表单
[完成] WebSocket 鉴权连接
[完成] connection_ack 业务确认
[完成] 状态 StateFlow
[完成] ACK 超时
[完成] Ping 保活
[完成] 指数退避重连
[完成] 旧连接回调隔离
[完成] 冷启动自动恢复
[完成] 编译、单测、Debug APK
[完成] 真机连接成功验证
[未开始] 工具注册
[未开始] 工具调用
[未开始] 工具权限和审计
[未开始] 静态占位符与遥测
```

连接功能代码提交：

```text
98abee0 实现 Android 分布式节点连接通信
```

## 14. 已知限制

1. **VCP Key 位于 URL 路径**
   这是现有 VCPToolBox 协议的兼容要求。客户端日志已经脱敏，但服务端、反向代理和网络诊断工具仍可能记录 URL。后续如果服务端支持 Header 鉴权，应迁移到 Header。

2. **没有 Android 前台服务**
   当前连接由应用进程持有。系统在后台回收进程后连接会消失，应用下次启动时恢复。不要在工具协议完成前过早引入高耗电的长期前台服务。

3. **节点名称尚未上报**
   当前只持久化 `deviceName`。它会在实现 `register_tools` 时映射到协议的 `serverName`。

4. **未知消息被忽略**
   当前只识别 `connection_ack`，这是刻意的安全边界。即便服务端下发 `execute_tool`，客户端也不会执行。

5. **测试暂时集中在 URL 层**
   后续应使用 MockWebServer WebSocket 增加 ACK、超时、断线、重连和旧 generation 回调测试。

6. **非简体中文语言使用英文回退**
   当前新增了默认英文与简体中文资源，其他语言目录暂未翻译。

## 15. 后续建议

下一阶段建议仍保持小步实现：

### 阶段二：协议骨架和空工具注册

1. 定义序列化协议模型。
2. 收到 ACK 后发送 `register_tools`。
3. 第一轮可以发送空工具数组或一个专用测试 manifest，用来验证服务端注册链路。
4. 在状态中增加已注册工具数量和注册错误。
5. 增加 MockWebServer 端到端协议测试。

### 阶段三：第一个只读工具

只增加一个低风险、无运行时权限的设备摘要工具，打通：

```text
execute_tool → requestId 去重 → 超时执行 → tool_result
```

此阶段同时加入：

- 最大并发限制。
- 请求超时。
- 结构化错误码。
- 断线取消待执行请求。
- 工具默认禁用策略。

### 阶段四：权限和敏感能力

再逐步接入定位、剪贴板、通知等 Android 能力，并为每个工具定义：

- 关闭。
- 直接允许。
- 每次询问。
- 仅前台允许。
- Android 运行时权限。
- 审计记录和结果裁剪。

不建议把现有 `NativeOffloadHandler` 全量直接暴露给远程服务器。
