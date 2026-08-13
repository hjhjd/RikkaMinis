package com.openminis.app.execplane.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

const val EXECPLANE_PROTOCOL_VERSION = "0.2"

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
data class ExecOutputEvent(
    val requestId: Long,
    val sequence: Long,
    val stream: String,
    val data: String,
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
    @SerialName("EXEC_OUTPUT_LIMIT") EXEC_OUTPUT_LIMIT,
    @SerialName("EXEC_RESOURCE_LIMIT") EXEC_RESOURCE_LIMIT,
    @SerialName("EXEC_INTERNAL") EXEC_INTERNAL,
    @SerialName("CAPABILITY_UNSUPPORTED") CAPABILITY_UNSUPPORTED,
    @SerialName("FS_INVALID_PATH") FS_INVALID_PATH,
    @SerialName("FS_NOT_FOUND") FS_NOT_FOUND,
    @SerialName("FS_PERMISSION_DENIED") FS_PERMISSION_DENIED,
    @SerialName("FS_CONFLICT") FS_CONFLICT,
    @SerialName("TRANSFER_TOO_LARGE") TRANSFER_TOO_LARGE,
    @SerialName("TRANSFER_CHECKSUM_MISMATCH") TRANSFER_CHECKSUM_MISMATCH,
    @SerialName("TRANSFER_INVALID_STATE") TRANSFER_INVALID_STATE,
    @SerialName("ENV_NOT_AUTHORIZED") ENV_NOT_AUTHORIZED,
    @SerialName("IDENTITY_VERIFICATION_FAILED") IDENTITY_VERIFICATION_FAILED,
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
