package com.openminis.app.tools.registry

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PRootToolProviderTest {
    @Test
    fun shellDefinitionIsLocalOnly() {
        val definition = PRootToolProvider().tools().single().definition
        assertTrue(definition.description.contains("built-in Android PRoot"))
        assertTrue(definition.description.contains("sandbox_dispatch"))
        assertFalse(definition.parameters.containsKey("sandbox"))
        assertTrue(definition.description.contains("persistent /bin/sh"))
        assertTrue(definition.description.contains("Timeout or cancellation"))
    }
}
