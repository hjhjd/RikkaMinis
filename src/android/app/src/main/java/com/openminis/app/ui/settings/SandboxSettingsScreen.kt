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
    val allowLanPlaintextWs by settings.allowLanPlaintextWs.collectAsState()
    val sandboxMode by settings.sandboxMode.collectAsState()
    val defaultWsId by settings.defaultWsId.collectAsState()
    val savedServers by settings.forwardServers.collectAsState()
    val viewedInstructionRevisions by settings.viewedInstructionRevisions.collectAsState()
    val status by bridge.status.collectAsState()
    val servers by bridge.connections.snapshots.collectAsState()
    val scope = rememberCoroutineScope()
    var portText by remember(savedPort) { mutableStateOf(savedPort.toString()) }
    var resetToken by remember { mutableStateOf(false) }
    var addServer by remember { mutableStateOf(false) }
    var commandTarget by remember { mutableStateOf<String?>(null) }
    var instructionTarget by remember { mutableStateOf<String?>(null) }
    var concurrencyTarget by remember { mutableStateOf<String?>(null) }
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
                    title = stringResource(R.string.sandbox_mode_proot),
                    subtitle = stringResource(R.string.sandbox_builtin_proot_subtitle),
                    selected = sandboxMode == com.openminis.app.execplane.ExecPlaneSettingsRepository.MODE_PROOT,
                    onClick = { settings.setSandboxMode(com.openminis.app.execplane.ExecPlaneSettingsRepository.MODE_PROOT) },
                )
                SandboxChoiceRow(
                    title = stringResource(R.string.sandbox_mode_ws),
                    subtitle = stringResource(R.string.sandbox_mode_ws_subtitle),
                    selected = sandboxMode == com.openminis.app.execplane.ExecPlaneSettingsRepository.MODE_WS,
                    onClick = { settings.setSandboxMode(com.openminis.app.execplane.ExecPlaneSettingsRepository.MODE_WS) },
                )
            }
            val enabledServers = savedServers.filter { it.enabled }
            if (enabledServers.isNotEmpty()) {
                Text(stringResource(R.string.sandbox_default_ws_section), style = MaterialTheme.typography.titleMedium)
                Column(
                    Modifier.fillMaxWidth().background(
                        MaterialTheme.colorScheme.surfaceContainerLow,
                        RoundedCornerShape(12.dp),
                    ),
                ) {
                    enabledServers.sortedBy { it.name }.forEach { saved ->
                        SandboxChoiceRow(
                            title = saved.name,
                            subtitle = saved.url,
                            selected = defaultWsId == saved.id,
                            onClick = { settings.setDefaultWsSandbox(saved.id) },
                        )
                    }
                }
            } else if (sandboxMode == com.openminis.app.execplane.ExecPlaneSettingsRepository.MODE_WS) {
                Text(
                    stringResource(R.string.sandbox_no_enabled_ws),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
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
            Row(
                Modifier.fillMaxWidth().background(
                    MaterialTheme.colorScheme.surfaceContainerLow,
                    RoundedCornerShape(12.dp),
                ).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.sandbox_allow_lan_plaintext_ws))
                    Text(
                        stringResource(R.string.sandbox_allow_lan_plaintext_ws_footer),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = allowLanPlaintextWs,
                    onCheckedChange = settings::setAllowLanPlaintextWs,
                )
            }
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
                                stringResource(R.string.sandbox_concurrency_value, settings.concurrencyLimit(server.name)) + " · " +
                                (bridge.handshake(server.name)?.let { "v${it.protocol} · ${it.limits.maxConcurrentCommands} hard · " } ?: "") +
                                server.caps.sorted().joinToString(", "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { concurrencyTarget = server.name }) {
                                Text(stringResource(R.string.sandbox_concurrency_edit))
                            }
                            bridge.handshake(server.name)?.let { handshake ->
                                handshake.instructionSet?.let { instructions ->
                                    val changed = com.openminis.app.execplane.ExecPlaneSettingsRepository.instructionRevisionChanged(
                                        viewedInstructionRevisions[server.sandboxId],
                                        instructions.revision,
                                    )
                                    TextButton(onClick = {
                                        settings.markInstructionRevisionViewed(server.sandboxId, instructions.revision)
                                        instructionTarget = server.name
                                    }) {
                                        Text(stringResource(if (changed) R.string.sandbox_instruction_updated_badge else R.string.sandbox_instruction_set))
                                    }
                                }
                            }
                            if (server.online) {
                                TextButton(onClick = { commandTarget = server.name }) {
                                    Text(stringResource(R.string.sandbox_dispatch_test))
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

    concurrencyTarget?.let { target ->
        var value by remember(target) { mutableStateOf(settings.concurrencyLimit(target).toString()) }
        val parsed = value.toIntOrNull()
        AlertDialog(
            onDismissRequest = { concurrencyTarget = null },
            title = { Text(stringResource(R.string.sandbox_concurrency_title, target)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = value,
                        onValueChange = { value = it.filter(Char::isDigit).take(3) },
                        label = { Text(stringResource(R.string.sandbox_concurrency_label)) },
                        supportingText = { Text(stringResource(R.string.sandbox_concurrency_range)) },
                        singleLine = true,
                    )
                    Text(
                        stringResource(R.string.sandbox_concurrency_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = parsed != null && parsed in com.openminis.app.execplane.SandboxConcurrencyLimiter.MIN_LIMIT..com.openminis.app.execplane.SandboxConcurrencyLimiter.MAX_LIMIT,
                    onClick = {
                        if (parsed != null && settings.setConcurrencyLimit(target, parsed)) concurrencyTarget = null
                    },
                ) { Text(stringResource(R.string.sandbox_apply)) }
            },
            dismissButton = {
                TextButton(onClick = { concurrencyTarget = null }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }

    instructionTarget?.let { target ->
        val instructions = bridge.handshake(target)?.instructionSet
        if (instructions == null) {
            instructionTarget = null
        } else AlertDialog(
            onDismissRequest = { instructionTarget = null },
            title = { Text(instructions.title) },
            text = {
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        buildString {
                            append(stringResource(R.string.sandbox_instruction_revision, instructions.revision))
                            append(" · ")
                            append(stringResource(R.string.sandbox_instruction_length, instructions.content.length))
                            instructions.updatedAt?.takeIf { it > 0 }?.let {
                                append(" · ")
                                append(stringResource(
                                    R.string.sandbox_instruction_updated,
                                    android.text.format.DateUtils.formatDateTime(
                                        context,
                                        it,
                                        android.text.format.DateUtils.FORMAT_SHOW_DATE or
                                            android.text.format.DateUtils.FORMAT_SHOW_TIME or
                                            android.text.format.DateUtils.FORMAT_SHOW_YEAR,
                                    ),
                                ))
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(instructions.content, fontFamily = FontFamily.Monospace)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    copyInstructionSet(context, target, instructions.content)
                    instructionTarget = null
                }) { Text(stringResource(R.string.sandbox_copy_all_instructions)) }
            },
            dismissButton = {
                TextButton(onClick = { instructionTarget = null }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }

    commandTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { if (!commandRunning) commandTarget = null },
            title = { Text(stringResource(R.string.sandbox_dispatch_on, target)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = command,
                        onValueChange = { command = it },
                        label = { Text(stringResource(R.string.sandbox_payload)) },
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
                        commandOutput = runCatching {
                            app.sandboxDispatchService.dispatch(target, command, 600_000).output
                        }
                            .fold(
                                onSuccess = { result -> result },
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

private fun copyInstructionSet(context: Context, sandbox: String, content: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("$sandbox AI instructions", content))
    Toast.makeText(context, R.string.sandbox_instructions_copied, Toast.LENGTH_SHORT).show()
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
