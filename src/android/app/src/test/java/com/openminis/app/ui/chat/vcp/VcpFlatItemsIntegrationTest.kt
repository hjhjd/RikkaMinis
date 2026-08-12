package com.openminis.app.ui.chat.vcp

import com.openminis.app.ui.chat.AssistantBlock
import com.openminis.app.ui.chat.ChatMessage
import com.openminis.app.ui.chat.FlatChatItem
import com.openminis.app.ui.chat.buildFlatChatItems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VcpFlatItemsIntegrationTest {
    @Test fun expandsVcpBlocksInsideNativeTextWithoutChangingOrder() {
        val text = "开头\n[--- VCP元思考链: 分析 ---]\n推理\n[--- 元思考链结束 ---]\n结尾"
        val message = ChatMessage(
            id = "m1",
            role = "assistant",
            content = text,
            toolBlocks = listOf(AssistantBlock(id = "t1", kind = "text", content = text)),
        )
        val vcp = buildFlatChatItems(listOf(message)).filterIsInstance<FlatChatItem.AssistantVcpBlock>()
        assertEquals(3, vcp.size)
        assertTrue(vcp[0].block is VcpContentBlock.Markdown)
        assertTrue(vcp[1].block is VcpContentBlock.Thought)
        assertTrue(vcp[2].block is VcpContentBlock.Markdown)
    }

    @Test fun streamingTailKeepsStableLazyListKeyWhileGrowing() {
        fun key(content: String): String {
            val message = ChatMessage(
                id = "m1",
                role = "assistant",
                content = content,
                isStreaming = true,
                toolBlocks = listOf(AssistantBlock(id = "t1", kind = "text", content = content)),
            )
            return buildFlatChatItems(listOf(message))
                .filterIsInstance<FlatChatItem.AssistantVcpBlock>()
                .last().key
        }
        assertEquals(
            key("[--- VCP元思考链 ---]\na"),
            key("[--- VCP元思考链 ---]\na longer stream tail"),
        )
    }

    @Test fun legacyMessageContentAlsoUsesVcpCompiler() {
        val message = ChatMessage(
            id = "legacy",
            role = "assistant",
            content = "<think>legacy reasoning</think>answer",
        )
        val rows = buildFlatChatItems(listOf(message))
        assertTrue(rows.any { it is FlatChatItem.AssistantVcpBlock && it.block is VcpContentBlock.Thought })
        assertTrue(rows.none { it is FlatChatItem.AssistantLegacyContent })
    }
}
