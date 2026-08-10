package com.openminis.app.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.openminis.app.MinisApp
import com.openminis.app.R
import com.openminis.app.execplane.WsBridgeState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SandboxSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as MinisApp
    val settings = app.execPlaneSettingsRepository
    val bridge = app.execPlaneBridge
    val enabled by settings.enabled.collectAsState()
    val savedPort by settings.port.collectAsState()
    val status by bridge.status.collectAsState()
    val executors by bridge.connections.snapshots.collectAsState()
    var portText by remember(savedPort) { mutableStateOf(savedPort.toString()) }
    var resetToken by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sandbox_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.padding(1.dp))
            Text(stringResource(R.string.sandbox_ws_section), style = MaterialTheme.typography.titleMedium)
            Column(
                Modifier.fillMaxWidth().background(
                    MaterialTheme.colorScheme.surfaceContainerLow,
                    RoundedCornerShape(12.dp),
                ).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.sandbox_ws_enabled))
                        Text(
                            bridgeStatusText(status.state, status.port, status.error),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = {
                            settings.setEnabled(it)
                            bridge.apply(enabled = it)
                        },
                    )
                }
                OutlinedTextField(
                    value = portText,
                    onValueChange = { portText = it.filter(Char::isDigit).take(5) },
                    label = { Text(stringResource(R.string.sandbox_ws_port)) },
                    supportingText = { Text("127.0.0.1 · 1024–65535") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        val value = portText.toIntOrNull()
                        if (value == null || !settings.setPort(value)) {
                            Toast.makeText(context, R.string.sandbox_invalid_port, Toast.LENGTH_SHORT).show()
                        } else if (enabled) bridge.start(value)
                    },
                ) { Text(stringResource(R.string.sandbox_apply)) }
            }

            Text(stringResource(R.string.sandbox_auth_section), style = MaterialTheme.typography.titleMedium)
            Column(
                Modifier.fillMaxWidth().background(
                    MaterialTheme.colorScheme.surfaceContainerLow,
                    RoundedCornerShape(12.dp),
                ).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("••••••••••••••••••••••••••••••••", fontFamily = FontFamily.Monospace)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { copyToken(context, settings.token()) }) {
                        Text(stringResource(R.string.sandbox_copy_token))
                    }
                    OutlinedButton(onClick = { resetToken = true }) {
                        Text(stringResource(R.string.sandbox_reset_token))
                    }
                }
                Text(
                    stringResource(R.string.sandbox_token_footer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(stringResource(R.string.sandbox_executors_section), style = MaterialTheme.typography.titleMedium)
            if (executors.isEmpty()) {
                Text(
                    stringResource(R.string.sandbox_no_executors),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            } else executors.values.sortedBy { it.name }.forEach { executor ->
                Row(
                    Modifier.fillMaxWidth().background(
                        MaterialTheme.colorScheme.surfaceContainerLow,
                        RoundedCornerShape(12.dp),
                    ).padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Computer, contentDescription = null)
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Text(executor.name)
                        Text(
                            "${if (executor.online) stringResource(R.string.sandbox_online) else stringResource(R.string.sandbox_offline)} · " +
                                executor.caps.sorted().joinToString(", "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(executor.direction.name.lowercase(), style = MaterialTheme.typography.labelSmall)
                }
            }
            Text(
                stringResource(R.string.sandbox_exec_disabled_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.padding(8.dp))
        }
    }

    if (resetToken) AlertDialog(
        onDismissRequest = { resetToken = false },
        title = { Text(stringResource(R.string.sandbox_reset_token)) },
        text = { Text(stringResource(R.string.sandbox_reset_token_confirm)) },
        confirmButton = {
            TextButton(onClick = {
                settings.resetToken()
                bridge.stop()
                if (enabled) bridge.start()
                resetToken = false
            }) { Text(stringResource(R.string.sandbox_reset)) }
        },
        dismissButton = {
            TextButton(onClick = { resetToken = false }) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

@Composable
private fun bridgeStatusText(state: WsBridgeState, port: Int?, error: String?): String = when (state) {
    WsBridgeState.STOPPED -> stringResource(R.string.sandbox_stopped)
    WsBridgeState.STARTING -> stringResource(R.string.sandbox_starting)
    WsBridgeState.LISTENING -> stringResource(R.string.sandbox_listening, port ?: 0)
    WsBridgeState.ERROR -> stringResource(R.string.sandbox_error, error.orEmpty())
}

private fun copyToken(context: Context, token: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("ExecPlane token", token))
    Toast.makeText(context, R.string.sandbox_token_copied, Toast.LENGTH_SHORT).show()
}
