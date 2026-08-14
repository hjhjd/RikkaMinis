package com.openminis.app.execplane

import android.util.Log
import com.openminis.app.execplane.protocol.ExecPlaneErrorCode
import com.openminis.app.sandbox.ExecutionCoordinator

/** Routes the existing shell interface to an explicit/default sandbox. */
class SandboxCommandRouter(
    private val settings: ExecPlaneSettingsRepository,
    private val bridge: ExecPlaneBridge,
    private val environmentProvider: () -> Map<String, String> = { emptyMap() },
) {
    suspend fun execute(
        sessionId: String,
        command: String,
        timeoutMs: Long,
        lineCallback: ((String) -> Unit)?,
        requestedSandbox: String?,
    ): ExecutionCoordinator.CommandResult? {
        val explicit = requestedSandbox?.trim()?.takeIf { it.isNotEmpty() }
        val configured = explicit?.let { requested ->
            settings.forwardServers.value.firstOrNull {
                it.name.equals(requested, ignoreCase = true) || it.id == requested
            }
        }
        val targetName: String
        val environmentConfig: ForwardServerConfig?
        if (explicit != null) {
            if (configured != null && !configured.enabled) {
                throw IllegalStateException("Sandbox '$explicit' is disabled")
            }
            val lookupName = configured?.name ?: explicit
            val knownExecutor = bridge.connections.snapshots.value.keys.any {
                it.equals(lookupName, ignoreCase = true)
            }
            targetName = bridge.connections.resolveOnlineName(lookupName)
                ?: if (configured != null || knownExecutor) {
                    throw IllegalStateException("Sandbox '$explicit' unavailable")
                } else {
                    throw IllegalArgumentException("Unknown sandbox: $explicit")
                }
            environmentConfig = configured
        } else {
            val server = settings.selectedForwardServer() ?: return null
            targetName = server.name
            environmentConfig = server
        }

        return try {
            // Reverse executors have no persisted environment authorization yet,
            // so their explicit route receives no App environment variables.
            val env = environmentConfig?.let { settings.environmentFor(it, environmentProvider()) }.orEmpty()
            val caps = bridge.connections.online(targetName)?.caps.orEmpty()
            if (env.isNotEmpty() && "env.inject" !in caps) {
                throw RemoteExecutionException(
                    ExecPlaneErrorCode.ENV_NOT_AUTHORIZED,
                    "'$targetName' does not support environment injection",
                )
            }
            var streamed = false
            val remote = bridge.exec(targetName, command, timeoutMs, env) { _, data ->
                streamed = true
                data.lineSequence().filter { it.isNotEmpty() }.forEach { lineCallback?.invoke(it) }
            }
            val combined = buildString {
                append(remote.stdout)
                if (remote.stderr.isNotEmpty()) {
                    if (isNotEmpty() && !endsWith("\n")) append('\n')
                    append(remote.stderr)
                }
            }.trimEnd()
            if (!streamed) combined.lineSequence().forEach { lineCallback?.invoke(it) }
            ExecutionCoordinator.CommandResult(
                output = combined,
                exitCode = remote.exitCode,
                durationMs = remote.durationMs ?: 0L,
                truncated = remote.truncated,
                sandboxName = targetName,
            )
        } catch (error: RemoteChannelException) {
            if (explicit != null) {
                // Explicit targeting must be truthful: never execute elsewhere.
                throw IllegalStateException("Sandbox '$explicit' unavailable: ${error.message}", error)
            }
            Log.w(TAG, "[$sessionId] $targetName unavailable; falling back to PRoot: ${error.message}")
            ExecutionCoordinator.execute(sessionId, command, timeoutMs, lineCallback)
                .copy(degraded = true)
        } catch (error: RemoteExecutionException) {
            ExecutionCoordinator.CommandResult(
                output = error.message ?: "Remote command failed",
                exitCode = 1,
                durationMs = 0L,
                sandboxName = targetName,
            )
        }
    }

    companion object { private const val TAG = "SandboxCommandRouter" }
}
