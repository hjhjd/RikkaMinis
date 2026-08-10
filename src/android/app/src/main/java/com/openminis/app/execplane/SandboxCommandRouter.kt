package com.openminis.app.execplane

import android.util.Log
import com.openminis.app.sandbox.ExecutionCoordinator

/** Routes the existing shell interface to the selected sandbox with PRoot fallback. */
class SandboxCommandRouter(
    private val settings: ExecPlaneSettingsRepository,
    private val bridge: ExecPlaneBridge,
) {
    suspend fun execute(
        sessionId: String,
        command: String,
        timeoutMs: Long,
        lineCallback: ((String) -> Unit)?,
    ): ExecutionCoordinator.CommandResult? {
        val server = settings.selectedForwardServer() ?: return null
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
            )
        } catch (error: RemoteChannelException) {
            // Channel unavailable: return null so ExecutionCoordinator runs the
            // same command once in built-in PRoot. Business failures never land here.
            Log.w(TAG, "[$sessionId] ${server.name} unavailable; falling back to PRoot: ${error.message}")
            null
        } catch (error: RemoteExecutionException) {
            ExecutionCoordinator.CommandResult(
                output = error.message ?: "Remote command failed",
                exitCode = 1,
                durationMs = 0L,
            )
        }
    }

    companion object { private const val TAG = "SandboxCommandRouter" }
}
