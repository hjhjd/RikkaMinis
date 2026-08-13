package com.openminis.app.execplane

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteOutputLimitsTest {
    @Test
    fun eventAndAccumulatedOutputAreBounded() {
        val guard = RemoteOutputLimits.StreamGuard()
        assertFalse(guard.accept("x".repeat(RemoteOutputLimits.MAX_EVENT_BYTES + 1)))
        assertTrue(guard.truncated)

        val accumulated = RemoteOutputLimits.StreamGuard()
        val chunk = "x".repeat(RemoteOutputLimits.MAX_EVENT_BYTES)
        repeat(4) { assertTrue(accumulated.accept(chunk)) }
        assertFalse(accumulated.accept("x"))
        assertTrue(accumulated.truncated)
    }

    @Test
    fun finalResultKeepsUtf8BoundaryAndPropagatesTruncation() {
        val oversized = "你".repeat(RemoteOutputLimits.MAX_TOTAL_BYTES)
        val bounded = RemoteOutputLimits.bound(
            RemoteExecResult(oversized, "stderr", 0, truncated = false),
            streamTruncated = false,
        )
        assertTrue(bounded.truncated)
        assertTrue(bounded.stdout.toByteArray(Charsets.UTF_8).size <= RemoteOutputLimits.MAX_TOTAL_BYTES)
        assertTrue(
            bounded.stdout.toByteArray(Charsets.UTF_8).size +
                bounded.stderr.toByteArray(Charsets.UTF_8).size <= RemoteOutputLimits.MAX_TOTAL_BYTES,
        )
        assertFalse(bounded.stdout.endsWith("�"))

        assertTrue(RemoteOutputLimits.bound(RemoteExecResult("ok", "", 0, truncated = true), false).truncated)
        assertTrue(RemoteOutputLimits.bound(RemoteExecResult("ok", "", 0), true).truncated)
    }
}
