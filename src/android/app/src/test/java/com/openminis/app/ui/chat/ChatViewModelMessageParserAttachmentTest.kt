package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatViewModelMessageParserAttachmentTest {
    @Test
    fun `解析新会话附件格式`() {
        val json = """[{"type":"sessionAttachment","value":{"relativePath":"uploads/a.pdf","mimeType":"application/pdf","originalFileName":"a.pdf","linuxPath":"/var/minis/attachments/uploads/a.pdf","size":42}}]"""

        val part = parsePartsJson(json).single() as ParsedPart.MediaRef

        assertEquals("uploads/a.pdf", part.relativePath)
        assertEquals(42L, part.size)
    }

    @Test
    fun `明确忽略旧 mediaRef 格式`() {
        val json = """[{"type":"mediaRef","value":{"relativePath":"2025/old.jpg","mimeType":"image/jpeg"}}]"""

        assertTrue(parsePartsJson(json).isEmpty())
    }
}
