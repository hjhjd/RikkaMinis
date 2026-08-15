package com.openminis.app.agent

import com.openminis.app.data.db.AgentEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPromptRendererTest {
    private fun agent(toolPromptEnabled: Int) = AgentEntity(
        id = "agent",
        name = "Agent",
        toolPromptEnabled = toolPromptEnabled,
        createdAt = 1L,
        updatedAt = 1L,
    )

    @Test
    fun `仅在 Agent 明确启用时注入工具提示词`() {
        assertTrue(AgentPromptRenderer.shouldInjectToolPrompt(agent(1)))
        assertFalse(AgentPromptRenderer.shouldInjectToolPrompt(agent(0)))
    }

    @Test
    fun `Agent 尚未解析时禁止回退注入工具提示词`() {
        assertFalse(AgentPromptRenderer.shouldInjectToolPrompt(null))
    }
}
