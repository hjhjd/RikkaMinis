package com.openminis.app.data.repository

import android.content.Context
import com.openminis.app.data.db.AgentIds
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Creates one physically-isolated memory repository per Agent. */
class AgentMemoryRepositoryFactory(private val context: Context) {
    private val repositories = ConcurrentHashMap<String, MemoryRepository>()
    private val root = File(context.filesDir, "minis-agents")
    private val legacy = File(context.filesDir, "minis-global/memory")
    private val prefs = context.getSharedPreferences("agent_migration", Context.MODE_PRIVATE)

    fun forAgent(agentId: String): MemoryRepository {
        val safeId = requireSafeId(agentId)
        if (safeId == AgentIds.DEFAULT) migrateLegacyDefaultIfNeeded()
        return repositories.getOrPut(safeId) {
            MemoryRepository(File(root, "$safeId/memory"))
        }
    }

    fun directory(agentId: String): File =
        File(root, "${requireSafeId(agentId)}/memory")

    @Synchronized
    private fun migrateLegacyDefaultIfNeeded() {
        if (prefs.getBoolean(KEY_DEFAULT_MEMORY_V1, false)) return
        val target = directory(AgentIds.DEFAULT)
        target.mkdirs()
        if (legacy.isDirectory) copyTreeWithoutOverwrite(legacy, target)
        check(prefs.edit().putBoolean(KEY_DEFAULT_MEMORY_V1, true).commit()) {
            "Failed to persist Agent memory migration marker"
        }
    }

    private fun copyTreeWithoutOverwrite(source: File, target: File) {
        source.listFiles()?.forEach { child ->
            val out = File(target, child.name)
            if (child.isDirectory) {
                out.mkdirs()
                copyTreeWithoutOverwrite(child, out)
            } else if (!out.exists()) {
                val temp = File(out.parentFile, ".${out.name}.migrating")
                child.inputStream().use { input -> temp.outputStream().use { input.copyTo(it) } }
                if (!temp.renameTo(out)) {
                    temp.delete()
                    error("Failed to migrate memory file ${child.name}")
                }
            }
        }
    }

    private fun requireSafeId(id: String): String {
        require(id.isNotBlank() && id.none { it == '/' || it == '\\' } && id != "." && id != "..") {
            "Invalid Agent id"
        }
        return id
    }

    private companion object {
        const val KEY_DEFAULT_MEMORY_V1 = "default_memory_copy_v1"
    }
}
