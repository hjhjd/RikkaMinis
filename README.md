# VCPMinis

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android%20arm64-brightgreen.svg)](#安装)
[![Build](https://github.com/hjhjd/VCPMinis/actions/workflows/build-apk.yml/badge.svg)](https://github.com/hjhjd/VCPMinis/actions/workflows/build-apk.yml)

**面向 VCPToolBox 生态的 Android AI Agent 客户端。**

VCPMinis fork 自 **RikkaMinis**，并沿用其 Android 原生聊天、模型接入、PRoot、浏览器、技能、记忆和系统工具基础。这个分支的主要方向不是继续使用 RikkaMinis 品牌，而是将客户端改造成适配 **VCPToolBox 服务端与 VCP 工作流** 的移动端实现。

项目已使用独立应用名 **VCPMinis** 和独立包名 `com.vcp.rikkaminis`。Android 包名保留 `com.vcp.rikkaminis` 以继承现有安装与数据；该兼容标识不代表当前产品名称。

> 这是一个持续演进的个人 fork。功能以实际代码和 Git 提交记录为准，不承诺与上游同步发布节奏。

---

## 项目定位

VCPMinis 希望在 Android 上提供一套完整的个人 Agent 工作台：

- 使用 Claude、GPT、Gemini 及 OpenAI 兼容服务；
- 将聊天按 **Agent 助手** 和 **话题** 组织；
- 对接 VCPToolBox 的请求中断、事件、消息和分布式连接能力；
- 在手机本地运行 PRoot Linux，也可连接外部 WebSocket 沙箱；
- 使用浏览器、技能、记忆、MCP、Android 系统能力和文件资源；
- 在移动端显示 VCP 思考、工具块、HTML 内容和系统事件。

它不是 VCPToolBox 服务端本身。VCPToolBox 负责服务端编排、上游请求和相关 VCP 能力；VCPMinis 是与其配合使用的 Android 客户端，同时也保留普通模型提供商和本地 PRoot 的独立使用能力。

---

## 主要改动

以下内容由近期 Git 历史归纳。详细实现、修复和提交边界请直接查看 [`git log`](https://github.com/hjhjd/VCPMinis/commits/main)。

### 1. Agent 助手与话题体系

VCPMinis 增加了完整的多 Agent 数据和界面基础：

- Agent 助手页、创建与编辑界面；
- 每个聊天话题绑定所属 Agent；
- 新话题继承 Agent 的默认模型和配置；
- 侧栏按当前 Agent 筛选话题，并显示 Agent 归属和头像；
- Agent 提示词、工具提示词和首选语言即时生效；
- Agent 级记忆目录隔离，避免不同助手共享私有记忆；
- Agent 配置、记忆和沙箱策略纳入备份恢复；
- 删除 Agent 时清理关联话题、规则、记忆和 Web App 数据。

当前聊天侧栏既是助手切换入口，也是话题管理入口。会话列表、长按操作、排序、置顶、草稿和输入历史均围绕这一结构进行了调整。

### 2. VCPToolBox 服务端适配

#### 级联停止

对于启用该能力的 OpenAI 兼容 Provider，用户停止生成时，客户端除取消本地协程和网络请求外，还可调用 VCPToolBox 的 `/v1/interrupt`：

```text
Android Agent / HTTP 流 / 本地工具
                  +
       VCPToolBox 上游请求与工具链
```

级联停止按 Agent 配置，适合多个助手使用不同 VCPToolBox 实例的场景。

详见 [Android VCPToolBox 级联停止](docs/VCPTOOLBOX_CASCADE_STOP_ANDROID.md)。

#### 分布式节点连接

增加面向 VCPToolBox 的应用级分布式 WebSocket 连接：

- VCP Key 加密保存；
- `ws://` / `wss://` 配置；
- `connection_ack` 业务握手；
- Ping、超时、自动重连和网络恢复；
- `serverId` / `clientId` 状态展示；
- 连接生命周期与界面状态解耦。

详见 [Android 分布式节点连接](docs/ANDROID_DISTRIBUTED_CONNECTION.md)。

#### VCPInfo 消息中心

增加只读 VCP 认知广播观察器，连接 VCPToolBox `/vcpinfo`，用于显示：

- RAG 检索；
- 元思考链；
- Agent 私聊预览；
- 记忆检索与 DailyNote；
- Agent 梦境等 VCPInfo 事件。

消息使用有界存储、分类过滤、未读计数和独立详情页。详见 [VCPInfo 消息中心](docs/ANDROID_VCPINFO_MESSAGE_CENTER.md)。

#### VCPLog 事件中心

聊天页增加右侧 VCPLog 事件栏，并提供独立日志详情页。它连接 VCPToolBox `/VCPlog`：

- 接收远端 VCP 系统事件；
- 支持未读提醒、分类和详情；
- 支持动态心跳、指数退避及前后台生命周期；
- 不自动上传 AppLogger、logcat、崩溃文件、OAuth 信息或本地敏感日志。

详见 [VCP Log Service](docs/ANDROID_VCP_LOG_SERVICE_PLAN.md)。

### 3. VCP 消息与富内容渲染

聊天消息流可识别并展示 VCP 专用内容块，同时保留原有 Provider 原生消息结构：

- VCP Thought / Tool Use / Tool Result；
- 角色内容与工具摘要；
- HTML Fence、HTML 文档和 HTML Fragment；
- 原生图片块；
- HTML 按钮回传聊天消息；
- WebView 离屏保活、尺寸测量和流式更新；
- 原生 Markdown、KaTeX、表格、媒体和工具块继续工作。

VCP 文本块不会伪装成客户端原生工具调用。详见 [VCP 消息渲染](docs/VCP_MESSAGE_RENDERING_ANDROID.md)。

### 4. VCPChatTarven 规则仓

新增请求级上下文注入规则系统，可配置：

- 注入内容；
- 注入位置；
- 全局或指定 Agent 生效；
- 排序、启停、编辑和预览；
- 系统提示词、用户消息后缀等不同规则类型。

规则只修改发送给模型的请求副本，不写回聊天历史，也不会破坏 `tool_use` / `tool_result` 配对。

详见 [VCPChatTarven 规则仓](docs/VCPCHATTARVEN_RULES.md)。

### 5. PRoot 与 WebSocket 沙箱

VCPMinis 保留两类明确分离的执行环境。

#### 本地 PRoot

`shell_execute` 只代表 Android 内置 PRoot：

- 每个聊天 session 使用独立的持久 `/bin/sh`；
- cwd、`export`、Shell 函数和后台任务可在同 session 延续；
- timeout/cancel 会终止原 Shell，下一次调用创建干净 Shell；
- 文件、图片和媒体工具默认只操作 Android/PRoot 文件空间；
- `/var/minis/` 提供 workspace、attachments、offloads、browser、shared、skills、memory 和授权挂载。

#### WebSocket 沙箱

外部沙箱使用唯一的 `sandbox_dispatch`：

- Android 只按稳定 sandbox ID 选路；
- `payload` 以原始 UTF-8 发送，Android 不解析、不补全、不转义；
- DSL 由 WS 服务端定义并通过可复制指令集说明；
- 显式失败不会回退到 PRoot，也不会自动重放有副作用请求；
- 指令集带 revision，更新时提示但不自动注入模型提示词；
- 支持有界流式输出、超时、取消、截断和 Agent 级沙箱白名单。

参考执行端位于 [`tools/execplane`](tools/execplane)，示例 VCPMinis DSL 提供 `help`、`status` 和 `exec`。这些 verb 不是 Android 内置协议；其他执行端可以提供完全不同的指令集。

#### 沙箱提示词占位符

沙箱运行状态不再由 `ChatViewModel` 强制追加到系统提示词末尾，而是在每次发送、重试或恢复 Agent Loop 时生成快照，并解析 Agent 人格提示词和工具提示词中的占位符。默认 [`default_tool_zh.md`](src/android/app/src/main/assets/prompts/default_tool_zh.md) 在末尾放置 `{{sandbox_runtime_context}}`，因此默认布局与原行为一致；Agent 可以移动、重复或删除占位符来自由控制排布。

| 占位符 | 解析内容 |
|---|---|
| `{{sandbox_runtime_context}}` | 完整沙箱运行上下文，包括模式、默认目标、首选目标、在线列表和路由要求 |
| `{{sandbox_mode}}` | 当前模式：`PRoot` 或 `WebSocket` |
| `{{sandbox_default_id}}` | 默认 WS 沙箱的稳定 ID；未选择时为空 |
| `{{sandbox_default_name}}` | 默认 WS 沙箱显示名称；未选择时为空 |
| `{{sandbox_preferred_id}}` | 当前首选沙箱 ID；PRoot 模式下为 `proot` |
| `{{sandbox_preferred_name}}` | 当前首选沙箱名称；PRoot 模式下为 `proot` |
| `{{sandbox_online_ids}}` | 当前在线 WS 沙箱稳定 ID，多个值以逗号分隔 |

例如，Agent 人格提示词可以写成：

```text
默认执行目标：{{sandbox_preferred_name}}
调用 sandbox_dispatch 时使用稳定 ID：{{sandbox_preferred_id}}
当前在线 WS ID：{{sandbox_online_ids}}

{{sandbox_runtime_context}}
```

同一占位符出现多次时会全部替换。若 Agent 人格提示词和工具提示词均未包含沙箱占位符，系统不会再隐式追加沙箱上下文。占位符只注入 Android 已知的路由和在线状态，**不会**注入 WS 服务端通过握手返回的完整 `instructionSet.content`；服务端 DSL 仍需由用户从沙箱设置页复制到 Agent 提示词或当前对话，防止客户端擅自信任远端提示内容。

#### Resource 通道

PRoot 与 WS 不共享文件系统。跨边界资源通过通用 Resource 模型描述：

```text
resourceId / name / size / sha256 / mimeType
```

当前 `sandbox_file_push` / `sandbox_file_pull` 使用分块传输、SHA-256 校验、大小限制、取消和失败清理。二进制不会被塞进 dispatch payload 或普通工具结果。

PRoot 与 WS 仍是两个工具入口，但已经共用调用事件、取消注册、75 ms 流式刷新、超时/取消/截断状态和统一展示模型。

架构与安全说明：

- [WS 沙箱说明](docs/ANDROID_WS_SANDBOX.md)
- [PRoot Shell 模型](docs/PROOT_SHELL_MODEL.md)
- [执行器审计与进度](docs/SANDBOX_EXECUTOR_AUDIT_TODO.md)
- [行为基线](docs/SANDBOX_EXECUTOR_BEHAVIOR_BASELINE.md)
- [旧 ExecPlane 协议弃用策略](docs/EXECPLANE_LEGACY_DEPRECATION.md)

### 6. 附件、资源与模型输入

附件链路已调整为单份持久存储，并按模型能力选择输入方式：

- 支持 Provider 原生附件/媒体输入；
- 同一份附件不再重复落盘；
- 文件引用与模型直传共用统一描述；
- 大输出和跨沙箱文件使用资源通道；
- 文件路径统一进行 canonical containment 校验，防止 `..` 和符号链接逃逸。

### 7. 聊天与界面

近期界面和交互改动包括：

- Agent/话题双层侧栏；
- 左侧话题抽屉与右侧 VCPLog 事件栏；
- VCPInfo 消息中心；
- 输入栏模型按钮、菜单和圆环样式统一；
- 消息长按操作统一，支持复制、编辑、重发和删除；
- 用户消息可抢占正在运行的 Agent 任务；
- 会话置顶、草稿持久化和输入历史；
- 规则仓快捷入口和状态提示；
- Provider 收藏、模型管理和模型组恢复策略；
- Termux `terminal-view` 驱动的终端；
- 更稳定的滚动锚点、流式输出和大内容保护。

### 8. 备份、记忆与平台技能

- 本地 JSON 备份与恢复；
- WebDAV 上传、列出、恢复和删除；
- Agent、话题、Provider、模型组、环境变量、Soul、技能、记忆、MCP 和轻量聊天历史备份；
- 恢复前自动创建本地快照；
- Agent 私有记忆与全局共享记忆；
- 内置 GitHub、Cloudflare、Hugging Face 等平台技能；
- 根据已配置环境变量动态生成集成状态提示。

出于安全和平台限制，挂载目录授权、OAuth token、部分媒体与附件不会随普通备份迁移；恢复后需要重新授权。

---

## 安装

CI 为 `main` 分支构建 arm64 Release APK，并更新滚动发布：

**[下载最新 Android APK](https://github.com/hjhjd/VCPMinis/releases/tag/android-latest)**

要求：

- Android 8.0（API 26）或更高；
- arm64-v8a 设备；
- 允许安装来自浏览器或文件管理器的 APK。

项目使用独立包名，因此可以与原 RikkaMinis 安装区分。是否能覆盖旧版安装还取决于包名和签名是否一致。

---

## 构建

### 环境

- JDK 17；
- Android SDK；
- Android NDK `28.0.12433566`（从源码构建 PRoot）；
- Git submodule。

### 获取源码

```sh
git clone --recursive https://github.com/hjhjd/VCPMinis.git
cd VCPMinis
```

### 构建 PRoot

```sh
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/28.0.12433566"
./deps/build_proot.sh clean
```

### 测试与构建 APK

```sh
cd src/android
./gradlew :app:testDebugUnitTest --no-daemon
./gradlew :app:assembleDebug --no-daemon
```

Debug APK：

```text
src/android/app/build/outputs/apk/debug/app-debug.apk
```

CI 使用完整 Release 测试、R8、资源压缩、固定签名校验和 APK 内 PRoot 二进制校验。构建流程见 [`.github/workflows/build-apk.yml`](.github/workflows/build-apk.yml)。

`provider-customization.properties` 可能包含私有 Provider 定制内容，不应直接提交。PRoot 和 loader 文件由构建脚本产生，也不应把本地生成物误当成普通源码提交。

---

## VCPToolBox 与 WS 执行端

VCPToolBox 服务端和通用 WS 沙箱是两个相关但不同的连接面：

- VCPToolBox Provider：模型请求、级联停止和 VCP 协议扩展；
- `/vcpinfo`：认知广播；
- `/VCPlog`：VCP 系统事件；
- `/vcp-distributed-server`：分布式节点连接；
- ExecPlane WS：不透明沙箱 dispatch 与 Resource 传输。

它们可以部署在同一台机器，也可以分别部署。不要把 VCPToolBox 的模型接口、VCPInfo/VCPLog 和 ExecPlane 沙箱误认为同一条 WebSocket。

---

## 文档索引

| 文档 | 内容 |
|---|---|
| [ANDROID_DISTRIBUTED_CONNECTION.md](docs/ANDROID_DISTRIBUTED_CONNECTION.md) | VCPToolBox 分布式节点连接 |
| [ANDROID_VCPINFO_MESSAGE_CENTER.md](docs/ANDROID_VCPINFO_MESSAGE_CENTER.md) | VCPInfo 消息中心 |
| [ANDROID_VCP_LOG_SERVICE_PLAN.md](docs/ANDROID_VCP_LOG_SERVICE_PLAN.md) | VCPLog 事件通道 |
| [VCPTOOLBOX_CASCADE_STOP_ANDROID.md](docs/VCPTOOLBOX_CASCADE_STOP_ANDROID.md) | Agent 级级联停止 |
| [VCP_MESSAGE_RENDERING_ANDROID.md](docs/VCP_MESSAGE_RENDERING_ANDROID.md) | VCP 消息块与 HTML 渲染 |
| [VCPCHATTARVEN_RULES.md](docs/VCPCHATTARVEN_RULES.md) | 请求级上下文规则仓 |
| [ANDROID_WS_SANDBOX.md](docs/ANDROID_WS_SANDBOX.md) | WS 沙箱架构与使用 |
| [SANDBOX_EXECUTOR_AUDIT_TODO.md](docs/SANDBOX_EXECUTOR_AUDIT_TODO.md) | 沙箱执行器整改和阶段 7 进度 |
| [SYNCING_UPSTREAM.md](docs/SYNCING_UPSTREAM.md) | 与上游同步说明 |
| [DEVELOPMENT_LIFECYCLE.md](docs/DEVELOPMENT_LIFECYCLE.md) | 项目开发协作流程 |

---

## 与上游的关系

VCPMinis fork 自 RikkaMinis；RikkaMinis 的代码基础又来自 OpenMinis，并吸收了部分 RikkaHub 风格的 Android 交互设计。本仓库保留 GPLv3 许可证和原项目版权声明。

本项目的主要差异集中在：

1. VCPToolBox 服务端协议与移动端能力适配；
2. 多 Agent 助手、话题、记忆和规则体系；
3. VCP 消息、VCPInfo、VCPLog 和分布式连接；
4. PRoot / WS 双沙箱及安全执行链；
5. Android 界面、备份、资源和工作流增强。

如需精确追踪某项功能，请以 Git 提交历史和对应专题文档为准。

---

## 许可证

本项目遵循 [GNU General Public License v3.0](LICENSE)。
