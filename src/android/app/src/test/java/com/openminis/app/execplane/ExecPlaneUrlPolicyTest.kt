package com.openminis.app.execplane

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecPlaneUrlPolicyTest {
    @Test
    fun secureWebSocketAllowsRemoteHosts() {
        assertTrue(ExecPlaneSettingsRepository.isAllowedForwardUrl("wss://example.com/ws", false))
        assertTrue(ExecPlaneSettingsRepository.isAllowedForwardUrl("wss://203.0.113.10:8766", false))
    }

    @Test
    fun plaintextLoopbackIsAlwaysAllowed() {
        assertTrue(ExecPlaneSettingsRepository.isAllowedForwardUrl("ws://127.0.0.1:8766", false))
        assertTrue(ExecPlaneSettingsRepository.isAllowedForwardUrl("ws://localhost:8766", false))
        assertTrue(ExecPlaneSettingsRepository.isAllowedForwardUrl("ws://[::1]:8766", false))
    }

    @Test
    fun plaintextPrivateLanRequiresExplicitOptIn() {
        val urls = listOf(
            "ws://10.0.0.2:8766",
            "ws://172.16.5.4:8766",
            "ws://172.31.255.254:8766",
            "ws://192.168.1.20:8766",
            "ws://169.254.10.2:8766",
            "ws://[fd12:3456::2]:8766",
            "ws://[fe80::2]:8766",
        )
        urls.forEach {
            assertFalse(it, ExecPlaneSettingsRepository.isAllowedForwardUrl(it, false))
            assertTrue(it, ExecPlaneSettingsRepository.isAllowedForwardUrl(it, true))
        }
    }

    @Test
    fun plaintextPublicAndHostnamesRemainRejected() {
        val urls = listOf(
            "ws://8.8.8.8:8766",
            "ws://172.32.0.1:8766",
            "ws://192.169.1.1:8766",
            "ws://sandbox.lan:8766",
            "http://192.168.1.2:8766",
        )
        urls.forEach { assertFalse(it, ExecPlaneSettingsRepository.isAllowedForwardUrl(it, true)) }
    }

    @Test
    fun credentialsFragmentsAndMalformedUrlsAreRejected() {
        assertFalse(ExecPlaneSettingsRepository.isAllowedForwardUrl("ws://user:pass@192.168.1.2:8766", true))
        assertFalse(ExecPlaneSettingsRepository.isAllowedForwardUrl("ws://192.168.1.2:8766/#secret", true))
        assertFalse(ExecPlaneSettingsRepository.isAllowedForwardUrl("not-a-url", true))
    }
}
