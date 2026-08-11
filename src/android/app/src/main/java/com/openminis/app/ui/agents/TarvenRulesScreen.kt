package com.openminis.app.ui.agents

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.agent.TarvenInjectionEngine
import com.openminis.app.data.db.*
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.repository.TarvenRuleRepository
import com.openminis.app.ui.settings.SettingsScaffold
import kotlinx.coroutines.launch

private val SystemBlue = Color(0xFF4F7DFF)
private val UserGreen = Color(0xFF16A779)
private val ContextOrange = Color(0xFFF28C38)

@Composable
fun TarvenRulesScreen(agentId: String, repository: TarvenRuleRepository, onBack: () -> Unit) {
    val all by repository.observeAll().collectAsState(initial = emptyList())
    val rules = all.filter { it.scope == TarvenScope.GLOBAL || it.agentId == agentId }
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<TarvenRuleEntity?>(null) }
    var deleting by remember { mutableStateOf<TarvenRuleEntity?>(null) }

    if (editing != null) {
        RuleEditorScreen(
            initial = editing!!,
            agentId = agentId,
            onBack = { editing = null },
            onSave = { rule -> scope.launch { repository.save(rule); editing = null } },
        )
    } else {
        SettingsScaffold(title = "VCPChatTarven 规则仓", onBack = onBack) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text("CONTEXT INJECTION", color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                Text("管理发送给模型前的上下文注入规则", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 18.dp))
                TarvenRuleType.ALL.forEach { type ->
                    RuleSection(
                        type = type,
                        items = rules.filter { it.ruleType == type }.sortedBy { it.sortOrder },
                        onToggle = { rule -> scope.launch { repository.setEnabled(rule.id, rule.isEnabled == 0) } },
                        onEdit = { editing = it },
                        onDelete = { deleting = it },
                        onMove = { items, index, delta ->
                            val target = index + delta
                            if (target in items.indices) {
                                val ids = items.map { it.id }.toMutableList().also { java.util.Collections.swap(it, index, target) }
                                scope.launch { repository.reorder(type, ids) }
                            }
                        },
                    )
                    Spacer(Modifier.height(18.dp))
                }
                Button(
                    onClick = { editing = newRule(rules.size, agentId) },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.width(8.dp))
                    Text("创建自定义注入规则", fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(28.dp))
            }
        }
    }

    deleting?.let { rule ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            icon = { Icon(Icons.Default.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("删除这条规则？") },
            text = { Text("“${rule.name}”将被永久删除，此操作无法撤销。") },
            dismissButton = { TextButton({ deleting = null }) { Text("取消") } },
            confirmButton = { Button({ scope.launch { repository.delete(rule.id); deleting = null } }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("删除") } },
        )
    }
}

@Composable
private fun RuleSection(
    type: String,
    items: List<TarvenRuleEntity>,
    onToggle: (TarvenRuleEntity) -> Unit,
    onEdit: (TarvenRuleEntity) -> Unit,
    onDelete: (TarvenRuleEntity) -> Unit,
    onMove: (List<TarvenRuleEntity>, Int, Int) -> Unit,
) {
    var expanded by rememberSaveable(type) { mutableStateOf(true) }
    val color = typeColor(type)
    Row(
        Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(34.dp).background(color.copy(alpha = .12f), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            Icon(typeIcon(type), null, tint = color, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(typeLabel(type), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Text(typeDescription(type), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Badge(containerColor = color.copy(alpha = .14f), contentColor = color) { Text(items.size.toString(), modifier = Modifier.padding(horizontal = 4.dp)) }
        Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    AnimatedVisibility(expanded) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 10.dp)) {
            if (items.isEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    color = Color.Transparent,
                ) {
                    Text("暂无${typeLabel(type)}规则", modifier = Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items.forEachIndexed { index, rule ->
                RuleCard(rule, color, index > 0, index < items.lastIndex, { onToggle(rule) }, { onEdit(rule) }, { onDelete(rule) }, { onMove(items, index, -1) }, { onMove(items, index, 1) })
            }
        }
    }
}

@Composable
private fun RuleCard(
    rule: TarvenRuleEntity,
    accent: Color,
    canUp: Boolean,
    canDown: Boolean,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle),
        shape = RoundedCornerShape(18.dp),
        color = if (rule.isEnabled != 0) accent.copy(alpha = .055f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .35f),
        border = BorderStroke(1.dp, if (rule.isEnabled != 0) accent.copy(alpha = .35f) else MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(38.dp).background(accent.copy(alpha = .12f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.AutoAwesome, null, tint = accent, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(11.dp))
                Text(rule.name, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, color = if (rule.isEnabled != 0) accent else MaterialTheme.colorScheme.onSurface)
                Switch(checked = rule.isEnabled != 0, onCheckedChange = { onToggle() })
            }
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Tag(typeShortLabel(rule.ruleType), accent)
                Tag(if (rule.scope == TarvenScope.GLOBAL) "全局" else "当前 Agent", MaterialTheme.colorScheme.onSurfaceVariant)
                Tag(if (rule.ruleType == TarvenRuleType.CONTEXT_INJECT) "${rule.role ?: "user"} · 深度 ${rule.depth ?: 0}" else if (rule.position == "prepend") "前置" else "后置", MaterialTheme.colorScheme.onSurfaceVariant)
                if (rule.wrap != 0) Tag("XML", MaterialTheme.colorScheme.tertiary)
            }
            HorizontalDivider(Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = onUp, enabled = canUp) { Icon(Icons.Default.KeyboardArrowUp, "上移") }
                IconButton(onClick = onDown, enabled = canDown) { Icon(Icons.Default.KeyboardArrowDown, "下移") }
                IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "编辑", tint = accent) }
                IconButton(onClick = onDelete) { Icon(Icons.Default.DeleteOutline, "删除", tint = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun Tag(text: String, color: Color) {
    Surface(shape = RoundedCornerShape(7.dp), color = color.copy(alpha = .10f), border = BorderStroke(.5.dp, color.copy(alpha = .22f))) {
        Text(text, color = color, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RuleEditorScreen(initial: TarvenRuleEntity, agentId: String, onBack: () -> Unit, onSave: (TarvenRuleEntity) -> Unit) {
    var rule by remember(initial.id) { mutableStateOf(initial) }
    val preview = remember(rule) {
        TarvenInjectionEngine.apply(
            "你是一个智能助手。",
            listOf(
                LLMMessage(LLMMessage.Role.USER, "你好，请问你是？"),
                LLMMessage(LLMMessage.Role.ASSISTANT, "我是你的 AI 助手。"),
                LLMMessage(LLMMessage.Role.USER, "帮我写一首关于秋天的诗。"),
            ),
            listOf(rule.copy(isEnabled = 1)),
            mapOf("agent_name" to "Agent", "current_date" to "2026-08-11"),
        )
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Column { Text(if (initial.name.isBlank()) "创建规则" else "编辑规则", fontWeight = FontWeight.Bold); Text("VCPChatTarven", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) } },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = { TextButton(enabled = rule.name.isNotBlank() && rule.content.isNotBlank(), onClick = { onSave(rule) }) { Text("保存", fontWeight = FontWeight.Bold) } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            EditorCard("基本信息") {
                OutlinedTextField(rule.name, { rule = rule.copy(name = it) }, label = { Text("规则名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                FieldTitle("注入类型")
                ChoiceRow(listOf(TarvenRuleType.SYSTEM_SUFFIX to "系统", TarvenRuleType.USER_SUFFIX to "用户", TarvenRuleType.CONTEXT_INJECT to "上下文"), rule.ruleType, typeColor(rule.ruleType)) { rule = rule.copy(ruleType = it) }
                FieldTitle("作用范围")
                ChoiceRow(listOf(TarvenScope.GLOBAL to "全局", TarvenScope.AGENT to "当前 Agent"), rule.scope, MaterialTheme.colorScheme.primary) { rule = rule.copy(scope = it, agentId = if (it == TarvenScope.AGENT) agentId else null) }
            }
            EditorCard("注入方式") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("XML 包裹", fontWeight = FontWeight.SemiBold); Text("标记为外部注入内容", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Switch(rule.wrap != 0, { rule = rule.copy(wrap = if (it) 1 else 0) })
                }
                if (rule.ruleType == TarvenRuleType.CONTEXT_INJECT) {
                    FieldTitle("虚拟消息角色")
                    ChoiceRow(listOf("user" to "用户", "assistant" to "智能体"), rule.role ?: "user", ContextOrange) { rule = rule.copy(role = it) }
                    OutlinedTextField((rule.depth ?: 0).toString(), { rule = rule.copy(depth = it.toIntOrNull()?.coerceIn(0, 20) ?: 0) }, label = { Text("插入深度（0–20）") }, modifier = Modifier.fillMaxWidth())
                } else {
                    FieldTitle("拼接位置")
                    ChoiceRow(listOf("prepend" to "前置", "append" to "后置"), rule.position ?: "append", typeColor(rule.ruleType)) { rule = rule.copy(position = it) }
                }
            }
            EditorCard("规则内容") {
                OutlinedTextField(rule.content, { rule = rule.copy(content = it) }, placeholder = { Text("输入要注入的内容…") }, modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp), textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                Text("支持 {{agent_name}}、{{current_date}}、{{runtime_context}} 等占位符", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            EditorCard("实时注入预览") { PreviewMessages(preview.systemPrompt, preview.messages) }
            Button(enabled = rule.name.isNotBlank() && rule.content.isNotBlank(), onClick = { onSave(rule) }, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) { Icon(Icons.Default.Check, null); Spacer(Modifier.width(8.dp)); Text("保存规则", fontWeight = FontWeight.Bold) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun EditorCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium); content() }
    }
}

@Composable private fun FieldTitle(text: String) { Text(text, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge) }

@Composable
private fun ChoiceRow(options: List<Pair<String, String>>, selected: String, accent: Color, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        options.forEach { (value, label) ->
            val active = value == selected
            Surface(
                modifier = Modifier.weight(1f).clickable { onSelect(value) },
                shape = RoundedCornerShape(12.dp),
                color = if (active) accent.copy(alpha = .13f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .45f),
                border = BorderStroke(1.dp, if (active) accent else MaterialTheme.colorScheme.outlineVariant),
            ) { Text(label, color = if (active) accent else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.padding(vertical = 11.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
        }
    }
}

@Composable
private fun PreviewMessages(systemPrompt: String?, messages: List<LLMMessage>) {
    PreviewBubble("SYSTEM", systemPrompt ?: "(空)", SystemBlue)
    messages.forEachIndexed { index, message -> PreviewBubble("[$index] ${message.role.value.uppercase()}", message.content, if (message.role == LLMMessage.Role.USER) UserGreen else ContextOrange) }
}

@Composable
private fun PreviewBubble(label: String, content: String, color: Color) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = color.copy(alpha = .06f), border = BorderStroke(1.dp, color.copy(alpha = .25f))) {
        Column(Modifier.padding(12.dp)) { Text(label, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace); Text(content, modifier = Modifier.padding(top = 5.dp), style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)) }
    }
}

private fun typeColor(type: String) = when (type) { TarvenRuleType.SYSTEM_SUFFIX -> SystemBlue; TarvenRuleType.USER_SUFFIX -> UserGreen; else -> ContextOrange }
private fun typeIcon(type: String) = when (type) { TarvenRuleType.SYSTEM_SUFFIX -> Icons.Default.Dns; TarvenRuleType.USER_SUFFIX -> Icons.Default.Person; else -> Icons.Default.AccountTree }
private fun typeLabel(type: String) = when (type) { TarvenRuleType.SYSTEM_SUFFIX -> "系统提示词注入"; TarvenRuleType.USER_SUFFIX -> "用户消息注入"; else -> "上下文消息注入" }
private fun typeShortLabel(type: String) = when (type) { TarvenRuleType.SYSTEM_SUFFIX -> "SYSTEM"; TarvenRuleType.USER_SUFFIX -> "USER"; else -> "CONTEXT" }
private fun typeDescription(type: String) = when (type) { TarvenRuleType.SYSTEM_SUFFIX -> "在系统提示词前后拼接"; TarvenRuleType.USER_SUFFIX -> "修改最新用户请求副本"; else -> "在对话历史指定深度插入" }

private fun newRule(order: Int, agentId: String): TarvenRuleEntity {
    val now = System.currentTimeMillis()
    return TarvenRuleEntity(id = "rule_${java.util.UUID.randomUUID()}", name = "", ruleType = TarvenRuleType.SYSTEM_SUFFIX, content = "", scope = TarvenScope.AGENT, agentId = agentId, wrap = 1, role = "user", depth = 0, position = "append", sortOrder = order, createdAt = now, updatedAt = now)
}
