package com.openminis.app.vcplog

import org.json.JSONObject

enum class VcpLogConnectionState { CLOSED, CONNECTING, CONNECTED, ERROR }

data class VcpLogConnectionStatus(
    val state: VcpLogConnectionState = VcpLogConnectionState.CLOSED,
    val lastError: String? = null,
    val reconnectDelaySeconds: Long? = null,
    val connectedUrl: String? = null,
)

enum class VcpLogEventCategory { ERROR, SUCCESS, TOOL, STATUS, RAW, INFO }

data class VcpLogEvent(
    val id: String,
    val type: String,
    val status: String?,
    val toolName: String?,
    val content: String,
    val source: String?,
    val rawJson: String,
    val receivedAtMs: Long,
    val eventAtMs: Long? = null,
) {
    val category: VcpLogEventCategory
        get() = when {
            status.equals("error", true) || type.contains("error", true) -> VcpLogEventCategory.ERROR
            type == "raw_text" && RAW_ERROR.containsMatchIn(content) -> VcpLogEventCategory.ERROR
            status.equals("success", true) -> VcpLogEventCategory.SUCCESS
            type == "vcp_log" || type == "vcp-log-message" || !toolName.isNullOrBlank() -> VcpLogEventCategory.TOOL
            type == "vcp-log-status" || type == "vcp-core-status" -> VcpLogEventCategory.STATUS
            type == "raw_text" -> VcpLogEventCategory.RAW
            else -> VcpLogEventCategory.INFO
        }

    val displayTitle: String get() = toolName ?: source ?: type

    companion object {
        private val RAW_ERROR = Regex("(?im)^(?:\\[?error]?|failed:|failure:|exception:)")
    }
}

object VcpLogEventParser {
    fun parse(id: String, raw: String, receivedAtMs: Long = System.currentTimeMillis()): VcpLogEvent {
        val json = runCatching { JSONObject(raw) }.getOrNull()
            ?: return VcpLogEvent(id, "raw_text", null, null, raw, "VCPLog", raw, receivedAtMs)
        val data = json.optJSONObject("data")
        val type = json.optString("type").ifBlank { "unknown" }
        val status = data.stringOrNull("status") ?: json.stringOrNull("status")
        val toolName = data.stringOrNull("tool_name") ?: data.stringOrNull("toolName")
        val source = data.stringOrNull("source") ?: json.stringOrNull("source")
        val contentValue = data?.opt("content")
            ?: data?.opt("message")
            ?: json.opt("message")
            ?: json.opt("data")
        val content = renderValue(contentValue)?.takeIf(String::isNotBlank) ?: raw
        val eventAtMs = parseTimestamp(data?.opt("timestamp") ?: json.opt("timestamp") ?: json.opt("time"))
        return VcpLogEvent(id, type, status, toolName, content, source, raw, receivedAtMs, eventAtMs)
    }

    private fun JSONObject?.stringOrNull(key: String): String? =
        this?.optString(key)?.takeIf(String::isNotBlank)

    private fun renderValue(value: Any?): String? = when (value) {
        null, JSONObject.NULL -> null
        is JSONObject -> value.toString(2)
        is org.json.JSONArray -> value.toString(2)
        else -> value.toString()
    }

    private fun parseTimestamp(value: Any?): Long? = when (value) {
        is Number -> value.toLong().let { if (it in 1..9_999_999_999L) it * 1_000 else it }
        is String -> value.toLongOrNull()?.let { if (it in 1..9_999_999_999L) it * 1_000 else it }
        else -> null
    }
}
