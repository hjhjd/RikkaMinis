package com.openminis.app.tools.registry

import com.openminis.app.data.model.AgentToolDefinition
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Aggregates tool providers without knowing concrete tool names.
 *
 * Model-facing names are currently required to be globally unique because the
 * existing LLM protocol returns only a tool name. Provider-qualified aliases
 * will be introduced with dynamic WS manifests before duplicate names are
 * allowed.
 */
class ToolRegistry(providers: Iterable<ToolProvider> = emptyList()) {
    private val providers = CopyOnWriteArrayList<ToolProvider>()

    init {
        providers.forEach(::register)
    }

    fun register(provider: ToolProvider) {
        require(providers.none { it.id == provider.id }) {
            "Tool provider already registered: ${provider.id}"
        }
        providers += provider
    }

    fun unregister(providerId: ToolProviderId): Boolean =
        providers.firstOrNull { it.id == providerId }?.let(providers::remove) ?: false

    fun descriptors(): List<ToolDescriptor> {
        val enabled = providers.flatMap { provider ->
            provider.tools().filter(ToolDescriptor::enabled)
        }
        val duplicate = enabled.groupBy { it.identity.toolName }
            .entries.firstOrNull { it.value.size > 1 }
        require(duplicate == null) {
            val sources = duplicate!!.value.joinToString { it.identity.providerId.value }
            "Duplicate model-facing tool '${duplicate.key}' from providers: $sources"
        }
        return enabled
    }

    fun definitions(): List<AgentToolDefinition> = descriptors().map(ToolDescriptor::definition)

    fun resolve(identity: ToolIdentity): ToolProvider? =
        providers.firstOrNull { it.id == identity.providerId }
            ?.takeIf { provider -> provider.tools().any { it.enabled && it.identity == identity } }

    fun resolveModelName(toolName: String): Pair<ToolProvider, ToolDescriptor>? {
        val matches = providers.flatMap { provider ->
            provider.tools().filter { it.enabled && it.identity.toolName == toolName }
                .map { provider to it }
        }
        return matches.singleOrNull()
    }
}
