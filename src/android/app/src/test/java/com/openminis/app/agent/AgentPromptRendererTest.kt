package com.openminis.app.agent

import com.openminis.app.data.db.AgentEntity
import com.openminis.app.data.model.AgentToolDefinition
import org.junit.Assert.assertEquals
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
    @Test
    fun `人格与工具规则合并为同一个系统提示词`() {
        assertEquals(
            "人格提示词\n\n工具规则",
            AgentPromptRenderer.mergeSystemSections("人格提示词\n\n", "工具规则"),
        )
    }

    @Test
    fun `关闭工具提示词后请求不再携带协议级工具定义`() {
        val definitions = listOf(
            AgentToolDefinition("file_read", "读取文件", emptyMap()),
        )
        assertTrue(AgentPromptRenderer.toolsForRequest(agent(1), definitions).isNotEmpty())
        assertTrue(AgentPromptRenderer.toolsForRequest(agent(0), definitions).isEmpty())
        assertTrue(AgentPromptRenderer.toolsForRequest(null, definitions).isEmpty())
    }

}
