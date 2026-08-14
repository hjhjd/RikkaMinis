package com.openminis.app.execplane

import android.content.Context

/** Compatibility boundary for transfer.* until the Resource channel replaces it. */
class LegacyExecPlaneTransferGateway(bridge: ExecPlaneBridge) {
    private val transfers = SandboxTransferService(bridge)

    suspend fun push(
        context: Context, sessionId: String, sandbox: String, source: String,
        destination: String, overwrite: String,
    ) = transfers.push(context, sessionId, sandbox, source, destination, overwrite)

    suspend fun pull(
        context: Context, sessionId: String, sandbox: String, source: String,
        destination: String, overwrite: String, directory: Boolean,
    ) = transfers.pull(context, sessionId, sandbox, source, destination, overwrite, directory)
}
