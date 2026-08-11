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
            return SystemPromptBuilder.personalitySection(context)
        }

        val instructions = agent.instructions.trim()
        return if (instructions.isEmpty()) "" else "$instructions\n\n"
    }
}
