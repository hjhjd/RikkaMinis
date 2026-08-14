package com.openminis.app.execplane

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class ResourceDescriptorTest {
    @Test
    fun descriptorHashesBytesWithoutEmbeddingBase64() {
        val file = File.createTempFile("resource", ".txt")
        try {
            file.writeText("hello")
            val d = ResourceDescriptor.fromFile(file, "text/plain")
            assertEquals(5L, d.size)
            assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", d.sha256)
            assertEquals("text/plain", d.mimeType)
        } finally { file.delete() }
    }

    @Test(expected = IllegalArgumentException::class)
    fun descriptorRejectsOversizedResource() {
        ResourceDescriptor("id", "x", ResourceDescriptor.MAX_RESOURCE_BYTES + 1, "0".repeat(64))
    }
}
