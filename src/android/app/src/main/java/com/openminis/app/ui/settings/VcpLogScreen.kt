package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import com.openminis.app.R
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openminis.app.MinisApp
import com.openminis.app.vcplog.VcpLogConnectionState
import com.openminis.app.vcplog.VcpLogEvent

@Composable
fun VcpLogScreen(onServerSettingsClick: () -> Unit) {
    val app = LocalContext.current.applicationContext as MinisApp
    val manager = app.vcpLogConnectionManager
    val config by app.distributedSettingsRepository.config.collectAsState()
    val status by manager.status.collectAsState()
    val events by manager.store.events.collectAsState()
    var filter by remember { mutableStateOf("all") }
    var selected by remember { mutableStateOf<VcpLogEvent?>(null) }

    DisposableEffect(manager.store) {
        manager.store.beginObserving()
        onDispose { manager.store.endObserving() }
    }

    val stateText = when (status.state) {
        VcpLogConnectionState.CLOSED -> stringResource(
            if (config.enabled) R.string.vcp_log_state_closed else R.string.vcp_log_state_disabled,
        )
        VcpLogConnectionState.CONNECTING -> stringResource(R.string.vcp_log_state_connecting)
        VcpLogConnectionState.CONNECTED -> stringResource(R.string.vcp_log_state_connected)
        VcpLogConnectionState.ERROR -> status.reconnectDelaySeconds?.let {
            stringResource(R.string.vcp_log_state_retrying, it)
        } ?: stringResource(R.string.vcp_log_state_error)
    }
    val visible = events.filter {
        when (filter) {
            "error" -> it.category == com.openminis.app.vcplog.VcpLogEventCategory.ERROR
            "tool" -> it.category == com.openminis.app.vcplog.VcpLogEventCategory.TOOL
            else -> true
        }
    }

    Column(Modifier.fillMaxSize()) {
        SettingsSection(
            header = stringResource(R.string.vcp_log_section_connection),
            footer = stringResource(R.string.vcp_log_connection_footer),
        ) {
            SettingsValueRow(stringResource(R.string.vcp_log_status), stateText, subtitle = status.lastError, showDivider = true)
            SettingsValueRow(
                title = stringResource(R.string.vcp_log_server),
                value = if (config.wsUrl.isBlank()) stringResource(R.string.vcp_log_not_configured) else redactServer(config.wsUrl),
                subtitle = stringResource(R.string.vcp_log_device_name, config.deviceName),
                onClick = onServerSettingsClick,
                showDivider = false,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = manager::reconnectNow, enabled = config.enabled, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.vcp_log_reconnect))
            }
            OutlinedButton(onClick = manager.store::clear, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.vcp_log_clear))
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(
                "all" to stringResource(R.string.vcp_log_filter_all),
                "tool" to stringResource(R.string.vcp_log_filter_tools),
                "error" to stringResource(R.string.vcp_log_filter_errors),
            ).forEach { (key, label) ->
                if (filter == key) Button(onClick = { filter = key }, modifier = Modifier.weight(1f)) { Text(label) }
                else OutlinedButton(onClick = { filter = key }, modifier = Modifier.weight(1f)) { Text(label) }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (visible.isEmpty()) {
            Text(stringResource(R.string.vcp_log_empty), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(visible, key = { it.id }) { event ->
                    SettingsRow(
                        title = event.displayTitle,
                        subtitle = event.content,
                        onClick = { selected = event },
                        showDivider = true,
                        minHeight = 72.dp,
                        trailing = {
                            Text(
                                event.status.orEmpty(),
                                color = if (event.status.equals("error", true)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }
        }
    }

    selected?.let { event -> VcpLogEventDialog(event) { selected = null } }
}

@Composable
private fun VcpLogEventDialog(event: VcpLogEvent, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val pretty = remember(event.rawJson) {
        runCatching { org.json.JSONObject(event.rawJson).toString(2) }.getOrDefault(event.rawJson)
    }
    val preview = pretty.take(64 * 1024)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(event.toolName ?: event.type) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                if (preview.length < pretty.length) {
                    Text(
                        stringResource(R.string.vcp_log_preview_truncated),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Text(
                    text = preview,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { clipboard.setText(AnnotatedString(pretty)) }) {
                Text(stringResource(R.string.vcp_log_copy_json))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.vcp_log_close)) } },
    )
}

private fun redactServer(value: String): String = value
    .replace(Regex("VCP_Key=[^/?#]+", RegexOption.IGNORE_CASE), "VCP_Key=***")
    .take(80)
