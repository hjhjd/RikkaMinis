package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiToolFlatItemsTest {
    @Test
    fun `同一助手消息的多个工具调用生成独立有序条目`() {
        val first = AssistantBlock(
            id = "call_a", kind = "tool_use", toolName = "read_file",
            toolTitle = "读取文件", toolStatus = ToolBlockStatus.PENDING,
        )
        val second = AssistantBlock(
            id = "call_b", kind = "tool_use", toolName = "search_web",
            toolTitle = "搜索网页", toolStatus = ToolBlockStatus.PENDING,
        )
        val rows = buildFlatChatItems(listOf(
            ChatMessage(id = "assistant_1", role = "assistant", content = "", toolBlocks = listOf(first, second))
        ))
        val tools = rows.filterIsInstance<FlatChatItem.AssistantToolUse>()

        assertEquals(listOf("call_a", "call_b"), tools.map { it.block.id })
        assertEquals(listOf("tool:assistant_1:call_a", "tool:assistant_1:call_b"), tools.map { it.key })
        assertTrue(tools.all { it.allToolBlocks.size == 2 })
        assertSame(first, tools[0].block)
        assertSame(second, tools[1].block)
    }

    @Test
    fun `工具状态变化不会改变多工具条目的稳定 key`() {
        val pending = listOf(
            AssistantBlock(id = "call_a", kind = "tool_use", toolStatus = ToolBlockStatus.PENDING),
            AssistantBlock(id = "call_b", kind = "tool_use", toolStatus = ToolBlockStatus.PENDING),
        )
        val completed = listOf(
            pending[0].copy(toolStatus = ToolBlockStatus.SUCCESS, content = "ok"),
            pending[1].copy(toolStatus = ToolBlockStatus.FAILED, content = "error"),
        )
        fun keys(blocks: List<AssistantBlock>) = buildFlatChatItems(listOf(
            ChatMessage(id = "assistant_1", role = "assistant", content = "", toolBlocks = blocks)
        )).filterIsInstance<FlatChatItem.AssistantToolUse>().map { it.key }

        assertEquals(keys(pending), keys(completed))
    }
}
