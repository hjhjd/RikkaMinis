package com.openminis.app.tools.registry

import com.openminis.app.sandbox.ExecutionCoordinator
import com.openminis.app.tools.AgentTools
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.json.JSONObject

/** Owns the built-in PRoot shell_execute tool and its execution lifecycle. */
class PRootToolProvider : ToolProvider {
    override val id = ID

    override fun tools() = listOf(
        ToolDescriptor(
            identity = ToolIdentity(id, TOOL_NAME),
            definition = AgentTools.shellExecuteDefinition(),
        ),
    )

    suspend fun execute(
        sessionId: String,
        command: String,
        timeoutMs: Long,
        lineCallback: ((String) -> Unit)? = null,
    ) = ExecutionCoordinator.execute(sessionId, command, timeoutMs, lineCallback)

    override fun invoke(invocation: ToolInvocation): Flow<ToolInvocationEvent> = flow {
        val args = JSONObject(invocation.argumentsJson)
        val command = args.optString("command")
        require(command.isNotBlank()) { "command is required" }
        val timeoutMs = args.optLong("timeout", 900L).coerceIn(1L, 900L) * 1000L
        emit(ToolInvocationEvent.Started(args.optString("tool_title", TOOL_NAME)))
        try {
            val result = execute(invocation.sessionId, command, timeoutMs) { line ->
                // Flow cannot emit from the shell callback; the final bounded
                // result remains authoritative until invocation migration ends.
            }
            emit(ToolInvocationEvent.Completed(ToolInvocationResult(
                output = result.output,
                success = result.exitCode == 0,
                timedOut = result.timedOut,
                cancelled = result.cancelled,
                truncated = result.truncated,
            )))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            emit(ToolInvocationEvent.Failed(error.message ?: "PRoot execution failed"))
        }
    }

    /** Drop cwd/export/functions/background-process state for this session. */
    fun resetSession(sessionId: String) {
        ExecutionCoordinator.resetSession(sessionId)
    }

    override suspend fun cancel(invocationId: String): Boolean = false

    companion object {
        const val TOOL_NAME = "shell_execute"
        val ID = ToolProviderId("android-proot")
    }
}
