package com.openminis.app.provider

import com.openminis.app.data.model.AgentContentPart
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectAttachmentTest {
    @Test
    fun `文本附件直接读取并包装`() {
        val file = tempFile("note.md", "hello")
        val part = part(file, "text/markdown")

        val text = DirectAttachment.text(part)

        assertTrue(text!!.contains("name=\"note.md\""))
        assertTrue(text.contains("hello"))
    }

    @Test
    fun `超限文本退回路径模式`() {
        val file = tempFile("large.txt", "x")
        val part = part(file, "text/plain").copy(size = DirectAttachment.MAX_TEXT_BYTES + 1)

        assertNull(DirectAttachment.text(part))
    }

    @Test
    fun `PDF 可按二进制直传`() {
        val bytes = "%PDF-test".toByteArray()
        val file = tempFile("doc.pdf", String(bytes))
        val part = part(file, "application/pdf")

        assertTrue(DirectAttachment.isPdf(part))
        assertEquals(bytes.toList(), DirectAttachment.binary(part)!!.toList())
    }

    private fun part(file: File, mime: String) = AgentContentPart.FileData(
        fileName = file.name,
        mimeType = mime,
        hostPath = file.absolutePath,
        linuxPath = "/var/minis/attachments/uploads/${file.name}",
        size = file.length(),
    )

    private fun tempFile(name: String, content: String): File {
        val dir = kotlin.io.path.createTempDirectory("direct-attachment-").toFile()
        return File(dir, name).apply { writeText(content) }
    }
}
