package com.openminis.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShellOutputFramerTest {
    @Test
    fun markerMaySpanEveryByteBoundary() {
        val marker = "abc123"
        val source = "hello世界\n__MINIS_DONE_${marker}_EXIT_17__\n".toByteArray()
        for (split in 1 until source.size) {
            val framer = ShellOutputFramer(marker)
            val first = framer.feed(source.copyOfRange(0, split))
            val second = framer.feed(source.copyOfRange(split, source.size))
            assertEquals("split=$split", "hello世界\n", first.output + second.output)
            assertEquals("split=$split", 17, second.exitCode ?: first.exitCode)
        }
    }

    @Test
    fun utf8CodePointMaySpanChunksWithoutReplacement() {
        val framer = ShellOutputFramer("m")
        val source = "A你😀B__MINIS_DONE_m_EXIT_0__".toByteArray()
        val output = StringBuilder()
        var exit: Int? = null
        source.forEach { byte ->
            val frame = framer.feed(byteArrayOf(byte))
            output.append(frame.output)
            if (frame.exitCode != null) exit = frame.exitCode
        }
        assertEquals("A你😀B", output.toString())
        assertEquals(0, exit)
    }

    @Test
    fun markerLikeUserOutputIsNotMistakenForCompletion() {
        val framer = ShellOutputFramer("real")
        val first = framer.feed("x__MINIS_DONE_real_EXIT_nope__y".toByteArray())
        val second = framer.feed("__MINIS_DONE_real_EXIT_3__".toByteArray())
        assertEquals("x__MINIS_DONE_real_EXIT_nope__y", first.output + second.output)
        assertEquals(3, second.exitCode)
    }

    @Test
    fun finishFlushesIncompleteUtf8AndPlainTail() {
        val framer = ShellOutputFramer("m")
        val bytes = "tail你".toByteArray()
        val first = framer.feed(bytes, bytes.size - 1)
        val last = framer.finish()
        assertEquals("tail�", first.output + last.output)
        assertNull(last.exitCode)
    }
}
