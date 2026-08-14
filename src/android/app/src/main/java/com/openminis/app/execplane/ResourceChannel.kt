package com.openminis.app.execplane

import android.content.Context

/** Generic byte-movement facade; transfer.* is private compatibility transport. */
class ResourceChannel(bridge: ExecPlaneBridge) {
    private val transport = SandboxTransferService(bridge)

    data class Result(val descriptor: ResourceDescriptor, val path: String, val directory: Boolean)

    suspend fun upload(context: Context, sessionId: String, sandbox: String, source: String, destination: String, overwrite: String): Result {
        val r = transport.push(context, sessionId, sandbox, source, destination, overwrite)
        return Result(ResourceDescriptor(java.util.UUID.randomUUID().toString(), destination.substringAfterLast('/').ifBlank { "resource" }, r.size, r.sha256), r.path, r.directory)
    }

    suspend fun download(context: Context, sessionId: String, sandbox: String, source: String, destination: String, overwrite: String, directory: Boolean): Result {
        val r = transport.pull(context, sessionId, sandbox, source, destination, overwrite, directory)
        return Result(ResourceDescriptor(java.util.UUID.randomUUID().toString(), destination.substringAfterLast('/').ifBlank { "resource" }, r.size, r.sha256), r.path, r.directory)
    }
}
