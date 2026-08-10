# Android 多 Agent 与侧栏 UI 重构 TODO

> 工作仓库：`/home/nova/workspace/RikkaMinis`
> Android 工程：`src/android`
> 主要源码：`src/android/app/src/main/java/com/openminis/app`
> 计划日期：2026-08-11
> 当前阶段：设计与实施计划；本文件不包含业务代码改动。

## 1. 目标

将当前“单一全局 Agent 配置 + 会话列表侧栏”重构为“Agent 是一级实体、话题（现有 session）绑定 Agent”的结构：

- 支持创建、编辑、删除、排序和切换多个 Agent。
- 每个 Agent 可独立设置头像、名称、默认模型、技能、人格/系统提示词、记忆。
- 将“人格”和“系统提示词”合并为一个用户可理解的 Agent 指令编辑区。
- 左侧抽屉参考提供的 UI 思路，加入“助手 / 话题”双页签、搜索、Agent 卡片、话题卡片和底部主操作。
- Agent 行右滑后露出设置入口；点击设置进入该 Agent 的独立配置页。
- 从全局设置页移除“技能、人格/系统提示词、记忆”三个 Agent 专属入口；全局设置只保留真正跨 Agent 的能力。
- 保留并迁移现有用户数据，升级后不丢失 SOUL.md、自定义系统提示词、技能启用状态、记忆和历史会话。

## 2. 已有架构审计结论

### 2.1 当前可复用资产

- [x] UI 使用 Jetpack Compose + Material 3，已有 `ModalNavigationDrawer` 和 `ChatHistoryDrawer`。
- [x] 会话使用 Room：`ChatSessionEntity`、`ChatDao`、`ChatRepository`。
- [x] 技能已有全局资源库和会话覆盖：`SkillRepository`、`session_skill_overrides`、`SessionSkillsSheet`。
- [x] 记忆已有完整读写与注入链：`MemoryRepository`、`MemoryGlobalPrefs`、`SessionMemorySheet`。
- [x] 人格已有 SOUL.md 解析、限制校验和注入：`SoulStore`、`SystemPromptBuilder`、`SoulSettingsScreen`。
- [x] 当前分支已新增系统提示词资源和编辑入口：`SystemPromptPreferences`、`SystemPromptSettingsScreen`。
- [x] 模型已有 `modelBinding` 和模型组体系，可复用于 Agent 默认模型选择。
- [x] 已依赖 Coil，可直接显示本地头像文件。
- [x] 已有 Photo Picker 使用范例，可复用头像选择流程。
- [x] `ConfigBackup` 已覆盖设置、SOUL、技能、记忆与会话，可扩展为 Agent-aware 备份。

### 2.2 当前核心限制

- [ ] `SoulStore` 固定读写唯一文件：`filesDir/minis-global/memory/SOUL.md`。
- [ ] `SystemPromptPreferences` 使用唯一 SharedPreferences，当前仍是全局配置。
- [ ] `MemoryRepository` 在 `MinisApp` 中只绑定唯一目录：`filesDir/minis-global/memory`。
- [ ] 技能默认启用状态是全局的，仅额外支持 session 覆盖，没有 Agent 配置层。
- [ ] `ChatSessionEntity` 没有 `agent_id`，历史话题无法归属 Agent。
- [ ] `ChatViewModel.buildSystemPrompt()` 只能装配全局人格、全局记忆和 session 技能。
- [ ] 当前抽屉只有会话历史，没有 Agent 列表、Agent 搜索和页签。

**结论：不能只改 UI。必须先引入稳定的 `agentId` 配置域，并让 session 持久绑定 Agent；否则头像虽然能切换，技能、提示词和记忆仍会串用。**

## 3. 产品与数据语义（实施前固定）

### 3.1 一级对象

- **Agent**：身份与配置容器，拥有名称、头像、默认模型、指令、技能集合和独立记忆空间。
- **Topic / Session**：一次对话，必须绑定一个 Agent；绑定后默认不随当前选中 Agent 漂移。
- **Skill**：仍是全局安装的资源包，但“是否给某 Agent 使用”由 Agent 绑定决定。
- **Memory**：文件内容按 Agent 物理隔离；session 的记忆开关只决定是否为该 session 注入其绑定 Agent 的记忆。

### 3.2 推荐优先级规则

- 模型：session 显式模型绑定 > Agent 默认模型 > App 全局默认模型。
- 技能：session 临时覆盖 > Agent 技能绑定 > 默认关闭；技能被卸载时所有绑定自然失效。
- 提示词：App 内置安全/工具运行时提示词 + Agent“人格与提示词” + 技能/MCP + Agent 记忆 + 动态运行时上下文。
- 记忆：只读取 session 所绑定 Agent 的目录；严禁回退到另一个 Agent 的目录。
- 新建话题：绑定当前选中的 Agent，并快照该 Agent 的默认模型。
- 老话题：升级时统一绑定迁移生成的默认 Agent。

### 3.3 人格与系统提示词合并原则

UI 合并，但底层仍需区分信任边界：

- 用户只看到一个“人格与提示词”编辑区，保存为 Agent 的 `instructions`。
- App 的工具协议、安全规则、文件路径、运行时能力说明仍由内置平台模板维护，不允许 Agent 文本覆盖。
- 当前 SOUL.md 的 `name/style/lang/body` 应迁移并渲染进默认 Agent 配置；Agent 名称改由 Agent 实体管理。
- 当前 `SystemPromptPreferences` 的覆盖值必须保留。迁移前先定义其归属：推荐把“身份/人格类覆盖”合并到默认 Agent 指令，把“平台主模板覆盖”保留为隐藏的兼容配置，直到确认无用户数据依赖后再下线。
- 不应直接删除当前暂存区内的 `SystemPromptPreferences` 与中文默认模板改动；先接入迁移，再清理旧入口。

## 4. 建议数据模型

### 4.1 Room：Agent 主表

- [x] 新增 `AgentEntity`（建议表名 `agents`）：
  - `id: String`（UUID，稳定主键）
  - `name: String`
  - `avatar_path: String?`（仅保存 App 内部相对路径）
  - `instructions: String`
  - `preferred_language: String?`（可选；若决定完全并入 instructions 可不暴露 UI）
  - `default_model_binding: String?`（复用现有 binding JSON 语义）
  - `created_at: Long`
  - `updated_at: Long`
  - `sort_order: Int`
  - `is_default: Int`
  - `is_archived: Int`（可选，便于软删除/恢复）
- [x] 新增 `AgentDao`：observe/list/get/insert/update/reorder/archive/delete/setDefault。
- [x] 新增 `AgentRepository`，作为 Agent 配置唯一读写入口。
- [x] `MinisApp` 创建 application-scoped `AgentRepository`，避免 Composable 自建多个实例。

### 4.2 Session 绑定

- [x] `ChatSessionEntity` 新增非空 `agent_id`。
- [x] Room 数据库版本从 10 升级，增加明确迁移：
  1. 创建默认 Agent；
  2. 给所有旧 session 回填默认 Agent id；
  3. 为 `sessions.agent_id` 建索引；
  4. 迁移完成后保证所有 session 都能解析 Agent。
- [x] `ChatRepository.createSession()` 增加 `agentId` 参数，并为旧调用保留默认 Agent 兼容值。
- [ ] 所有会话创建入口补齐 Agent：普通新聊天、分享入口、快捷方式、HeadlessChatRunner、fork、备份恢复。
- [x] fork 默认继承源 session 的 Agent。
- [ ] 话题移动到其他 Agent 作为后续能力；首版至少提供删除 Agent 时的“重新分配话题”策略。

### 4.3 Agent 技能绑定

- [x] 将 `skills.db` 版本升级，新增 `agent_skill_bindings(agent_id, skill_id, is_enabled)`，联合主键。
- [x] `SkillRepository` 增加：
  - `isEnabledForAgent(skillId, agentId)`
  - `setAgentBinding(agentId, skillId, enabled)`
  - `clearAgentBindings(agentId)`
  - `skillPromptFragment(agentId, sessionId)`
- [x] 保留 `session_skill_overrides`，作为临时会话级最高优先级覆盖。
- [x] 默认 Agent 的技能绑定从当前全局 `is_enabled` 状态迁移。
- [x] 新 Agent 默认不继承其他 Agent 技能；创建页后续可提供“复制自现有 Agent”。
- [x] 删除技能时同步清理 Agent 和 session 绑定。

### 4.4 Agent 独立记忆

- [x] 推荐目录已采用：`filesDir/minis-agents/<agentId>/memory/`。
- [x] 每个 Agent 目录支持独立 `GLOBAL.md` 与 `YYYY-MM-DD.md`；SOUL 兼容仍由默认 Agent 旧入口维护。
- [x] 新增按 `agentId` 获取仓库的 `AgentMemoryRepositoryFactory`；App 级旧仓库仅作为默认 Agent 兼容别名。
- [x] memory tools、prompt 注入和会话记忆面板均从当前 session 的 `agentId` 解析仓库。
- [x] shell 中 `/var/minis/memory/` 已按 PersistentShell 所属 session 绑定 Agent 目录；session-aware 文件工具也使用同一映射，并发 Agent 不共享最后写入者状态。
- [x] 将现有 `minis-global/memory` 以“不覆盖目标文件”的复制方式迁入默认 Agent；源目录保留且迁移幂等。
- [ ] `MemoryGlobalPrefs` 重新定义为“新 Agent / 新 session 默认开关”，已有 session 的 `memory_enabled` 继续有效。

### 4.5 头像存储

- [x] 使用系统 Photo Picker 选择图片。
- [x] 不长期保存外部 content URI；立即复制到 App 私有目录：`filesDir/minis-agents/<agentId>/avatar.webp`。
- [x] 读取 EXIF 方向，居中裁剪为正方形，压缩并限制为最长边 512px WebP。
- [x] UI 使用 Coil `AsyncImage`，加载失败显示默认 Agent 图标。
- [x] 更换头像采用临时文件 + 原子替换，避免半写文件。
- [ ] 删除/归档 Agent 时按产品策略清理头像；只要话题仍引用 Agent 就不能提前删除资源。

## 5. 分阶段实施计划

### 阶段 0：冻结边界与补基线测试

- [ ] 为当前暂存区改动创建独立提交或至少保留 patch，避免多 Agent 重构覆盖中文提示词工作。
- [ ] 记录当前 Room v10、skills.db v3 和旧目录结构测试夹具。
- [ ] 为 `SoulMDParser`、`SystemPromptPreferences` 模板替换、技能覆盖优先级补单测。
- [ ] 增加升级前数据快照测试：SOUL.md、自定义 prompt、GLOBAL.md、daily memory、技能开关、旧 session。
- [ ] 明确首版是否包含 Group；建议先做 Agent + Topic，Group 放到阶段 8，避免主链路过度扩张。

**验收：** 在不改产品行为的情况下，关键旧数据能被测试夹具稳定构造和读取。

### 阶段 1：Agent 数据层与无损迁移

- [x] 新增 `AgentEntity/AgentDao/AgentRepository`。
- [x] Room migration 创建默认 Agent 并回填 session.agent_id。
- [ ] 将旧 SOUL、提示词覆盖、默认模型和技能状态迁入默认 Agent。（SOUL 与技能已接入；提示词覆盖、默认模型待接）
- [ ] 将旧记忆目录迁入默认 Agent；采用“复制 → 校验 → 切换标记 → 延迟清理”而非直接移动。
- [x] 添加迁移版本标记，保证重复启动幂等。
- [x] 对 SOUL 迁移异常提供 fallback：默认 Agent 仍存在，旧文件不删除。
- [ ] 扩展 ConfigBackup 格式；读取旧格式时自动导入为默认 Agent。

**验收：** 老用户升级后看到一个默认 Agent；旧话题、人格、提示词、技能、记忆全部可用，且冷启动重复执行不会重复创建 Agent。

### 阶段 2：运行时 Agent 化

- [x] `ChatViewModel` 加载 session 时恢复绑定 Agent 实体；缺失 Agent 回退默认 Agent。
- [x] 草稿路由携带稳定 `agentId`；草稿提升为真实 session 时保留绑定。
- [x] `buildSystemPrompt()` 已按 Agent 渲染身份与 instructions；完整装配顺序随独立记忆接入后最终固定。
- [ ] 按“平台模板 → Agent 指令 → 技能/MCP → Agent 记忆 → runtime”固定装配顺序。
- [x] 技能 fragment 改用 agent + session 两层解析。
- [x] memory tools、GLOBAL.md、daily logs、会话记忆 UI、shell 与 session-aware 文件工具均使用 Agent memory repository。
- [ ] Agent 默认模型只用于新话题；已有 session 的模型不随 Agent 配置修改而强制改变。
- [ ] 聊天气泡头部和通知展示 Agent 名称/头像；缺失 Agent 时回退默认 Agent并记录告警。
- [ ] 检查 `ChatViewModelStore` 缓存键：session 已唯一，可保留，但重载 Agent 配置后需使 prompt 缓存失效。

**验收：** 两个 Agent 使用不同指令、技能和记忆连续对话，互相看不到对方记忆；杀进程重进后绑定不变。

### 阶段 3：侧栏信息架构重构

#### 3.1 抽屉框架

- [ ] 将 `ChatHistoryDrawer` 拆为可维护组件，例如：
  - `AgentTopicDrawer`
  - `DrawerSegmentedTabs`
  - `AgentListPane`
  - `TopicListPane`
  - `AgentRow`
  - `TopicRow`
  - `DrawerBottomActions`
- [x] 顶部采用“助手 / 话题”分段控件；状态在抽屉组合期间保持。
- [x] 搜索框随页签改变 placeholder，并支持 Agent 名称过滤。
- [x] 维持现有抽屉关闭后再导航的处理，避免重新引入抽屉卡住问题。
- [ ] 保留系统 Back、预测返回、IME 隐藏、TalkBack 和横竖屏行为。

#### 3.2 助手页

- [x] Agent 卡片展示头像、名称、默认模型状态。
- [x] 当前会话绑定 Agent 使用容器高亮。
- [x] 点击 Agent：已有话题打开最近话题，无话题创建绑定该 Agent 的新草稿。
- [x] 右滑 Agent 行露出设置按钮。
- [x] 滑动使用互斥 reveal state：同一时间只允许一行展开。
- [x] 设置按钮触控区域为 48dp，并提供无障碍描述。
- [x] 底部提供“创建 Agent”；未展示未实现的 Group。

#### 3.3 话题页

- [ ] 复用现有 session Flow、消息数可见性、置顶、删除、草稿和日期分组逻辑。
- [x] 话题卡片增加 Agent 头像/名称标识。
- [x] 搜索同时匹配标题、最后消息及 Agent 名称。
- [ ] “新建话题”绑定当前 Agent。
- [x] 保留长按删除、置顶和当前话题高亮。
- [ ] 页签和搜索状态用 `rememberSaveable`，旋转屏幕不丢失。

#### 3.4 底部入口

- [ ] 全局设置继续保留在抽屉底部，但与 Agent 设置视觉区分。
- [ ] 现有可配置 footer actions 不应直接消失：决定迁移到“更多”菜单、话题页工具条或全局设置。
- [ ] 避免同时保留两套重复的设置入口。

**验收：** 可在抽屉中搜索、选择和右滑配置 Agent，可切换到对应话题；旧的置顶、删除、草稿、footer action 不回归。

### 阶段 4：Agent 创建与配置页

- [x] 新增路由：
  - `agents/new`
  - `agents/{agentId}`
  - `agents/{agentId}/skills`
  - `agents/{agentId}/memory`
  - `agents/{agentId}/memory/{file}`
- [x] Agent 配置页头部支持可点击头像与 Agent 名称；默认模型选择待补。
- [x] “人格与提示词”使用单一多行编辑器并明确平台安全规则不受覆盖。
- [ ] 模型参数区首版复用现有模型/模型组选择；温度等高级参数只有底层真正支持且能持久化时才展示。
- [x] 技能区复用 `SkillRowItem`，读写 Agent binding。
- [x] 记忆区复用 `MemoryManagementScreen` 和文件编辑页，仓库绑定 agentId。
- [ ] 支持创建时“从某 Agent 复制配置”，默认只复制提示词、模型和技能，不复制记忆；复制记忆必须二次确认。
- [ ] 保存失败留在当前页并显示字段级错误，不静默丢数据。
- [ ] 删除 Agent 使用危险操作样式，并明确显示其话题数量和处理方式。

#### 删除策略（首版建议）

- 默认 Agent 不可删除，除非先指定另一个默认 Agent。
- 有历史话题的 Agent 不做无提示级联删除。
- 提供“将话题迁移到另一 Agent 后删除”；归档可作为更安全的默认选项。
- 记忆与头像在确认不再被引用后再清理。

**验收：** 新建 Agent 后可以立即设置头像、模型、指令、技能与记忆；重新启动 App 后完整恢复。

### 阶段 5：全局设置瘦身与旧入口退场

- [x] 从 `SettingsScreen` 的 Agent Runtime 区移除视觉入口：
  - 技能
  - 人格
  - 系统提示词
  - 记忆
- [x] “人格 + 系统提示词”在 Agent 配置中合并为一个入口，不再在全局设置并列展示。
- [x] 全局设置保留 Provider、模型组、MCP、环境变量、沙箱、权限、外观、备份等跨 Agent 能力。
- [ ] 技能的“安装/导入/更新”入口并入 Agent 技能页中的“技能库”；安装资源仍是全局的，启用关系是 Agent 独立的。
- [ ] 旧路由 `SOUL/SYSTEM_PROMPT/MEMORY/SKILLS` 先保留一版兼容重定向到默认/当前 Agent，避免 deep link 和旧消息链接失效。
- [ ] 更新 prompt 内的设置 deep link，例如记忆关闭提示应跳到当前 Agent 的记忆页。
- [ ] 下一主版本确认无旧链接依赖后，再删除旧 Composable/字符串资源。

**验收：** 全局设置中不再出现四个重复页面；旧 deep link 不崩溃，能跳到合理的 Agent 页面。

### 阶段 6：备份、同步、CLI 与配置协议

- [ ] `ConfigBackup` 增加 agents、agent-skill bindings、agent memory、avatar 元数据、session.agent_id。
- [ ] 头像可选择写入备份（base64/zip），设置单文件和总大小上限；超限时回退默认头像并给出 skipped 原因。
- [ ] 导入时 remap Agent UUID，并同步 remap session.agent_id 和技能绑定。
- [ ] WebDAV 使用同一备份格式完成 round-trip。
- [ ] `minis-config` 增加 Agent scope，例如 `agents list/get/create/update`；写操作继续走确认门。
- [ ] `minis-sessions-cli send` 支持可选 agent id/name；不提供时使用默认 Agent。
- [ ] 旧 soul 配置 path 保留兼容映射到默认 Agent一版。
- [ ] 日志和审计记录 Agent id，但不得输出完整私密提示词或记忆正文。

**验收：** 导出 → 清数据 → 导入后，Agent 数量、头像、配置、技能、记忆和话题归属一致；旧格式备份仍可导入。

### 阶段 7：测试、性能与可访问性

### 7.1 单元/迁移测试

- [ ] Room 10 → 新版本迁移测试，验证默认 Agent 和 session 回填。
- [ ] skills.db 3 → 新版本迁移测试。
- [ ] 旧 SOUL/SystemPrompt/Memory 数据迁移幂等测试。
- [ ] prompt 装配顺序与 Agent 隔离测试。
- [ ] 技能优先级测试：session > agent > default。
- [ ] 删除/归档/重新分配 Agent 测试。
- [ ] 备份 UUID remap 与旧格式导入测试。

### 7.2 Compose/UI 测试

- [ ] 助手/话题页签切换与状态恢复。
- [ ] Agent 搜索、Topic 搜索和空状态。
- [ ] 右滑仅展开一行；反向滑动、点击外部和滚动可关闭。
- [ ] 当前 Agent/Topic 选中态。
- [ ] 头像选择、裁剪失败、文件丢失 fallback。
- [ ] 删除确认与默认 Agent 保护。
- [ ] TalkBack 文案、焦点顺序、48dp 触控区、字体放大 1.3x/1.5x。

### 7.3 性能与稳定性

- [ ] 100 Agent / 1000 Topic 列表滚动基准，列表使用稳定 key。
- [ ] 头像生成缩略图，避免每行解码原图。
- [ ] Agent 切换不在主线程读取大 memory 文件。
- [ ] prompt/skills/memory 配置变化使用精确失效，不全局重建所有 ChatViewModel。
- [ ] 进程死亡、旋转屏幕、后台恢复、升级中断覆盖仪器测试。

### 阶段 8（后续）：Agent Group

> 参考图出现了 Group，但当前需求主链是多 Agent。Group 不应阻塞核心交付。

- [ ] 先定义 Group 是“群聊参与者集合”还是“配置模板/文件夹”，禁止只按参考图做一个空壳按钮。
- [ ] 若为群聊：新增 group、group_members、发言路由、共享/隔离记忆、费用与并发策略。
- [ ] 若仅为组织：先实现 Agent 分组和筛选，不改变聊天运行时。
- [ ] 产品语义确定前隐藏“创建 Group”。

## 6. 建议代码拆分

```text
com/openminis/app/
├── agent/
│   ├── AgentRepository.kt
│   ├── AgentPromptAssembler.kt
│   ├── AgentAvatarStore.kt
│   └── AgentMigration.kt
├── data/db/
│   ├── AgentEntity.kt
│   └── AgentDao.kt
├── data/repository/
│   ├── AgentMemoryRepositoryFactory.kt
│   └── SkillRepository.kt              # 增加 Agent binding
├── ui/agents/
│   ├── AgentEditScreen.kt
│   ├── AgentCreateScreen.kt
│   ├── AgentSkillsScreen.kt
│   ├── AgentMemoryScreen.kt
│   └── AgentAvatarPicker.kt
└── ui/chat/drawer/
    ├── AgentTopicDrawer.kt
    ├── AgentListPane.kt
    ├── TopicListPane.kt
    └── SwipeRevealAgentRow.kt
```

命名可按现有工程风格调整；关键是避免继续把 Agent 逻辑堆进已经很大的 `ChatViewModel.kt` 和 `ChatScreen.kt`。

## 7. 风险清单与防护

- **数据串线（最高风险）**：任何 memory/skill/prompt 读取都必须显式携带 agentId；禁止使用“当前全局 Agent”作为老 session 的隐式来源。
- **迁移丢数据**：旧目录先复制校验，至少跨一个稳定版本后再清理；失败时不删除源文件。
- **暂存改动冲突**：当前系统提示词中文化和设置页改动已在 Git 暂存区，重构必须基于它迁移，不能 checkout/reset 覆盖。
- **两套数据库一致性**：Agent 主表在 Room，技能绑定在 skills.db；删除 Agent 需有补偿清理，不能假设跨库事务。
- **头像 URI 失效**：必须复制到私有目录，不依赖 Photo Picker 临时授权。
- **旧 deep link 失效**：旧设置路由至少兼容重定向一个版本。
- **会话行为漂移**：修改 Agent 默认模型/提示词不能偷偷重绑历史 session；session 的 Agent 归属必须持久化。
- **抽屉手势冲突**：右滑行与抽屉整体开合、系统返回手势可能竞争；需限制触发阈值并做真机测试。
- **隐私泄漏**：不同 Agent 的 memory、备份和日志必须隔离；错误日志不得打印正文。

## 8. 推荐提交序列

1. `docs: add multi-agent architecture and UI todo`
2. `test: add legacy agent-data migration fixtures`
3. `data: add agent entity repository and room migration`
4. `data: add agent skill bindings and memory scopes`
5. `runtime: bind sessions and prompt assembly to agents`
6. `ui: add agent/topic drawer and swipe actions`
7. `ui: add agent create edit avatar skills memory screens`
8. `settings: redirect legacy agent settings and remove duplicates`
9. `backup: add agent-aware export import and cli support`
10. `test: add multi-agent isolation migration and UI coverage`

每个提交保持可编译、可迁移、可回滚；不要把数据库迁移、运行时改造和整套 UI 压进一个超大提交。

## 9. MVP 完成定义

只有同时满足以下条件，才算“多 Agent”完成，而不是仅完成 UI：

- [ ] 至少可创建两个 Agent，并分别设置名称、头像、默认模型和指令。
- [ ] 每个 Agent 有独立技能绑定和独立记忆目录。
- [ ] 每个 topic 持久绑定 Agent，重启后不变化。
- [ ] Agent A 无法读取或注入 Agent B 的记忆。
- [ ] 左侧抽屉可在助手/话题间切换、搜索，并右滑 Agent 进入设置。
- [ ] 新话题使用当前 Agent；旧话题仍使用其原 Agent。
- [ ] 旧用户数据自动进入默认 Agent且无损。
- [ ] 全局设置不再重复展示技能、人格/系统提示词、记忆。
- [ ] 备份恢复能保留 Agent 配置和话题归属。
- [ ] 迁移、隔离和关键手势均有自动测试或明确的真机验收记录。
