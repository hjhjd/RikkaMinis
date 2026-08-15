package com.openminis.app.agent

import android.content.Context
import com.openminis.app.data.db.AgentEntity
import com.openminis.app.data.db.AgentIds
import com.openminis.app.data.model.AgentToolDefinition

/** Renders the user-controlled Agent layer without exposing platform rules. */
object AgentPromptRenderer {
    /** 安全失败：Agent 未解析成功时绝不能回退注入默认工具模板。 */
    fun shouldInjectToolPrompt(agent: AgentEntity?): Boolean =
        agent?.toolPromptEnabled == 1

    fun toolsForRequest(
        agent: AgentEntity?,
        definitions: List<AgentToolDefinition>,
    ): List<AgentToolDefinition> =
        if (shouldInjectToolPrompt(agent)) definitions else emptyList()

    /** 人格与工具规则属于同一个系统级提示词，按稳定顺序合并。 */
    fun mergeSystemSections(agentSection: String, toolSection: String): String =
        listOf(agentSection, toolSection)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n\n")

    fun render(context: Context, agent: AgentEntity?): String {
        if (agent == null || agent.id == AgentIds.DEFAULT) {
            // Migration compatibility: SOUL.md remains the live editor for the
            // default Agent until the Agent settings screen replaces it.
            return SystemPromptBuilder.personalitySection(context)
        }

        val instructions = agent.instructions.trim()
        return if (instructions.isEmpty()) "" else "$instructions\n\n"
    }
}
