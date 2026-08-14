package com.openminis.app.provider

import java.io.BufferedReader
import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SseEventReaderTest {
    @Test fun `支持可选空格注释和多行 data`() {
        val input = ": ping\nevent: message\ndata:first\ndata: second\n\ndata: tail\n\n"
        val reader = SseEventReader(BufferedReader(StringReader(input)))
        assertEquals("first\nsecond", reader.readData())
        assertEquals("tail", reader.readData())
        assertNull(reader.readData())
    }

    @Test fun `EOF 前未带空行的数据仍可读取`() {
        val reader = SseEventReader(BufferedReader(StringReader("data:{\\\"ok\\\":true}")))
        assertEquals("{\\\"ok\\\":true}", reader.readData())
        assertNull(reader.readData())
    }
}
