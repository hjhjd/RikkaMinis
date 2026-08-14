package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ToolExecutionResultTest {
    @Test
    fun executionMetadataParticipatesInValueSemantics() {
        val base = ToolExecutionResult("out", true, sandboxName = "proot")
        assertEquals(base, base.copy())
        assertNotEquals(base, base.copy(timedOut = true))
        assertNotEquals(base, base.copy(cancelled = true))
        assertNotEquals(base, base.copy(truncated = true))
        assertNotEquals(base, base.copy(sandboxName = "remote"))
    }
}
