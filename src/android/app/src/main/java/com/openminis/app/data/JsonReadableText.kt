package com.openminis.app.data

import org.json.JSONArray
import org.json.JSONObject

/** Turns provider request JSON into readable labelled text for snapshot floors. */
object JsonReadableText {
    fun render(value: Any?): String = renderValue(value, 0).trimEnd()

    private fun renderValue(value: Any?, depth: Int): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> renderObject(value, depth)
        is JSONArray -> renderArray(value, depth)
        is Boolean, is Number -> value.toString()
        else -> value.toString()
    }

    private fun renderObject(value: JSONObject, depth: Int): String {
        // Snapshot message wrapper: keep its origin, but show the actual message
        // as fields rather than dumping the wrapper's JSON source.
        if (value.has("source") && value.has("value") && value.length() == 2) {
            return "来源：${value.optString("source")}\n${renderValue(value.get("value"), depth)}"
        }
        val lines = mutableListOf<String>()
        val keys = value.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val child = value.get(key)
            // The floor capsule already communicates the role.
            if (key == "role") continue
            val label = label(key)
            when (child) {
                is JSONObject -> lines += "$label：\n${indent(renderValue(child, depth + 1))}"
                is JSONArray -> lines += "$label：\n${indent(renderValue(child, depth + 1))}"
                JSONObject.NULL -> lines += "$label：null"
                else -> lines += "$label：${child}"
            }
        }
        return lines.joinToString("\n")
    }

    private fun renderArray(value: JSONArray, depth: Int): String {
        if (value.length() == 0) return "（空）"
        return (0 until value.length()).joinToString("\n") { index ->
            val child = value.get(index)
            val rendered = renderValue(child, depth + 1)
            if (child is JSONObject || child is JSONArray) {
                "[${index + 1}]\n${indent(rendered)}"
            } else {
                "• $rendered"
            }
        }
    }

    private fun indent(text: String): String = text.lineSequence().joinToString("\n") { "  $it" }

    private fun label(key: String): String = when (key) {
        "source" -> "来源"
        "value" -> "内容"
        "content" -> "内容"
        "text" -> "文本"
        "type" -> "类型"
        "name" -> "名称"
        "description" -> "说明"
        "arguments", "input" -> "参数"
        "output" -> "输出"
        "tool_call_id", "call_id" -> "工具调用 ID"
        "id" -> "ID"
        "model" -> "模型"
        "provider" -> "服务商"
        "tools" -> "工具定义"
        "required" -> "必填字段"
        "properties" -> "字段"
        "stream" -> "流式输出"
        "temperature" -> "温度"
        "max_tokens", "max_completion_tokens", "maxOutputTokens" -> "最大输出 Token"
        "system", "instructions", "systemInstruction" -> "系统提示词"
        else -> key.replace('_', ' ')
    }
}
