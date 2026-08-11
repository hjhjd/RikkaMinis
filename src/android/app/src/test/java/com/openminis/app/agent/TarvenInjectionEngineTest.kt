package com.openminis.app.agent

import com.openminis.app.data.db.TarvenRuleEntity
import com.openminis.app.data.db.TarvenRuleType
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TarvenInjectionEngineTest {
    @Test fun systemRulesRespectPrependAndAppendOrder() {
        val result = TarvenInjectionEngine.apply(
            "BASE", emptyList(), listOf(
                rule("a", TarvenRuleType.SYSTEM_SUFFIX, "A", "append", 2),
                rule("p", TarvenRuleType.SYSTEM_SUFFIX, "P", "prepend", 1),
            )
        )
        assertEquals("P\n\nBASE\n\nA", result.systemPrompt)
    }

    @Test fun userSuffixChangesRequestCopyOnly() {
        val original = listOf(LLMMessage(LLMMessage.Role.USER, "hello"))
        val result = TarvenInjectionEngine.apply(null, original, listOf(rule("u", TarvenRuleType.USER_SUFFIX, "format", "append", 0)))
        assertEquals("hello", original.single().content)
        assertEquals("hello\n\nformat", result.messages.single().content)
    }

    @Test fun userSuffixUpdatesStructuredContentParts() {
        val original = listOf(LLMMessage(
            LLMMessage.Role.USER,
            "hello",
            contentParts = listOf(AgentContentPart.Text("hello")),
        ))
        val result = TarvenInjectionEngine.apply(null, original, listOf(rule("u", TarvenRuleType.USER_SUFFIX, "format", "append", 0)))
        assertEquals(listOf(AgentContentPart.Text("hello")), original.single().contentParts)
        assertEquals(
            listOf(AgentContentPart.Text("hello"), AgentContentPart.Text("format")),
            result.messages.single().contentParts,
        )
    }

    @Test fun contextDepthZeroAppendsVirtualMessage() {
        val messages = listOf(LLMMessage(LLMMessage.Role.USER, "hello"))
        val r = rule("c", TarvenRuleType.CONTEXT_INJECT, "guide", null, 0).copy(role = "assistant", depth = 0)
        val result = TarvenInjectionEngine.apply(null, messages, listOf(r))
        assertEquals(2, result.messages.size)
        assertEquals(LLMMessage.Role.ASSISTANT, result.messages.last().role)
        assertEquals("guide", result.messages.last().content)
    }

    @Test fun disabledRuleDoesNothing() {
        val result = TarvenInjectionEngine.apply("BASE", emptyList(), listOf(rule("x", TarvenRuleType.SYSTEM_SUFFIX, "X", "append", 0).copy(isEnabled = 0)))
        assertEquals("BASE", result.systemPrompt)
        assertFalse(result.messages.isNotEmpty())
    }

    private fun rule(id: String, type: String, content: String, position: String?, order: Int) = TarvenRuleEntity(
        id = id, name = id, ruleType = type, content = content, wrap = 0, position = position,
        sortOrder = order, createdAt = 1, updatedAt = 1,
    )
}
