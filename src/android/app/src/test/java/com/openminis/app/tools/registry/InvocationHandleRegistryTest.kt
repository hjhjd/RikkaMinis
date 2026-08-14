package com.openminis.app.tools.registry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InvocationHandleRegistryTest {
    @Test fun cancelIsAvailableUntilCompletion() {
        val r = InvocationHandleRegistry(); var calls=0
        r.register("i") { calls++ }
        assertTrue(r.cancel("i")); assertTrue(r.cancel("i")); assertEquals(2,calls)
        r.complete("i"); assertFalse(r.cancel("i")); assertEquals(0,r.size())
    }
}
