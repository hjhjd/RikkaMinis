package com.openminis.app.execplane

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExecPlaneSettingsSelectionTest {
    private fun server(id: String, enabled: Boolean) = ForwardServerConfig(
        id = id,
        name = "server-$id",
        url = "wss://example.invalid/$id",
        token = "token-$id",
        enabled = enabled,
    )

    @Test
    fun disabledDefaultFallsBackToFirstEnabledServer() {
        val selected = ExecPlaneSettingsRepository.selectEnabledForwardServer(
            listOf(server("disabled", false), server("enabled", true)),
            "disabled",
        )

        assertEquals("enabled", selected?.id)
    }

    @Test
    fun enabledDefaultWinsEvenWhenItIsNotFirst() {
        val selected = ExecPlaneSettingsRepository.selectEnabledForwardServer(
            listOf(server("first", true), server("default", true)),
            "default",
        )

        assertEquals("default", selected?.id)
    }

    @Test
    fun instructionUpdateRequiresPreviouslyViewedDifferentRevision() {
        assertEquals(false, ExecPlaneSettingsRepository.instructionRevisionChanged(null, "r1"))
        assertEquals(false, ExecPlaneSettingsRepository.instructionRevisionChanged("r1", "r1"))
        assertEquals(true, ExecPlaneSettingsRepository.instructionRevisionChanged("r1", "r2"))
    }

    @Test
    fun noEnabledServerReturnsNull() {
        assertNull(ExecPlaneSettingsRepository.selectEnabledForwardServer(
            listOf(server("one", false), server("two", false)),
            "one",
        ))
    }
}