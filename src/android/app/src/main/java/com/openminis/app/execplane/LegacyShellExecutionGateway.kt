package com.openminis.app.execplane

import com.openminis.app.sandbox.ExecutionCoordinator

/**
 * Compatibility boundary for the legacy shell_execute tool.
 *
 * New WebSocket functionality must use SandboxDispatchService. This gateway
 * exists only until the old remote shell_execute path is removed.
 */
object LegacyShellExecutionGateway {
    @Volatile
    private var remoteExecutor: (suspend (String, String, Long, ((String) -> Unit)?, String?) -> ExecutionCoordinator.CommandResult?)? = null

    fun installRemoteExecutor(executor: suspend (String, String, Long, ((String) -> Unit)?, String?) -> ExecutionCoordinator.CommandResult?) {
        remoteExecutor = executor
    }

    suspend fun execute(
        sessionId: String,
        command: String,
        timeout: Long = 600_000L,
        lineCallback: ((String) -> Unit)? = null,
        sandbox: String? = null,
    ): ExecutionCoordinator.CommandResult {
        if (sandbox.equals("proot", ignoreCase = true)) {
            return ExecutionCoordinator.execute(sessionId, command, timeout, lineCallback)
        }
        remoteExecutor?.invoke(sessionId, command, timeout, lineCallback, sandbox)?.let { return it }
        return ExecutionCoordinator.execute(sessionId, command, timeout, lineCallback)
    }
}
