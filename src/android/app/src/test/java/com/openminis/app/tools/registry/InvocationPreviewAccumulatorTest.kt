package com.openminis.app.tools.registry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InvocationPreviewAccumulatorTest {
    @Test fun highFrequencyChunksKeepBoundedTail() {
        val a = InvocationPreviewAccumulator(8)
        repeat(100) { a.append(it.toString().last().toString()) }
        assertTrue(a.truncated)
        assertTrue(a.snapshot().endsWith("23456789"))
    }
    @Test fun smallOutputIsUnchanged() {
        val a = InvocationPreviewAccumulator(8)
        assertEquals("abc", a.append("abc")); assertFalse(a.truncated)
    }
}
