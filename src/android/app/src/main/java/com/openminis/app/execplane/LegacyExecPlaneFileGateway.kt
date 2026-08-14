package com.openminis.app.execplane

import android.content.Context

/** Compatibility boundary for deprecated fs.* and transfer.* RPC methods. */
class LegacyExecPlaneFileGateway(bridge: ExecPlaneBridge) {
    private val files = SandboxFileService(bridge)
    private val transfers = SandboxTransferService(bridge)

    suspend fun read(sandbox: String, path: String, offset: Long = 0, maxBytes: Int = 1_048_576) =
        files.read(sandbox, path, offset, maxBytes)

    suspend fun readAll(sandbox: String, path: String, maxBytes: Int) =
        files.readAll(sandbox, path, maxBytes)

    suspend fun write(
        sandbox: String,
        path: String,
        bytes: ByteArray,
        append: Boolean = false,
        createParents: Boolean = false,
        expectedRevision: String? = null,
    ) = files.write(sandbox, path, bytes, append, createParents, expectedRevision)

    suspend fun push(
        context: Context, sessionId: String, sandbox: String, source: String,
        destination: String, overwrite: String,
    ) = transfers.push(context, sessionId, sandbox, source, destination, overwrite)

    suspend fun pull(
        context: Context, sessionId: String, sandbox: String, source: String,
        destination: String, overwrite: String, directory: Boolean,
    ) = transfers.pull(context, sessionId, sandbox, source, destination, overwrite, directory)
}
