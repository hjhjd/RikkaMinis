package com.openminis.app.data

import android.content.Context
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
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

/** Durable, compressed snapshots of the exact context passed to a provider. */
class ContextSnapshotStore(context: Context) {
    private val root = File(context.filesDir, "context_snapshots")

    suspend fun save(sessionId: String, systemPrompt: String?, messages: List<LLMMessage>) = withContext(Dispatchers.IO) {
        val dir = sessionDir(sessionId).apply { mkdirs() }
        val now = System.currentTimeMillis()
        val floors = JSONArray()
        systemPrompt?.let { floors.put(floor("SYSTEM", it)) }
        messages.forEach { message -> snapshotFloors(message).forEach(floors::put) }
        val body = JSONObject().put("createdAt", now).put("floors", floors).toString()
        val target = File(dir, SNAPSHOT_FILE)
        val temporary = File(dir, ".$SNAPSHOT_FILE.tmp")
        GZIPOutputStream(temporary.outputStream().buffered()).bufferedWriter(Charsets.UTF_8).use { it.write(body) }
        if (!temporary.renameTo(target)) {
            temporary.copyTo(target, overwrite = true)
            temporary.delete()
        }
        // Remove legacy multi-snapshot files after the fixed snapshot has been
        // committed successfully. One session now owns exactly one snapshot.
        dir.listFiles { file -> file.isFile && file.name.endsWith(".json.gz") && file.name != SNAPSHOT_FILE }
            .orEmpty().forEach(File::delete)
    }

    suspend fun list(sessionId: String): List<ContextSnapshotSummary> = withContext(Dispatchers.IO) {
        val dir = sessionDir(sessionId)
        val fixed = File(dir, SNAPSHOT_FILE)
        if (!fixed.isFile) {
            // Upgrade snapshots created by the first implementation: preserve
            // only the newest one and move it to the fixed per-session name.
            val legacy = dir.listFiles { file -> file.isFile && file.name.endsWith(".json.gz") }
                .orEmpty().maxByOrNull { readCreatedAt(it) ?: it.lastModified() }
                ?: return@withContext emptyList()
            if (!legacy.renameTo(fixed)) legacy.copyTo(fixed, overwrite = true)
            dir.listFiles { file -> file.isFile && file.name.endsWith(".json.gz") && file.name != SNAPSHOT_FILE }
                .orEmpty().forEach(File::delete)
        }
        val createdAt = readCreatedAt(fixed) ?: fixed.lastModified()
        listOf(ContextSnapshotSummary(fixed.name, createdAt))
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        root.deleteRecursively()
    }

    suspend fun load(sessionId: String, fileName: String): ContextSnapshot? = withContext(Dispatchers.IO) {
        val file = File(sessionDir(sessionId), File(fileName).name)
        if (!file.isFile) return@withContext null
        runCatching {
            val json = GZIPInputStream(file.inputStream().buffered()).bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
            val array = json.getJSONArray("floors")
            ContextSnapshot(json.getLong("createdAt"), List(array.length()) { i ->
                val item = array.getJSONObject(i)
                ContextSnapshotFloor(item.getString("role"), item.getString("content"))
            })
        }.getOrNull()
    }

    private fun readCreatedAt(file: File): Long? = runCatching {
        GZIPInputStream(file.inputStream().buffered()).bufferedReader(Charsets.UTF_8).use {
            JSONObject(it.readText()).optLong("createdAt").takeIf { value -> value > 0L }
        }
    }.getOrNull()

    private fun sessionDir(sessionId: String) = File(root, sessionId.replace(Regex("[^A-Za-z0-9._-]"), "_"))
    private fun floor(role: String, content: String) = JSONObject().put("role", role).put("content", content)

    private fun snapshotFloors(message: LLMMessage): List<JSONObject> {
        val role = if (message.role == LLMMessage.Role.USER) "USER" else "AI"
        val result = mutableListOf<JSONObject>()
        val textParts = message.contentParts.filterIsInstance<AgentContentPart.Text>()
        val ordinary = buildString {
            // Structured messages carry the same user text in content and in a
            // Text part. Providers serialize Text parts, so prefer them instead
            // of displaying the compatibility content field a second time.
            if (textParts.isEmpty()) append(message.content)
            textParts.forEach {
                if (isNotEmpty() && last() != '\n') append('\n')
                append(it.text)
            }
            message.contentParts.forEach { part ->
                when (part) {
                    is AgentContentPart.FileData -> {
                        val alreadyDescribed = textParts.any { text -> text.text.contains(part.linuxPath) }
                        if (!alreadyDescribed) append("\n[FILE ${part.fileName}; ${part.mimeType}; ${part.size} bytes; ${part.linuxPath}]")
                    }
                    is AgentContentPart.ImageData -> {
                        val alreadyDescribed = part.linuxPath != null &&
                            textParts.any { text -> text.text.contains(part.linuxPath) }
                        if (!alreadyDescribed) append("\n[IMAGE ${part.mimeType}; ${part.data.size} bytes${part.linuxPath?.let { "; $it" } ?: ""}]")
                    }
                    else -> Unit
                }
            }
            // New messages mirror images in both contentParts and imageParts
            // for provider compatibility. Show the legacy list only when no
            // structured ImageData exists, otherwise the attachment appears twice.
            if (message.contentParts.none { it is AgentContentPart.ImageData }) {
                message.imageParts.forEach { append("\n[IMAGE ${it.mimeType}; ${it.data.size} bytes${it.linuxPath?.let { p -> "; $p" } ?: ""}]") }
            }
            message.audioParts.forEach { append("\n[AUDIO ${it.format}; base64 ${it.base64Data.length} chars]") }
            message.reasoningContent?.let { append("\n[REASONING]\n").append(it) }
        }.trimStart('\n')
        if (ordinary.isNotEmpty() || message.contentParts.none { it is AgentContentPart.ToolUse || it is AgentContentPart.ToolResult }) {
            result += floor(role, ordinary)
        }
        message.contentParts.forEach { part ->
            when (part) {
                is AgentContentPart.ToolUse -> result += floor("TOOL", "USE ${part.name} #${part.id}\n${part.input.toString(2)}")
                is AgentContentPart.ToolResult -> result += floor(
                    "TOOL",
                    "RESULT ${part.name} #${part.id}${if (part.isError) " ERROR" else ""}\n${part.content}",
                )
                else -> Unit
            }
        }
        return result
    }

    private companion object {
        const val SNAPSHOT_FILE = "snapshot.json.gz"
    }
}
