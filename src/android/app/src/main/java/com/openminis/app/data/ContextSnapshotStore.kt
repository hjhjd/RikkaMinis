package com.openminis.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

data class ContextSnapshotSummary(val fileName: String, val createdAt: Long)
data class ContextSnapshotFloor(val role: String, val content: String)
data class ContextSnapshot(val createdAt: Long, val floors: List<ContextSnapshotFloor>)

/**
 * 将线路请求映射为界面分区。顶层工具定义属于协议元数据，
 * 不进入上下文楼层；展示从 SYSTEM 开始，再依次显示对话与请求配置。
 */
internal object FinalRequestFloorParser {
    fun parse(provider: String, request: JSONObject): JSONArray {
        val floors = JSONArray()
        val consumed = linkedSetOf<String>()
        fun add(role: String, value: Any?) {
            floors.put(JSONObject().put("role", role).put("content", JsonReadableText.render(value)))
        }
        fun addSystem(key: String) {
            if (request.has(key)) {
                consumed += key
                add("SYSTEM", JSONObject().put(key, request.get(key)))
            }
        }
        addSystem("system")
        addSystem("instructions")
        addSystem("systemInstruction")

        // 顶层 `tools` 是协议元数据，不属于模型对话楼层，也不并入 REQUEST。
        if (request.has("tools")) consumed += "tools"

        val sequenceKey = listOf("messages", "input", "contents").firstOrNull { request.optJSONArray(it) != null }
        sequenceKey?.let { key ->
            consumed += key
            val rows = request.getJSONArray(key)
            for (i in 0 until rows.length()) {
                val value = rows.get(i)
                val role = requestRole(value)
                // 工具请求与工具结果属于执行协议，不进入上下文快照楼层。
                if (role == "TOOL REQUEST" || role == "TOOL RESULT") continue
                add(role, JSONObject().put("source", "$key[$i]").put("value", value))
            }
        }

        val config = JSONObject().put("provider", provider)
        val keys = request.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key !in consumed) config.put(key, request.get(key))
        }
        add("REQUEST", config)
        return floors
    }

    private fun requestRole(value: Any?): String {
        val obj = value as? JSONObject ?: return "REQUEST"
        return when (obj.optString("role").lowercase()) {
            "system", "developer" -> "SYSTEM"
            "user" -> "USER"
            "assistant", "model" -> "AI"
            "tool" -> "TOOL RESULT"
            else -> when (obj.optString("type").lowercase()) {
                "function_call", "tool_use" -> "TOOL REQUEST"
                "function_call_output", "tool_result" -> "TOOL RESULT"
                else -> "REQUEST"
            }
        }
    }
}

class ContextSnapshotStore(context: Context) {
    private val root = File(context.filesDir, "context_snapshots")

    suspend fun saveFinalRequest(sessionId: String, provider: String, finalBody: String) = withContext(Dispatchers.IO) {
        val request = JSONObject(finalBody)
        val now = System.currentTimeMillis()
        val floors = FinalRequestFloorParser.parse(provider, request)
        val body = JSONObject()
            .put("createdAt", now)
            .put("provider", provider)
            .put("floors", floors)
            .toString()
        val dir = sessionDir(sessionId).apply { mkdirs() }
        val target = File(dir, SNAPSHOT_FILE)
        val temporary = File(dir, ".$SNAPSHOT_FILE.tmp")
        GZIPOutputStream(temporary.outputStream().buffered()).bufferedWriter(Charsets.UTF_8).use { it.write(body) }
        if (!temporary.renameTo(target)) {
            temporary.copyTo(target, overwrite = true)
            temporary.delete()
        }
        dir.listFiles { file -> file.isFile && file.name.endsWith(".json.gz") && file.name != SNAPSHOT_FILE }
            .orEmpty().forEach(File::delete)
    }

    suspend fun list(sessionId: String): List<ContextSnapshotSummary> = withContext(Dispatchers.IO) {
        val dir = sessionDir(sessionId)
        val fixed = File(dir, SNAPSHOT_FILE)
        if (!fixed.isFile) {
            val legacy = dir.listFiles { file -> file.isFile && file.name.endsWith(".json.gz") }
                .orEmpty().maxByOrNull { readCreatedAt(it) ?: it.lastModified() }
                ?: return@withContext emptyList()
            if (!legacy.renameTo(fixed)) legacy.copyTo(fixed, overwrite = true)
            dir.listFiles { file -> file.isFile && file.name.endsWith(".json.gz") && file.name != SNAPSHOT_FILE }
                .orEmpty().forEach(File::delete)
        }
        listOf(ContextSnapshotSummary(fixed.name, readCreatedAt(fixed) ?: fixed.lastModified()))
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) { root.deleteRecursively() }

    suspend fun load(sessionId: String, fileName: String): ContextSnapshot? = withContext(Dispatchers.IO) {
        val file = File(sessionDir(sessionId), File(fileName).name)
        if (!file.isFile) return@withContext null
        runCatching {
            val json = readJson(file)
            val array = json.getJSONArray("floors")
            ContextSnapshot(json.getLong("createdAt"), List(array.length()) { i ->
                val item = array.getJSONObject(i)
                ContextSnapshotFloor(item.getString("role"), item.getString("content"))
            })
        }.getOrNull()
    }

    private fun readJson(file: File): JSONObject =
        GZIPInputStream(file.inputStream().buffered()).bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }

    private fun readCreatedAt(file: File): Long? = runCatching {
        readJson(file).optLong("createdAt").takeIf { it > 0L }
    }.getOrNull()

    private fun sessionDir(sessionId: String) = File(root, sessionId.replace(Regex("[^A-Za-z0-9._-]"), "_"))

    private companion object { const val SNAPSHOT_FILE = "snapshot.json.gz" }
}
