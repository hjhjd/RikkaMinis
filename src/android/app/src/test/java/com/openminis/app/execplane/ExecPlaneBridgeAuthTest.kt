package com.openminis.app.execplane

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecPlaneBridgeAuthTest {
    private val token = "0123456789abcdef0123456789abcdef0123456789abcdef"

    @Test
    fun exactTokenIsAccepted() {
        assertTrue(ExecPlaneBridge.constantTimeEquals(token, token))
    }

    @Test
    fun missingWrongAndEmptyTokensAreRejected() {
        assertFalse(ExecPlaneBridge.constantTimeEquals(null, token))
        assertFalse(ExecPlaneBridge.constantTimeEquals("wrong", token))
        assertFalse(ExecPlaneBridge.constantTimeEquals(token.dropLast(1) + "0", token))
        assertFalse(ExecPlaneBridge.constantTimeEquals("", ""))
    }

    @Test
    fun reverseNameCannotConflictWithSavedForwardServer() {
        val saved = ForwardServerConfig("id", "VCPMinis", "wss://example.invalid", token)

        assertTrue(ExecPlaneBridge.conflictsWithForwardServer("vcpminis", listOf(saved)))
        assertFalse(ExecPlaneBridge.conflictsWithForwardServer("VCPMinis-Reverse", listOf(saved)))
    }
}
