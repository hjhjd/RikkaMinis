# VCPChatTarven 规则仓

VCPChatTarven 是 VCPMinis 的请求级上下文注入规则系统。它允许用户声明：**向模型请求注入什么内容、注入到哪里、对哪些 Agent 生效**。

设计灵感来自 SillyTavern Lorebook / World Info 和 VCPChatTarven，但实现完全基于 VCPMinis 当前的 Kotlin、Room、Jetpack Compose 与 Provider 抽象。

## 核心原则

- **请求级注入**：规则只作用于发给模型的请求副本。
- **零历史污染**：不会把规则文本写入用户消息、`agentHistory` 或 Room 消息记录。
- **即时生效**：启用、禁用或编辑后，从下一次模型请求开始生效。
- **Agent 隔离**：规则可全局生效，也可仅绑定某个 Agent。
- **真实预览**：编辑页面预览与实际请求共用 `TarvenInjectionEngine`。
- **工具链安全**：上下文插入会避开 `tool_use` 与 `tool_result` 配对之间的位置。

## 使用入口

### Agent 设置

进入：

```text
Agent 设置 → 能力与数据 → 规则仓
```

可以创建、编辑、排序、启用、禁用和删除规则。

### 聊天快捷入口

进入：

```text
聊天输入栏 → + → 规则仓
```

Bottom Sheet 展示当前 Agent 可用的全局规则和 Agent 专属规则，可快速开关规则或进入完整管理页。

当存在生效规则时，输入栏 `+` 按钮会显示绿色状态点。

## 三种规则类型

### 1. 系统提示词注入

内部类型：

```text
system_suffix
```

将规则内容插入最终系统提示词的前面或后面。

```text
[系统前置规则]

[Agent 人格]
[工具提示词]
[Skills / MCP / Memory / Runtime]

[系统后置规则]
```

适合：

- 长期行为约束
- 特定 Agent 的背景设定
- 输出规范
- 运行环境说明

配置字段：

- `position = prepend`：前置
- `position = append`：后置

### 2. 用户消息注入

内部类型：

```text
user_suffix
```

将规则内容插入最新一条**真实用户消息**的前面或后面。

例如用户输入：

```text
帮我检查这段代码
```

后置规则内容：

```text
先指出问题，再给出完整修复代码。
```

模型实际收到：

```text
帮我检查这段代码

先指出问题，再给出完整修复代码。
```

聊天界面和数据库仍只保存：

```text
帮我检查这段代码
```

纯 `ToolResult` 消息不会被误判为真实用户消息。

### 3. 上下文消息注入

内部类型：

```text
context_inject
```

在模型请求的消息历史副本中插入一条虚拟 `user` 或 `assistant` 消息。

字段：

- `role = user | assistant`
- `depth = 0..20`

深度语义：

- `depth = 0`：插入到消息历史末尾
- `depth = N`：从末尾向前定位插入位置

多条规则按深度从大到小处理，降低数组插入导致的位置偏移。

如果插入位置落在：

```text
assistant(tool_use)
user(tool_result)
```

之间，引擎会移动到安全边界，避免破坏 Provider 的工具调用协议。

## 作用范围

当前支持：

| Scope | 含义 |
|---|---|
| `global` | 对所有 Agent 生效 |
| `agent` | 仅对绑定的 Agent ID 生效 |

VCPMinis 当前没有群聊实体，因此第一版不提供无实际作用的 `group` scope。后续加入群聊后可扩展该枚举。

## XML 包裹

开启“XML 包裹”后，规则内容会变为：

```xml
<minis_injection description="由 VCPMinis 规则系统注入">
规则内容
</minis_injection>
```

用途：

- 明确区分用户原始内容与外部注入内容
- 帮助 Claude 等模型识别结构化边界
- 降低规则内容与正常上下文混淆的概率

关闭后直接注入纯文本。

## 占位符

规则内容支持以下白名单占位符：

| 占位符 | 内容 |
|---|---|
| `{{agent_name}}` | 当前 Agent 名称 |
| `{{AgentName}}` | 当前 Agent 名称，兼容旧规则写法 |
| `{{session_id}}` | 当前会话 ID |
| `{{current_date}}` | 当前日期 |
| `{{device_language}}` | Android 设备语言 |
| `{{runtime_context}}` | 简化运行时上下文 |
| `{{sandbox_runtime_context}}` | 当前沙箱模式、首选沙箱与路由规则 |

示例：

```text
你正在为 {{agent_name}} 的会话工作。
当前日期：{{current_date}}

{{sandbox_runtime_context}}
```

占位符只做白名单字符串替换，不执行脚本或任意表达式。

## 排序规则

规则按类型内的 `sortOrder` 升序执行：

- 系统规则只与系统规则排序
- 用户规则只与用户规则排序
- 上下文规则先按 `depth` 从大到小，再按 `sortOrder` 排序

管理页中的上移和下移只调整当前类型内的顺序。

## 请求流水线

每次模型请求前执行：

```text
buildSystemPrompt()
       │
       ▼
effectiveAgentHistory()
       │
       ▼
TarvenInjectionEngine.apply()
  ├─ 系统提示词前置/后置
  ├─ 最新真实用户消息前置/后置
  └─ 上下文虚拟消息插入
       │
       ▼
applyRequestImageBudget()
       │
       ▼
Provider.streamMessage()
```

Tarven 规则应用于每次 Agent Loop 请求，包括工具调用完成后的后续模型轮次。

## 数据库

Room 表：

```sql
tarven_rules
```

主要字段：

```text
id
name
rule_type
is_enabled
content
scope
agent_id
wrap
role
depth
position
sort_order
created_at
updated_at
```

数据库迁移：

```text
AppDatabase 12 → 13
MIGRATION_12_13
```

索引：

```text
(rule_type, is_enabled, sort_order)
(agent_id)
```

## 备份与恢复

规则包含在 VCPMinis 配置备份中：

```json
{
  "tarvenRules": [
    {
      "id": "rule_...",
      "name": "代码审查格式",
      "ruleType": "user_suffix",
      "isEnabled": 1,
      "content": "先指出问题，再给出修复代码。",
      "scope": "agent",
      "agentId": "...",
      "wrap": 1,
      "position": "append",
      "sortOrder": 0
    }
  ]
}
```

支持：

- 本地 JSON 备份
- WebDAV 备份
- 恢复前本地快照
- Agent ID 冲突时的绑定重映射

## 代码结构

```text
agent/
  TarvenInjectionEngine.kt       请求级注入引擎

data/db/
  TarvenRuleEntity.kt            Room Entity 与类型常量
  TarvenRuleDao.kt               查询、CRUD、开关和排序
  AppDatabase.kt                 表注册与 12→13 迁移

data/repository/
  TarvenRuleRepository.kt        规则业务仓库

ui/agents/
  TarvenRulesScreen.kt           管理、编辑与实时预览

ui/chat/
  TarvenSelectorSheet.kt         聊天快捷选择器
  ChatScreen.kt                  + 菜单入口和生效指示点
  ChatViewModel.kt               请求前规则流水线接入

backup/
  ConfigBackup.kt                规则备份与恢复
```

## 示例规则

### Markdown 输出约束

```text
名称：Markdown 输出
类型：用户消息注入
位置：后置
范围：全局
XML 包裹：关闭
内容：请使用结构清晰的 Markdown 回复。
```

### Agent 专属背景

```text
名称：Nova 身份补充
类型：系统提示词注入
位置：前置
范围：当前 Agent
XML 包裹：开启
内容：你正在作为 {{agent_name}} 工作，优先直接执行任务。
```

### 上下文末尾提醒

```text
名称：最终核对
类型：上下文消息注入
角色：user
深度：0
范围：当前 Agent
XML 包裹：开启
内容：回答前核对是否满足用户最新消息中的全部要求。
```

## 第一版边界

当前不包含：

- 关键词触发
- 正则条件
- 脚本规则
- 群聊 scope
- 云端规则市场
- 与其他项目共享数据库

这些能力可以在不改变现有三类注入语义的前提下后续扩展。

## 验证

核心引擎测试位于：

```text
src/android/app/src/test/java/com/openminis/app/agent/TarvenInjectionEngineTest.kt
```

覆盖：

- 系统前置/后置顺序
- 用户消息零历史污染
- 上下文深度 0 插入
- 禁用规则不生效

构建验证命令：

```sh
cd src/android
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleRelease
```
