package com.openminis.app.ui.agents

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openminis.app.data.repository.SkillRepository
import com.openminis.app.ui.chat.StandardChatSheet
import com.openminis.app.ui.settings.SettingsScaffold
import com.openminis.app.ui.settings.SettingsSection
import com.openminis.app.ui.settings.SkillRowItem

@Composable
fun AgentSkillsScreen(agentId: String, skillRepository: SkillRepository, onBack: () -> Unit) {
    val skills by skillRepository.skills.collectAsState()
    var states by remember(agentId, skills) {
        mutableStateOf(skills.associate { it.id to skillRepository.isEnabledForAgent(it.id, agentId) })
    }
    SettingsScaffold(title = "Agent 技能", onBack = onBack) {
        SettingsSection(header = "已安装技能", footer = "技能文件全局安装，但启用状态仅作用于此 Agent。会话临时覆盖仍具有更高优先级。") {
            if (skills.isEmpty()) {
                Text("暂无已安装技能", modifier = Modifier.padding(16.dp))
            } else {
                skills.forEachIndexed { index, skill ->
                    SkillRowItem(
                        name = skill.name,
                        description = skill.description,
                        importSource = skill.importSource,
                        isEnabled = states[skill.id] == true,
                        onToggle = { enabled ->
                            skillRepository.setAgentBinding(agentId, skill.id, enabled)
                            states = states + (skill.id to enabled)
                        },
                        showDivider = index < skills.lastIndex,
                    )
                }
            }
        }
    }
}
