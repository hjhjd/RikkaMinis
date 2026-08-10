package com.openminis.app.execplane.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

const val EXECPLANE_PROTOCOL_VERSION = "0.1"

@Serializable
data class RpcRequest(
    val id: Long,
    val method: String,
    val params: JsonObject = JsonObject(emptyMap()),
    val target: String? = null,
    val ts: Long,
)

@Serializable
data class RpcResponse(
    val id: Long,
    val ok: Boolean,
    val result: JsonElement? = null,
    val error: RpcError? = null,
)

@Serializable
data class RpcError(
    val code: ExecPlaneErrorCode,
    val message: String,
)

@Serializable
data class RpcEvent(
    val event: String,
    val data: JsonElement,
)

@Serializable
enum class ExecPlaneErrorCode {
    @SerialName("CHANNEL_CONNECT_FAILED") CHANNEL_CONNECT_FAILED,
    @SerialName("CHANNEL_HANDSHAKE_FAILED") CHANNEL_HANDSHAKE_FAILED,
    @SerialName("CHANNEL_TIMEOUT") CHANNEL_TIMEOUT,
    @SerialName("CHANNEL_DISCONNECTED") CHANNEL_DISCONNECTED,
    @SerialName("CHANNEL_EXECUTOR_OFFLINE") CHANNEL_EXECUTOR_OFFLINE,
    @SerialName("EXEC_INVALID_REQUEST") EXEC_INVALID_REQUEST,
    @SerialName("EXEC_UNSUPPORTED_VERSION") EXEC_UNSUPPORTED_VERSION,
    @SerialName("EXEC_METHOD_NOT_FOUND") EXEC_METHOD_NOT_FOUND,
    @SerialName("EXEC_FORBIDDEN") EXEC_FORBIDDEN,
    @SerialName("EXEC_INVALID_PARAMS") EXEC_INVALID_PARAMS,
    @SerialName("EXEC_TIMEOUT") EXEC_TIMEOUT,
    @SerialName("EXEC_CANCELLED") EXEC_CANCELLED,
    @SerialName("EXEC_FAILED") EXEC_FAILED,
    @SerialName("EXEC_INTERNAL") EXEC_INTERNAL,
    ;

    val isChannelError: Boolean
        get() = name.startsWith("CHANNEL_")
}

object ExecPlaneJson {
    val codec = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }
}
