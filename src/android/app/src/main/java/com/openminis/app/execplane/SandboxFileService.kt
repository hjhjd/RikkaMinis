package com.openminis.app.execplane

import android.util.Base64
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Direct file operations in an explicitly named WS sandbox. */
class SandboxFileService(private val bridge: ExecPlaneBridge) {
    data class ReadResult(val bytes: ByteArray, val revision: String?, val nextOffset: Long?)

    suspend fun read(sandbox: String, path: String, offset: Long = 0, maxBytes: Int = 1_048_576): ReadResult {
        val response = checked(sandbox, "fs.read", buildJsonObject {
            put("path", path); put("offset", offset); put("maxBytes", maxBytes)
        })
        val result = response.result?.jsonObject ?: JsonObject(emptyMap())
        return ReadResult(
            Base64.decode(result["data"]?.jsonPrimitive?.content.orEmpty(), Base64.DEFAULT),
            result["revision"]?.jsonPrimitive?.content,
            result["nextOffset"]?.jsonPrimitive?.content?.toLongOrNull(),
        )
    }

    suspend fun write(
        sandbox: String, path: String, bytes: ByteArray, append: Boolean = false,
        createParents: Boolean = false, expectedRevision: String? = null,
    ): JsonObject {
        val response = checked(sandbox, "fs.write", buildJsonObject {
            put("path", path)
            put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
            put("createMode", if (append) "append" else "replace")
            put("createParents", createParents)
            expectedRevision?.let { put("expectedRevision", it) }
        })
        return response.result?.jsonObject ?: JsonObject(emptyMap())
    }

    suspend fun mkdir(sandbox: String, path: String, parents: Boolean = true) =
        checked(sandbox, "fs.mkdir", buildJsonObject {
            put("path", path); put("parents", parents); put("existOk", true)
        })

    suspend fun remove(sandbox: String, path: String, recursive: Boolean, revision: String? = null) =
        checked(sandbox, "fs.remove", buildJsonObject {
            put("path", path); put("recursive", recursive); revision?.let { put("expectedRevision", it) }
        })

    suspend fun move(sandbox: String, source: String, destination: String, overwrite: Boolean) =
        checked(sandbox, "fs.move", buildJsonObject {
            put("path", source); put("destination", destination); put("overwrite", overwrite)
        })

    private suspend fun checked(sandbox: String, method: String, params: JsonObject) =
        bridge.request(sandbox, method, params).also { response ->
            if (!response.ok) throw RemoteExecutionException(
                "${response.error?.code ?: "EXEC_FAILED"}: ${response.error?.message ?: "Remote request failed"}",
            )
        }
}
