# Android VCPInfo 消息中心设计与进展

## 1. 定位

VCPInfo 消息中心是一个只读认知广播观察器。它连接 VCPToolBox 的 `/vcpinfo` WebSocket，接收并展示 RAG 检索、元思考链、Agent 私聊预览、记忆检索、DailyNote 和 Agent 梦境事件。

本模块与分布式工具节点职责不同：

- 分布式节点连接 `/vcp-distributed-server`，未来用于注册和执行工具。
- VCPInfo 连接 `/vcpinfo`，只被动接收认知广播。
- 两者复用同一服务器地址和 VCP Key，但使用两个独立 WebSocket 和独立状态机。

参考：

- `VCPMobile-main/docs/modules/23_RAG灵视中心.md`
- `VCPMobile-main/docs/modules/15_分布式节点能力.md`
- `VCPMobile-main/src-tauri/src/vcp_modules/infra/vcp_info_service.rs`
- `VCPMobile-main/src/features/rag/RagObserver.vue`

## 2. 数据流

```text
VCPToolBox /vcpinfo/VCP_Key=...
              │
              ▼
VcpInfoConnectionManager
  - 连接、Ping、重连、generation
              │ raw JSON
              ▼
VcpInfoStore
  - 分类解析、500 条有界队列、未读计数
              │ StateFlow
              ▼
VcpInfoCenterScreen / SettingsScreen
  - 在线状态、未读提示、过滤、卡片和详情
```

## 3. 连接设计

连接地址：

```text
{wsUrl}/vcpinfo/VCP_Key={vcpKey}
```

Key 使用 URL path segment 编码，日志统一显示 `VCP_Key=***`。

VCPInfo 服务没有 `connection_ack` 业务确认，因此 WebSocket `onOpen` 即视为在线。连接参数：

- 连接超时：10 秒。
- Ping：15 秒。
- 重连：1、2、4、8、16、32、60 秒，上限 60 秒。
- 配置变化：取消旧 socket 并建立新 socket。
- generation：忽略旧连接延迟到达的关闭和失败回调。
- 总开关：当前跟随分布式节点总开关。

## 4. 消息模型

```kotlin
data class VcpInfoMessage(
    val id: String,
    val type: String,
    val category: VcpInfoCategory,
    val title: String,
    val subtitle: String?,
    val summary: String,
    val timestamp: String,
    val hasDetails: Boolean,
    val rawJson: String,
    val receivedAtMs: Long,
)
```

支持分类：

| 分类 | 协议类型 |
|---|---|
| RAG | 含 `dbName` 和 `results` 的检索事件，包括 `RAG_RETRIEVAL_DETAILS` |
| 思考链 | `META_THINKING_CHAIN` |
| Agent 会话 | `AGENT_PRIVATE_CHAT_PREVIEW` |
| 记忆 | `AI_MEMO_RETRIEVAL`、`DailyNote` |
| 梦境 | `AGENT_DREAM_*`、`AGENT_DREAM_SCHEDULE` |

不属于认知广播的 JSON 会被过滤。非法 JSON 和超过 512 KiB 的单条消息也会被丢弃。

## 5. 缓存与未读

当前使用进程内有界缓存：

- 最多 500 条，最新消息在前。
- 元数据和原始 JSON 保存在同一不可变消息对象中。
- 打开消息中心时清零未读，并在页面可见期间不累加未读。
- 页面不可见时新消息增加未读，最大值与缓存容量一致。
- 支持手动清空。

与 VCPMobile 的 zstd 双层缓存不同，本阶段没有引入压缩依赖。原因是 Android 端已限制单条载荷和队列长度，直接保存 JSON 实现更简单，也避免详情索引与压缩缓存不一致。后续真机内存数据表明有必要时再引入压缩。

缓存不落数据库。进程被系统回收后历史消息消失，这是当前明确边界。

## 6. UI

入口：

```text
设置 → Agent Runtime → VCPInfo 消息中心
```

主设置入口会显示：

- 未读消息数；或
- 已连接状态；或
- 默认功能说明。

消息中心提供：

- 连接状态、消息总数和错误信息。
- 手动重连和清空。
- 全部、RAG、思考链、Agent 会话、记忆、梦境过滤标签。
- 参考 VCPMobile 的紧凑分类标签和细边卡片，降低首屏留白与单卡高度。
- 分类色卡片、标题、副标题、摘要和时间。
- 按消息类型结构化详情：检索请求、标签、策略、召回结果、元思考流/阶段/K/召回项、USER/AI、召回记忆、梦境种子/联想/叙事/操作。
- 使用语义色板区分内部数据：RAG 蓝、思考链紫、标签增强金、记忆绿、Agent 会话橙、梦境粉、错误红；深色模式使用同色低透明背景。
- 原始 JSON 收进末尾“调试数据”二级折叠区，不再作为默认详情主体。
- 复制格式化 JSON。

本阶段没有照搬 Canvas 频谱动画。原实现的动画属于视觉装饰，不影响消息中心功能；Compose 版优先保证高频消息列表的滚动和重组成本可控。

## 7. 关联文件

| 文件 | 职责 |
|---|---|
| `src/android/app/src/main/java/com/openminis/app/vcpinfo/VcpInfoModels.kt` | 消息模型、分类和摘要提取 |
| `src/android/app/src/main/java/com/openminis/app/vcpinfo/VcpInfoStore.kt` | 有界内存队列、详情和未读状态 |
| `src/android/app/src/main/java/com/openminis/app/vcpinfo/VcpInfoConnectionManager.kt` | WebSocket 生命周期、Ping 和重连 |
| `src/android/app/src/main/java/com/openminis/app/ui/settings/VcpInfoCenterScreen.kt` | Compose 消息中心和紧凑卡片布局 |
| `src/android/app/src/main/java/com/openminis/app/ui/settings/VcpInfoDetailContent.kt` | 各类消息的结构化详情与调试 JSON 折叠区 |
| `src/android/app/src/main/java/com/openminis/app/MinisApp.kt` | 应用级服务初始化和冷启动恢复 |
| `src/android/app/src/main/java/com/openminis/app/ui/settings/SettingsScreen.kt` | 入口、未读和在线状态 |
| `src/android/app/src/main/java/com/openminis/app/ui/settings/DistributedSettingsScreen.kt` | 配置变更时同步调和 VCPInfo 连接 |
| `src/android/app/src/main/java/com/openminis/app/ui/navigation/AppNavigation.kt` | `vcp_info` 路由 |
| `src/android/app/src/test/java/com/openminis/app/vcpinfo/VcpInfoTest.kt` | URL、分类、容量和未读测试 |

## 8. 当前进展

```text
[完成] 独立 /vcpinfo WebSocket
[完成] 复用分布式服务器凭据
[完成] 15 秒 Ping
[完成] 1–60 秒指数退避
[完成] generation 旧回调隔离
[完成] 六类认知消息解析
[完成] 非认知消息过滤
[完成] 500 条有界缓存
[完成] 单条 512 KiB 限制
[完成] 未读计数
[完成] 紧凑分类过滤和细边消息卡片
[完成] 各消息类型结构化详情 UI
[完成] 原始 JSON 二级调试折叠与复制
[完成] URL、分类、容量、未读测试
[未做] 消息数据库持久化
[未做] zstd 压缩缓存
[未做] 频谱动画
[未做] 后台系统通知
[未做] 独立于分布式节点的 VCPInfo 开关
```

## 9. 后续建议

1. 使用 MockWebServer 增加真实 WebSocket 收包、断线和重连测试。
2. 根据真机消息载荷统计决定是否增加压缩或 Room 持久化。
3. 如果用户希望只观察消息而不启用分布式节点，增加独立 `vcpInfoEnabled` 配置。
4. 增加搜索以及按 Agent/知识库二级过滤，并继续完善少见自定义事件的字段映射。
5. 高频广播场景下做批量 StateFlow 更新，避免每条消息触发一次完整列表重组。
