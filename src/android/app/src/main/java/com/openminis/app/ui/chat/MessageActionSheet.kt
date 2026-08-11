package com.openminis.app.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** One message-level action surface shared by user and assistant messages. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MessageActionSheet(
    message: ChatMessage,
    canRetry: Boolean,
    onCopy: () -> Unit,
    onRetry: () -> Unit,
    onEdit: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        if (message.role == "user") "你的消息" else "Agent 回复",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        message.content.trim().ifBlank { "[…]" },
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            MessageActionRow("复制", Icons.Outlined.ContentCopy) { onDismiss(); onCopy() }
            // The visual container is shared, while actions are role-aware.
            // User messages prioritize editing; Agent replies prioritize regeneration.
            if (message.role == "user") {
                if (onEdit != null) MessageActionRow("编辑消息", Icons.Outlined.Edit) { onDismiss(); onEdit() }
                if (canRetry) MessageActionRow("重新生成回复", Icons.Outlined.Refresh) { onDismiss(); onRetry() }
            } else {
                if (canRetry) MessageActionRow("重新生成回复", Icons.Outlined.Refresh) { onDismiss(); onRetry() }
                if (onEdit != null) MessageActionRow("修改 Agent 回复", Icons.Outlined.Edit) { onDismiss(); onEdit() }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun MessageActionRow(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Icon(icon, null, Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
    }
}

/** Full-screen editor; saving replaces the selected user turn and regenerates its tail. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MessageEditScreen(
    initialText: String,
    role: String,
    onCancel: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember(initialText) { mutableStateOf(initialText) }
    BackHandler(onBack = onCancel)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text(if (role == "assistant") "修改 Agent 回复" else "编辑消息", fontWeight = FontWeight.SemiBold) },
                    navigationIcon = { TextButton(onClick = onCancel) { Text("取消") } },
                    actions = {
                        TextButton(onClick = { onSave(text.trim()) }, enabled = text.isNotBlank()) {
                            Text("保存")
                        }
                    },
                )
            },
        ) { padding ->
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).imePadding(),
                placeholder = { Text("输入消息") },
                shape = RoundedCornerShape(16.dp),
                textStyle = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}
