package com.openminis.app.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.RadioButton
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
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SandboxSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as MinisApp
    val settings = app.execPlaneSettingsRepository
    val bridge = app.execPlaneBridge
    val enabled by settings.enabled.collectAsState()
    val savedPort by settings.port.collectAsState()
    val defaultSandboxId by settings.defaultSandboxId.collectAsState()
    val savedServers by settings.forwardServers.collectAsState()
    val status by bridge.status.collectAsState()
    val servers by bridge.connections.snapshots.collectAsState()
    val scope = rememberCoroutineScope()
    var portText by remember(savedPort) { mutableStateOf(savedPort.toString()) }
    var resetToken by remember { mutableStateOf(false) }
    var addServer by remember { mutableStateOf(false) }
    var commandTarget by remember { mutableStateOf<String?>(null) }
    var command by remember { mutableStateOf("uname -a") }
    var commandOutput by remember { mutableStateOf("") }
    var commandRunning by remember { mutableStateOf(false) }

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
            Text(stringResource(R.string.sandbox_default_section), style = MaterialTheme.typography.titleMedium)
            Column(
                Modifier.fillMaxWidth().background(
                    MaterialTheme.colorScheme.surfaceContainerLow,
                    RoundedCornerShape(12.dp),
                ),
            ) {
                SandboxChoiceRow(
                    title = stringResource(R.string.sandbox_builtin_proot),
                    subtitle = stringResource(R.string.sandbox_builtin_proot_subtitle),
                    selected = defaultSandboxId == com.openminis.app.execplane.ExecPlaneSettingsRepository.SANDBOX_PROOT,
                    onClick = { settings.setDefaultSandbox(com.openminis.app.execplane.ExecPlaneSettingsRepository.SANDBOX_PROOT) },
                )
                savedServers.sortedBy { it.name }.forEach { saved ->
                    SandboxChoiceRow(
                        title = saved.name,
                        subtitle = saved.url,
                        selected = defaultSandboxId == saved.id,
                        onClick = { settings.setDefaultSandbox(saved.id) },
                    )
                }
            }
            Text(
                stringResource(R.string.sandbox_fallback_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

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
            OutlinedButton(onClick = { addServer = true }) {
                Text(stringResource(R.string.sandbox_add_server))
            }
            if (servers.isEmpty()) {
                Text(
                    stringResource(R.string.sandbox_no_executors),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            } else servers.values.sortedBy { it.name }.forEach { server ->
                Row(
                    Modifier.fillMaxWidth().background(
                        MaterialTheme.colorScheme.surfaceContainerLow,
                        RoundedCornerShape(12.dp),
                    ).padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Computer, contentDescription = null)
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Text(server.name)
                        Text(
                            "${if (server.online) stringResource(R.string.sandbox_online) else stringResource(R.string.sandbox_offline)} · " +
                                server.caps.sorted().joinToString(", "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (server.online) {
                                TextButton(onClick = { commandTarget = server.name }) {
                                    Text(stringResource(R.string.sandbox_run_command))
                                }
                                TextButton(onClick = { bridge.disconnect(server.name) }) {
                                    Text(stringResource(R.string.sandbox_disconnect))
                                }
                            } else {
                                TextButton(onClick = {
                                    settings.forwardServers.value.firstOrNull { it.name == server.name }?.let(bridge::connect)
                                }) {
                                    Text(stringResource(R.string.sandbox_reconnect))
                                }
                                TextButton(onClick = { bridge.delete(server.name) }) {
                                    Text(stringResource(R.string.sandbox_delete))
                                }
                            }
                        }
                    }
                    Text(server.direction.name.lowercase(), style = MaterialTheme.typography.labelSmall)
                }
            }
            Text(
                stringResource(R.string.sandbox_exec_enabled_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.padding(8.dp))
        }
    }

    if (addServer) AddWebSocketServerDialog(
        onDismiss = { addServer = false },
        onAdd = { name, url, token ->
            settings.saveForwardServer(name, url, token)?.also(bridge::connect) != null
        },
    )

    commandTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { if (!commandRunning) commandTarget = null },
            title = { Text(stringResource(R.string.sandbox_run_command_on, target)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = command,
                        onValueChange = { command = it },
                        label = { Text(stringResource(R.string.sandbox_command)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (commandOutput.isNotEmpty()) Text(commandOutput, fontFamily = FontFamily.Monospace)
                }
            },
            confirmButton = {
                TextButton(enabled = !commandRunning && command.isNotBlank(), onClick = {
                    commandRunning = true
                    commandOutput = ""
                    scope.launch {
                        commandOutput = runCatching { bridge.exec(target, command) }
                            .fold(
                                onSuccess = { result ->
                                    buildString {
                                        append(result.stdout)
                                        if (result.stderr.isNotBlank()) append("\nstderr:\n${result.stderr}")
                                        append("\nexit: ${result.exitCode}")
                                    }
                                },
                                onFailure = { "Error: ${it.message}" },
                            )
                        commandRunning = false
                    }
                }) { Text(if (commandRunning) stringResource(R.string.sandbox_running) else stringResource(R.string.sandbox_run)) }
            },
            dismissButton = {
                TextButton(enabled = !commandRunning, onClick = { commandTarget = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
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

@Composable
private fun AddWebSocketServerDialog(
    onDismiss: () -> Unit,
    onAdd: (String, String, String) -> Boolean,
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("wss://") }
    var token by remember { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sandbox_add_server)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.sandbox_server_name)) })
                OutlinedTextField(url, { url = it }, label = { Text(stringResource(R.string.sandbox_server_url)) })
                OutlinedTextField(token, { token = it }, label = { Text(stringResource(R.string.sandbox_server_token)) })
                if (invalid) Text(stringResource(R.string.sandbox_invalid_server), color = MaterialTheme.colorScheme.error)
                Text(stringResource(R.string.sandbox_forward_security), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (onAdd(name, url, token)) onDismiss() else invalid = true
            }) { Text(stringResource(R.string.sandbox_connect)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

@Composable
private fun SandboxChoiceRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.padding(start = 8.dp)) {
            Text(title)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
