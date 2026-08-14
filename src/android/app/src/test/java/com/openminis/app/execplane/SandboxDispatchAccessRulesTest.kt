package com.openminis.app.execplane

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SandboxDispatchAccessRulesTest {
    @Test fun exposureAndStableIdAllowlistAreEnforced() {
        assertTrue(SandboxDispatchAccessRules.isAllowed(true, null, "stable-a"))
        assertTrue(SandboxDispatchAccessRules.isAllowed(true, setOf("stable-a"), "stable-a"))
        assertFalse(SandboxDispatchAccessRules.isAllowed(true, setOf("stable-a"), "display-name"))
        assertFalse(SandboxDispatchAccessRules.isAllowed(false, null, "stable-a"))
    }
}
