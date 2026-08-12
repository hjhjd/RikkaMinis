package com.openminis.app.vcpinfo

import org.json.JSONArray
import org.json.JSONObject

enum class VcpInfoCategory { RAG, CHAIN, CHAT, MEMO, DREAM }

data class VcpInfoMessage(
    val id: String,
    val type: String,
    val category: VcpInfoCategory,
    val title: String,
    val subtitle: String? = null,
    val summary: String,
    val timestamp: String,
    val hasDetails: Boolean,
    val rawJson: String,
    val receivedAtMs: Long,
)

/** 将 VCPInfo 广播压缩成列表元数据，详情仍保留原始 JSON。 */
object VcpInfoMessageParser {
    fun parse(id: String, raw: String, receivedAtMs: Long = System.currentTimeMillis()): VcpInfoMessage? {
        val value = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val type = value.optString("type")
        val timestamp = value.optString("timestamp").ifBlank { java.time.Instant.ofEpochMilli(receivedAtMs).toString() }
        val meta = when {
            type == "AGENT_PRIVATE_CHAT_PREVIEW" -> Meta(
                VcpInfoCategory.CHAT,
                "Agent 私聊: ${value.string("agentName", "Unknown")}",
                value.string("sessionId").takeIf(String::isNotBlank)?.let { "Session: $it" },
                "💬 [USER]: ${value.string("query")} | [AI]: ${value.string("response")}", true,
            )
            type == "META_THINKING_CHAIN" -> {
                val activated = value.array("activatedGroups").strings().joinToString(",")
                Meta(VcpInfoCategory.CHAIN, "元思考链: ${value.string("chainName", "未知")}",
                    "阶段: ${value.long("totalStages")} | K序列: ${value.array("kSequence")}",
                    listOfNotNull(activated.takeIf(String::isNotBlank)?.let { "[激活分组: $it]" }, value.string("query")).joinToString(" "), true)
            }
            type == "AI_MEMO_RETRIEVAL" -> {
                val error = value.string("error")
                val chunks = value.long("tagMemoChunkCount")
                val summary = if (error.isNotBlank()) "[Error] $error" else
                    (if (chunks > 0) "[TagMemo 召回 $chunks Chunks] " else "") + value.string("extractedMemories")
                Meta(VcpInfoCategory.MEMO, "记忆回溯 (${value.long("diaryCount")})",
                    "模式: ${value.string("mode", "Unknown")} | 扫描: ${value.long("fileCount")}文件", summary, true)
            }
            type == "DailyNote" -> Meta(VcpInfoCategory.MEMO,
                "日记直接召回: ${value.string("dbName", "未知")}",
                "模式: ${value.string("action", "DirectRecall")}", value.string("message"), false)
            type == "AGENT_DREAM_SCHEDULE" -> Meta(VcpInfoCategory.DREAM, "梦境自动调度",
                "时间: ${value.long("currentHour")}点",
                "准备入梦: ${value.array("agents").strings().joinToString(",")} | ${value.string("message")}", false)
            type.startsWith("AGENT_DREAM_") -> dreamMeta(type, value)
            value.has("dbName") && value.has("results") -> ragMeta(value)
            else -> return null
        }
        return VcpInfoMessage(id, type, meta.category, meta.title, meta.subtitle,
            truncate(meta.summary), timestamp, meta.hasDetails, raw, receivedAtMs)
    }

    private fun dreamMeta(type: String, v: JSONObject): Meta {
        val title = "Agent梦境: ${v.string("agentName", "Unknown")}"
        return when (type) {
            "AGENT_DREAM_START" -> Meta(VcpInfoCategory.DREAM, title, "[入梦开始]", v.string("message"), false)
            "AGENT_DREAM_ASSOCIATIONS" -> Meta(VcpInfoCategory.DREAM, title,
                "[共鸣联想] 种子数: ${v.long("seedCount")} | 联想数: ${v.long("associationCount")}",
                "种子: ${v.long("seedCount")} (近:${v.long("recentSeedsCount")} | 中:${v.long("midSeedsCount")} | 深:${v.long("deepRecallsCount")}) ➜ 联想: ${v.long("associationCount")}", true)
            "AGENT_DREAM_NARRATIVE" -> {
                val narrative = v.string("narrative")
                Meta(VcpInfoCategory.DREAM, title, "[梦叙事] 字数: ${v.optLong("fullLength", narrative.length.toLong())}", narrative, true)
            }
            "AGENT_DREAM_OPERATIONS" -> {
                val ops = v.array("operations"); var merge = 0; var delete = 0; var insight = 0
                for (i in 0 until ops.length()) when (ops.optJSONObject(i)?.optString("type")) {
                    "merge" -> merge++; "delete" -> delete++; "insight" -> insight++
                }
                Meta(VcpInfoCategory.DREAM, title,
                    "[梦操作] 数量: ${v.long("operationCount")} | 日志: ${v.string("logFile", "None")}",
                    "[操作 ${ops.length()} 项] 待审核: ${merge}合并, ${delete}删除, ${insight}感悟", true)
            }
            "AGENT_DREAM_END" -> Meta(VcpInfoCategory.DREAM, title, "[出梦 (${v.string("status", "unknown")})]",
                v.string("message").ifBlank { v.string("error") }, false)
            else -> Meta(VcpInfoCategory.DREAM, title, type.removePrefix("AGENT_DREAM_"), v.string("message"), true)
        }
    }

    private fun ragMeta(v: JSONObject): Meta {
        val strategies = buildList {
            if (v.optBoolean("useTime")) add("Time")
            if (v.optBoolean("useRerankPlus")) add("Rerank+") else if (v.optBoolean("useRerank")) add("Rerank")
            if (v.optBoolean("useTagMemo")) add("TagMemo(${"%.2f".format(v.optDouble("tagWeight", 0.0))})")
            if (v.optBoolean("useGeodesicRerank")) add("GeoRerank")
            if (v.optBoolean("useAssociate")) add("Associate")
            if (v.optBoolean("useGroup")) add("Group")
        }
        val k = v.long("k")
        return Meta(VcpInfoCategory.RAG, "RAG知识库: ${v.string("dbName", "未知")}",
            if (strategies.isEmpty()) "K: $k" else "K: $k | [${strategies.joinToString(" | ")}]",
            "[召回 ${v.array("results").length()} 项] ${v.string("query")}", true)
    }

    private fun truncate(value: String): String = if (value.length <= 80) value else value.take(80) + "…"
    private data class Meta(val category: VcpInfoCategory, val title: String, val subtitle: String?, val summary: String, val hasDetails: Boolean)
    private fun JSONObject.string(key: String, fallback: String = "") = optString(key).ifBlank { fallback }
    private fun JSONObject.long(key: String) = optLong(key, 0)
    private fun JSONObject.array(key: String) = optJSONArray(key) ?: JSONArray()
    private fun JSONArray.strings() = (0 until length()).mapNotNull { optString(it).takeIf(String::isNotBlank) }
}
