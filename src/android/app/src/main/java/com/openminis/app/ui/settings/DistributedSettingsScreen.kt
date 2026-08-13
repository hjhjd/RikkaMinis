package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.openminis.app.MinisApp
import com.openminis.app.R
import com.openminis.app.distributed.DistributedConnectionState
import com.openminis.app.distributed.DistributedSettingsRepository

@Composable
fun DistributedSettingsScreen(onBack: () -> Unit) {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as MinisApp
    val repository = app.distributedSettingsRepository
    val manager = app.distributedConnectionManager
    val persisted by repository.config.collectAsState()
    val status by manager.status.collectAsState()

    var wsUrl by remember(persisted.wsUrl) { mutableStateOf(persisted.wsUrl) }
    var vcpKey by remember(persisted.vcpKey) { mutableStateOf(persisted.vcpKey) }
    var deviceName by remember(persisted.deviceName) { mutableStateOf(persisted.deviceName) }
    var validationError by remember { mutableStateOf<String?>(null) }

    val stateText = when (status.state) {
        DistributedConnectionState.DISCONNECTED -> stringResource(R.string.distributed_state_disconnected)
        DistributedConnectionState.CONNECTING -> status.reconnectDelaySeconds?.let {
            stringResource(R.string.distributed_state_reconnecting, it)
        } ?: stringResource(R.string.distributed_state_connecting)
        DistributedConnectionState.CONNECTED -> stringResource(
            R.string.distributed_state_connected,
            status.serverId ?: "VCPToolBox",
        )
        DistributedConnectionState.DISCONNECTING -> stringResource(R.string.distributed_state_disconnecting)
    }

    SettingsScaffold(
        title = stringResource(R.string.distributed_title),
        onBack = onBack,
        navigation = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
            }
        },
    ) {
        SettingsSection(
            header = stringResource(R.string.distributed_section_connection),
            footer = stringResource(R.string.distributed_connection_footer),
        ) {
            SettingsSwitchRow(
                title = stringResource(R.string.distributed_enabled),
                subtitle = stateText,
                checked = persisted.enabled,
                // 开关必须始终可操作；开启时由 save() 校验地址和密钥并在页面提示。
                // 若把配置有效性绑定到 enabled，首次进入页面时用户会得到一个
                // 无法点击、也无法说明缺少什么的灰色开关。
                enabled = true,
                icon = Icons.Outlined.CloudSync,
                iconColor = Color(0xFF5856D6),
                onCheckedChange = { wanted ->
                    validationError = null
                    if (repository.save(wsUrl, vcpKey, deviceName, wanted)) {
                        manager.reconcile()
                        app.vcpInfoConnectionManager.reconcile()
                        app.vcpLogConnectionManager.reconcile()
                    }
                    else validationError = app.getString(R.string.distributed_invalid_config)
                },
                showDivider = false,
            )
        }

        SettingsSection(header = stringResource(R.string.distributed_section_server)) {
            SettingsCardBlock {
                OutlinedTextField(
                    value = wsUrl,
                    onValueChange = { wsUrl = it; validationError = null },
                    label = { Text(stringResource(R.string.distributed_ws_url)) },
                    placeholder = { Text("ws://192.168.1.2:5800") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = vcpKey,
                    onValueChange = { vcpKey = it; validationError = null },
                    label = { Text(stringResource(R.string.distributed_vcp_key)) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = deviceName,
                    onValueChange = { deviceName = it },
                    label = { Text(stringResource(R.string.distributed_device_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                validationError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
                }
                status.lastError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        val valid = repository.save(wsUrl, vcpKey, deviceName, persisted.enabled)
                        validationError = if (valid) null else app.getString(R.string.distributed_invalid_config)
                        if (valid && persisted.enabled) {
                            manager.reconnectNow()
                            app.vcpInfoConnectionManager.reconnectNow()
                            app.vcpLogConnectionManager.reconnectNow()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(if (persisted.enabled) R.string.distributed_save_reconnect else R.string.distributed_save))
                }
            }
        }

        SettingsSection(footer = stringResource(R.string.distributed_phase_notice)) {
            SettingsRow(
                title = stringResource(R.string.distributed_protocol_status),
                subtitle = status.clientId?.let { stringResource(R.string.distributed_client_id, it) } ?: stateText,
                showDivider = false,
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}
