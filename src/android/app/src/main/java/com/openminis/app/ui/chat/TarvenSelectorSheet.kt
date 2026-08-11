package com.openminis.app.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openminis.app.data.db.*
import com.openminis.app.data.repository.TarvenRuleRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TarvenSelectorSheet(
    agentId: String,
    repository: TarvenRuleRepository,
    onDismiss: () -> Unit,
    onManage: () -> Unit,
) {
    val all by repository.observeAll().collectAsState(initial = emptyList())
    val rules = all.filter { it.scope == TarvenScope.GLOBAL || it.agentId == agentId }.sortedBy { it.sortOrder }
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Column { Text("Context System", style = MaterialTheme.typography.labelSmall); Text("规则仓", style = MaterialTheme.typography.titleLarge) }
            IconButton(onClick = onManage) { Icon(Icons.Outlined.Settings, "管理规则") }
        }
        if (rules.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.AutoAwesome, null)
                Text("尚未配置任何规则")
                Button(onClick = onManage) { Text("立即添加规则") }
            }
        } else rules.forEach { rule ->
            ListItem(
                headlineContent = { Text(rule.name) },
                supportingContent = { Text("${typeLabel(rule.ruleType)} · ${if (rule.scope == TarvenScope.GLOBAL) "全局" else "当前 Agent"}") },
                leadingContent = { Icon(Icons.Outlined.AutoAwesome, null) },
                trailingContent = { Switch(rule.isEnabled != 0, { scope.launch { repository.setEnabled(rule.id, it) } }) },
                modifier = Modifier.clickable { scope.launch { repository.setEnabled(rule.id, rule.isEnabled == 0) } },
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun typeLabel(type: String) = when (type) {
    TarvenRuleType.SYSTEM_SUFFIX -> "系统提示词"
    TarvenRuleType.USER_SUFFIX -> "用户消息"
    else -> "上下文消息"
}
