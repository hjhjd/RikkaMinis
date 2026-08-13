package com.openminis.app.execplane

import com.openminis.app.execplane.protocol.ExecPlaneCapabilities
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Android-side transport for opaque sandbox payloads. It never parses payload verbs. */
class SandboxDispatchService(private val bridge: ExecPlaneBridge) {
    data class Result(
        val output: String,
        val durationMs: Long? = null,
        val truncated: Boolean = false,
    )

    suspend fun dispatch(
        sandbox: String,
        payload: String,
        timeoutMs: Long,
        outputCallback: ((String) -> Unit)? = null,
    ): Result {
        require(payload.isNotEmpty()) { "Payload cannot be empty" }
        require('\u0000' !in payload) { "Payload contains NUL" }
        require(payload.toByteArray(Charsets.UTF_8).size <= MAX_PAYLOAD_BYTES) {
            "Payload exceeds $MAX_PAYLOAD_BYTES bytes"
        }
        val caps = bridge.connections.online(sandbox)?.caps.orEmpty()
        require(ExecPlaneCapabilities.DISPATCH in caps) { "Sandbox '$sandbox' does not support opaque dispatch" }
        val response = bridge.dispatch(sandbox, payload, timeoutMs, outputCallback)
        return Result(response.output, response.durationMs, response.truncated)
    }

    internal fun decodeResult(result: kotlinx.serialization.json.JsonElement?): Result {
        val value = result?.jsonObject ?: buildJsonObject {}
        val output = value["output"]?.jsonPrimitive?.content.orEmpty()
        require(output.toByteArray(Charsets.UTF_8).size <= MAX_FINAL_OUTPUT_BYTES) {
            "Dispatch result exceeds $MAX_FINAL_OUTPUT_BYTES bytes"
        }
        return Result(
            output = output,
            durationMs = value["durationMs"]?.jsonPrimitive?.content?.toLongOrNull(),
            truncated = value["truncated"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
        )
    }

    companion object {
        const val MAX_PAYLOAD_BYTES = 256 * 1024
        const val MAX_EVENT_BYTES = 256 * 1024
        const val MAX_FINAL_OUTPUT_BYTES = 1024 * 1024
    }
}
