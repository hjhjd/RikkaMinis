package com.openminis.app.execplane

import com.openminis.app.execplane.protocol.ExecPlaneCapabilities
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Android-side transport for opaque sandbox payloads. It never parses payload verbs. */
class SandboxDispatchService(private val bridge: ExecPlaneBridge) {
    data class Result(
        val output: String,
        val durationMs: Long? = null,
        val truncated: Boolean = false,
        val resources: List<ResourceReference> = emptyList(),
    )

    suspend fun dispatch(
        sandbox: String,
        payload: String,
        timeoutMs: Long,
        outputCallback: ((String) -> Unit)? = null,
        resources: List<ResourceReference> = emptyList(),
    ): Result {
        require(payload.isNotEmpty()) { "Payload cannot be empty" }
        require('\u0000' !in payload) { "Payload contains NUL" }
        require(payload.toByteArray(Charsets.UTF_8).size <= MAX_PAYLOAD_BYTES) {
            "Payload exceeds $MAX_PAYLOAD_BYTES bytes"
        }
        val resolved = bridge.connections.resolveOnlineName(sandbox)
            ?: error("Unknown or offline sandbox ID: $sandbox")
        val caps = bridge.connections.online(resolved)?.caps.orEmpty()
        require(ExecPlaneCapabilities.DISPATCH in caps) { "Sandbox '$sandbox' does not support opaque dispatch" }
        require(resources.size <= MAX_RESOURCES) { "Too many resources" }
        resources.forEach { ResourceDescriptor(it.resourceId, it.name, it.size, it.sha256, it.mimeType) }
        val response = bridge.dispatch(resolved, payload, timeoutMs, outputCallback, resources)
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
            resources = (value["resources"] as? JsonArray).orEmpty().map { item ->
                val o = item.jsonObject
                ResourceReference(o["resourceId"]!!.jsonPrimitive.content, o["name"]!!.jsonPrimitive.content,
                    o["size"]!!.jsonPrimitive.content.toLong(), o["sha256"]!!.jsonPrimitive.content,
                    o["mimeType"]?.jsonPrimitive?.content)
            },
        )
    }

    companion object {
        const val MAX_PAYLOAD_BYTES = 256 * 1024
        const val MAX_EVENT_BYTES = 256 * 1024
        const val MAX_FINAL_OUTPUT_BYTES = 1024 * 1024
        const val MAX_RESOURCES = 16
    }
}
