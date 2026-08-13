package com.openminis.app.tools.registry

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ToolRegistryTest {
    @Test
    fun `registry aggregates enabled provider tools`() {
        val first = FakeProvider("first", listOf(tool("alpha"), tool("disabled", enabled = false)))
        val second = FakeProvider("second", listOf(tool("beta")))
        val registry = ToolRegistry(listOf(first, second))

        assertEquals(listOf("alpha", "beta"), registry.definitions().map { it.name })
        assertNotNull(registry.resolveModelName("alpha"))
        assertNull(registry.resolveModelName("disabled"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `duplicate model names from providers are rejected`() {
        ToolRegistry(listOf(
            FakeProvider("first", listOf(tool("same"))),
            FakeProvider("second", listOf(tool("same"))),
        )).definitions()
    }

    @Test
    fun `provider can be removed by stable id`() {
        val registry = ToolRegistry(listOf(FakeProvider("first", listOf(tool("alpha")))))

        assertFalse(registry.unregister(ToolProviderId("missing")))
        assertEquals(true, registry.unregister(ToolProviderId("first")))
        assertEquals(emptyList<AgentToolDefinition>(), registry.definitions())
    }

    private fun tool(name: String, enabled: Boolean = true): ToolDescriptor {
        val providerId = if (name == "beta") ToolProviderId("second") else ToolProviderId("first")
        return ToolDescriptor(
            identity = ToolIdentity(providerId, name),
            definition = AgentToolDefinition(
                name = name,
                description = "$name tool",
                parameters = mapOf("value" to AgentToolParam("string", "value")),
            ),
            enabled = enabled,
        )
    }

    private class FakeProvider(
        id: String,
        private val descriptors: List<ToolDescriptor>,
    ) : ToolProvider {
        override val id = ToolProviderId(id)
        override fun tools(): List<ToolDescriptor> = descriptors.map {
            if (it.identity.providerId == id) it else it.copy(identity = it.identity.copy(providerId = this.id))
        }
        override fun invoke(invocation: ToolInvocation): Flow<ToolInvocationEvent> = emptyFlow()
    }
}
