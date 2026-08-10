package com.openminis.app.agent

import android.content.Context
import com.openminis.app.data.db.AgentIds
import com.openminis.app.data.repository.AgentRepository

/**
 * One-way, non-destructive bootstrap from the legacy singleton SOUL.md into
 * the deterministic default Agent. The source file remains untouched so old
 * builds and rollback paths continue to work.
 */
object LegacyAgentMigration {
    private const val PREFS = "agent_migration"
    private const val KEY_SOUL_TO_DEFAULT_V1 = "soul_to_default_v1"

    suspend fun migrate(context: Context, agents: AgentRepository) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_SOUL_TO_DEFAULT_V1, false)) return

        val current = agents.get(AgentIds.DEFAULT) ?: return
        val soul = SoulStore.load(context)
        if (soul != null) {
            val migratedInstructions = buildString {
                soul.body.trim().takeIf { it.isNotEmpty() }?.let { append(it) }
                soul.metadata.style.trim().takeIf { it.isNotEmpty() }?.let { style ->
                    if (isNotEmpty()) append("\n\n")
                    append("回复风格：\n").append(style)
                }
            }
            agents.save(
                current.copy(
                    name = soul.metadata.name.trim().ifEmpty { current.name },
                    instructions = current.instructions.ifBlank { migratedInstructions },
                    preferredLanguage = soul.metadata.lang.trim().ifEmpty { "auto" },
                ),
            )
        }
        // commit(): the marker must be durable before this suspend function
        // returns; otherwise a process kill can repeat a partially-observed run.
        check(prefs.edit().putBoolean(KEY_SOUL_TO_DEFAULT_V1, true).commit()) {
            "Failed to persist Agent migration marker"
        }
    }
}
