package com.openminis.app.agent

import android.content.Context
import com.openminis.app.data.db.AgentEntity
import com.openminis.app.data.db.AgentIds

/** Renders the user-controlled Agent layer without exposing platform rules. */
object AgentPromptRenderer {
    fun render(context: Context, agent: AgentEntity?): String {
        if (agent == null || agent.id == AgentIds.DEFAULT) {
            // Migration compatibility: SOUL.md remains the live editor for the
            // default Agent until the Agent settings screen replaces it.
            return SystemPromptBuilder.identitySection(context)
        }

        val name = agent.name.trim().ifEmpty { "Agent" }
        val identity = SystemPromptPreferences.identityTemplate(context)
            .replace(SystemPromptPreferences.NAME_PLACEHOLDER, name)
            .trimEnd()
        val instructions = agent.instructions.trim()
        val language = when (agent.preferredLanguage?.lowercase()?.trim()) {
            "zh" -> "\n\n无论用户使用何种语言输入，都使用中文回复；只有用户明确要求其他语言时才切换。"
            "en" -> "\n\nReply in English unless the user explicitly asks for another language."
            else -> ""
        }
        return buildString {
            append(identity)
            if (instructions.isNotEmpty()) {
                append("\n\nAgent 人格与提示词（若与用户最新消息冲突，以用户最新消息为准）：\n")
                append(instructions)
            }
            append(language)
            append("\n\n")
        }
    }
}
