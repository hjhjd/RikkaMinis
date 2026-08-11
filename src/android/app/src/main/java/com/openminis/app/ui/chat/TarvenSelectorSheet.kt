package com.openminis.app.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.data.db.*
import com.openminis.app.data.repository.TarvenRuleRepository
import kotlinx.coroutines.launch

private val SheetSystemBlue = Color(0xFF4F7DFF)
private val SheetUserGreen = Color(0xFF16A779)
private val SheetContextOrange = Color(0xFFF28C38)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TarvenSelectorSheet(
    agentId: String,
    repository: TarvenRuleRepository,
    onDismiss: () -> Unit,
    onManage: () -> Unit,
) {
    val all by repository.observeAll().collectAsState(initial = emptyList())
    val rules = all.filter { it.scope == TarvenScope.GLOBAL || it.agentId == agentId }
        .sortedWith(compareBy<TarvenRuleEntity> { TarvenRuleType.ALL.indexOf(it.ruleType) }.thenBy { it.sortOrder })
    val enabledCount = rules.count { it.isEnabled != 0 }
    val scope = rememberCoroutineScope()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { BottomSheetDefaults.DragHandle(width = 42.dp) },
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = .12f), RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Outlined.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(23.dp)) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("CONTEXT SYSTEM", color = MaterialTheme.colorScheme.primary, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
                    Text("VCPChatTarven 规则仓", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                    Text(if (enabledCount > 0) "$enabledCount 条规则正在生效" else "当前没有启用规则", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FilledTonalIconButton(onClick = onManage) { Icon(Icons.Outlined.Settings, "管理规则") }
            }

            Spacer(Modifier.height(18.dp))
            if (rules.isEmpty()) {
                Surface(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .35f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.size(58.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = .10f), RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
                        }
                        Text("尚未配置任何规则", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Text("创建规则，在请求发出前自动注入上下文", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        Button(onClick = onManage, shape = RoundedCornerShape(14.dp)) { Text("立即添加规则") }
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    rules.forEach { rule ->
                        SelectorRuleCard(
                            rule = rule,
                            onToggle = { scope.launch { repository.setEnabled(rule.id, rule.isEnabled == 0) } },
                        )
                    }
                }
                OutlinedButton(
                    onClick = onManage,
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp).height(48.dp),
                    shape = RoundedCornerShape(15.dp),
                ) {
                    Icon(Icons.Outlined.Settings, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("管理与创建规则", fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SelectorRuleCard(rule: TarvenRuleEntity, onToggle: () -> Unit) {
    val accent = typeColor(rule.ruleType)
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle),
        shape = RoundedCornerShape(17.dp),
        color = if (rule.isEnabled != 0) accent.copy(alpha = .055f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .32f),
        border = BorderStroke(1.dp, if (rule.isEnabled != 0) accent.copy(alpha = .38f) else MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.padding(horizontal = 13.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).background(accent.copy(alpha = .12f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                Icon(typeIcon(rule.ruleType), null, tint = accent, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(rule.name, fontWeight = FontWeight.Bold, color = if (rule.isEnabled != 0) accent else MaterialTheme.colorScheme.onSurface)
                Row(Modifier.padding(top = 5.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    SheetTag(typeLabel(rule.ruleType), accent)
                    SheetTag(if (rule.scope == TarvenScope.GLOBAL) "全局" else "当前 Agent", MaterialTheme.colorScheme.onSurfaceVariant)
                    SheetTag(ruleDetail(rule), MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Switch(checked = rule.isEnabled != 0, onCheckedChange = { onToggle() })
        }
    }
}

@Composable
private fun SheetTag(text: String, color: Color) {
    Surface(shape = RoundedCornerShape(6.dp), color = color.copy(alpha = .10f), border = BorderStroke(.5.dp, color.copy(alpha = .22f))) {
        Text(text, color = color, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

private fun typeColor(type: String) = when (type) {
    TarvenRuleType.SYSTEM_SUFFIX -> SheetSystemBlue
    TarvenRuleType.USER_SUFFIX -> SheetUserGreen
    else -> SheetContextOrange
}
private fun typeIcon(type: String): ImageVector = when (type) {
    TarvenRuleType.SYSTEM_SUFFIX -> Icons.Default.Dns
    TarvenRuleType.USER_SUFFIX -> Icons.Default.Person
    else -> Icons.Default.AccountTree
}
private fun typeLabel(type: String) = when (type) {
    TarvenRuleType.SYSTEM_SUFFIX -> "SYSTEM"
    TarvenRuleType.USER_SUFFIX -> "USER"
    else -> "CONTEXT"
}
private fun ruleDetail(rule: TarvenRuleEntity): String = if (rule.ruleType == TarvenRuleType.CONTEXT_INJECT) {
    "${rule.role ?: "user"} · 深度 ${rule.depth ?: 0}"
} else if (rule.position == "prepend") "前置" else "后置"
