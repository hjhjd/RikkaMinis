package com.openminis.app.tools.registry

import com.openminis.app.data.model.AgentToolDefinition
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Transitional adapter for the current APK-defined tools.
 *
 * Invocation intentionally remains in ChatViewModel during the first migration
 * step. The adapter makes discovery provider-based now, so execution can move
 * tool-by-tool without changing the model-facing list again.
 */
class LegacyAgentToolProvider(
    private val definitionsProvider: () -> List<AgentToolDefinition>,
) : ToolProvider {
    override val id: ToolProviderId = ID

    override fun tools(): List<ToolDescriptor> = definitionsProvider().map { definition ->
        ToolDescriptor(
            identity = ToolIdentity(id, definition.name),
            definition = definition,
        )
    }

    override fun invoke(invocation: ToolInvocation): Flow<ToolInvocationEvent> = flow {
        emit(ToolInvocationEvent.Failed(
            message = "Legacy tool execution has not migrated to ToolProvider yet",
            code = "LEGACY_DISPATCH_REQUIRED",
        ))
    }

    companion object {
        val ID = ToolProviderId("android-legacy")
    }
}
