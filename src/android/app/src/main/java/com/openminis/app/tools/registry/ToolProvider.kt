package com.openminis.app.tools.registry

import com.openminis.app.data.model.AgentToolDefinition
import kotlinx.coroutines.flow.Flow

/** Stable identity of a tool source. Display names must never be used for routing. */
@JvmInline
value class ToolProviderId(val value: String) {
    init {
        require(value.matches(ID_PATTERN)) { "Invalid tool provider id: $value" }
    }

    override fun toString(): String = value

    companion object {
        private val ID_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
    }
}

data class ToolIdentity(
    val providerId: ToolProviderId,
    val toolName: String,
) {
    init {
        require(toolName.matches(NAME_PATTERN)) { "Invalid tool name: $toolName" }
    }

    companion object {
        private val NAME_PATTERN = Regex("^[A-Za-z][A-Za-z0-9_-]{0,63}$")
    }
}

data class ToolManifest(
    val providerId: ToolProviderId,
    val revision: String,
    val tools: List<ToolDescriptor>,
)

/** Provider-owned descriptor converted to the existing model-facing definition for now. */
data class ToolDescriptor(
    val identity: ToolIdentity,
    val definition: AgentToolDefinition,
    val enabled: Boolean = true,
    val manifestRevision: String? = null,
) {
    init {
        require(identity.toolName == definition.name) {
            "Tool identity and definition name differ: ${identity.toolName} != ${definition.name}"
        }
    }
}

data class ToolInvocation(
    val identity: ToolIdentity,
    val invocationId: String,
    val sessionId: String,
    val argumentsJson: String,
    val manifestRevision: String? = null,
)

sealed interface ToolInvocationEvent {
    data class Started(val title: String? = null) : ToolInvocationEvent
    data class Output(val text: String, val stream: String? = null) : ToolInvocationEvent
    data class UrlCaptured(val url: String) : ToolInvocationEvent
    data class Attachment(val path: String, val mimeType: String? = null) : ToolInvocationEvent
    data class Completed(val result: ToolInvocationResult) : ToolInvocationEvent
    data class Failed(val message: String, val code: String? = null) : ToolInvocationEvent
}

data class ToolInvocationResult(
    val output: String,
    val success: Boolean,
    val timedOut: Boolean = false,
    val cancelled: Boolean = false,
    val truncated: Boolean = false,
)

/**
 * Generic tool source. Implementations may be Android-native, PRoot-backed,
 * WebSocket-backed, or a temporary adapter around the legacy dispatcher.
 */
interface ToolProvider {
    val id: ToolProviderId

    /** Must be side-effect free. Dynamic providers should expose their cached descriptors. */
    fun tools(): List<ToolDescriptor>

    /** Cached manifest view; dynamic providers should override with the remote revision. */
    fun manifest(): ToolManifest = ToolManifest(
        providerId = id,
        revision = "local",
        tools = tools(),
    )

    fun invoke(invocation: ToolInvocation): Flow<ToolInvocationEvent>

    suspend fun cancel(invocationId: String): Boolean = false
}
