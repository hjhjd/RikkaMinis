package com.openminis.app.tools.registry

import com.openminis.app.sandbox.ExecutionCoordinator
import com.openminis.app.tools.AgentTools
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.callbackFlow
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/** Owns the built-in PRoot shell_execute tool and its execution lifecycle. */
class PRootToolProvider : ToolProvider {
    private val active = InvocationHandleRegistry()
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

    override fun invoke(invocation: ToolInvocation): Flow<ToolInvocationEvent> = callbackFlow {
        val args = JSONObject(invocation.argumentsJson)
        val command = args.optString("command")
        require(command.isNotBlank()) { "command is required" }
        val timeoutMs = args.optLong("timeout", 900L).coerceIn(1L, 900L) * 1000L
        val terminal = AtomicBoolean(false)
        active.register(invocation.invocationId) { ExecutionCoordinator.stopCurrentCommand(invocation.sessionId) }
        trySend(ToolInvocationEvent.Started(args.optString("tool_title", TOOL_NAME)))
        val job = launch {
            try {
                val result = execute(invocation.sessionId, command, timeoutMs) { line ->
                    trySend(ToolInvocationEvent.Output(line + "\n", "combined"))
                }
                if (terminal.compareAndSet(false, true)) trySend(ToolInvocationEvent.Completed(ToolInvocationResult(
                    output=result.output, success=result.exitCode==0, timedOut=result.timedOut, cancelled=result.cancelled,
                    truncated=result.truncated, durationMs=result.durationMs, exitCode=result.exitCode, sandboxId="proot", sandboxName="proot")))
            } catch (cancelled: CancellationException) {
                if (terminal.compareAndSet(false, true)) trySend(ToolInvocationEvent.Completed(ToolInvocationResult("", false, cancelled=true, sandboxId="proot", sandboxName="proot")))
            } catch (error: Throwable) {
                if (terminal.compareAndSet(false, true)) trySend(ToolInvocationEvent.Failed(error.message ?: "PRoot execution failed"))
            } finally { active.complete(invocation.invocationId); close() }
        }
        awaitClose { if (job.isActive) { active.cancel(invocation.invocationId); job.cancel() }; active.complete(invocation.invocationId) }
    }

    /** Drop cwd/export/functions/background-process state for this session. */
    fun resetSession(sessionId: String) {
        ExecutionCoordinator.resetSession(sessionId)
    }

    override suspend fun cancel(invocationId: String): Boolean = active.cancel(invocationId)

    companion object {
        const val TOOL_NAME = "shell_execute"
        val ID = ToolProviderId("android-proot")
    }
}
