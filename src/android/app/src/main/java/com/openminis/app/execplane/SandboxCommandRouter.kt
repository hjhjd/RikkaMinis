package com.openminis.app.execplane

import android.util.Log
import com.openminis.app.sandbox.ExecutionCoordinator

/** Routes the existing shell interface to an explicit/default sandbox. */
class SandboxCommandRouter(
    private val settings: ExecPlaneSettingsRepository,
    private val bridge: ExecPlaneBridge,
) {
    suspend fun execute(
        sessionId: String,
        command: String,
        timeoutMs: Long,
        lineCallback: ((String) -> Unit)?,
        requestedSandbox: String?,
    ): ExecutionCoordinator.CommandResult? {
        val explicit = requestedSandbox?.trim()?.takeIf { it.isNotEmpty() }
        val server = if (explicit != null) {
            settings.forwardServers.value.firstOrNull {
                it.name.equals(explicit, ignoreCase = true) || it.id == explicit
            } ?: throw IllegalArgumentException("Unknown sandbox: $explicit")
        } else {
            settings.selectedForwardServer() ?: return null
        }

        return try {
            val remote = bridge.exec(server.name, command, timeoutMs)
            val combined = buildString {
                append(remote.stdout)
                if (remote.stderr.isNotEmpty()) {
                    if (isNotEmpty() && !endsWith("\n")) append('\n')
                    append(remote.stderr)
                }
            }.trimEnd()
            combined.lineSequence().forEach { lineCallback?.invoke(it) }
            ExecutionCoordinator.CommandResult(
                output = combined,
                exitCode = remote.exitCode,
                durationMs = remote.durationMs ?: 0L,
                sandboxName = server.name,
            )
        } catch (error: RemoteChannelException) {
            if (explicit != null) {
                // Explicit targeting must be truthful: never execute elsewhere.
                throw IllegalStateException("Sandbox '$explicit' unavailable: ${error.message}", error)
            }
            Log.w(TAG, "[$sessionId] ${server.name} unavailable; falling back to PRoot: ${error.message}")
            ExecutionCoordinator.executeLocal(sessionId, command, timeoutMs, lineCallback)
                .copy(degraded = true)
        } catch (error: RemoteExecutionException) {
            ExecutionCoordinator.CommandResult(
                output = error.message ?: "Remote command failed",
                exitCode = 1,
                durationMs = 0L,
                sandboxName = server.name,
            )
        }
    }

    companion object { private const val TAG = "SandboxCommandRouter" }
}
