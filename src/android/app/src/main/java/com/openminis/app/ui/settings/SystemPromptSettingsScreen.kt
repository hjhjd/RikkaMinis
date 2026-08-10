package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.agent.SystemPromptPreferences

/** 编辑系统提示词覆盖值；空值不会复制默认内容，而是始终回退到应用内置中文模板。 */
@Composable
fun SystemPromptSettingsScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var identity by remember { mutableStateOf(SystemPromptPreferences.identityOverride(context)) }
    var main by remember { mutableStateOf(SystemPromptPreferences.mainOverride(context)) }
    var saved by remember { mutableStateOf(false) }

    SettingsScaffold(
        title = stringResource(R.string.system_prompt_settings_title),
        onBack = { onBack() },
    ) {
        SettingsSection(
            header = stringResource(R.string.system_prompt_identity_title),
            footer = stringResource(R.string.system_prompt_identity_footer),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                OutlinedTextField(
                    value = identity,
                    onValueChange = { identity = it; saved = false },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    placeholder = { Text(stringResource(R.string.system_prompt_default_placeholder)) },
                )
            }
        }

        SettingsSection(
            header = stringResource(R.string.system_prompt_main_title),
            footer = stringResource(R.string.system_prompt_main_footer),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                OutlinedTextField(
                    value = main,
                    onValueChange = { main = it; saved = false },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 320.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    placeholder = { Text(stringResource(R.string.system_prompt_default_placeholder)) },
                )
            }
        }

        SettingsSection(footer = stringResource(R.string.system_prompt_apply_footer)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        identity = ""
                        main = ""
                        SystemPromptPreferences.clear(context)
                        saved = true
                    },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.system_prompt_use_default)) }
                Button(
                    onClick = {
                        SystemPromptPreferences.save(context, identity, main)
                        saved = true
                    },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.soul_save)) }
            }
            if (saved) {
                Text(
                    text = stringResource(R.string.system_prompt_saved),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
    }
}
