package com.openminis.app.data.repository

import com.openminis.app.data.db.AgentDao
import com.openminis.app.data.db.AgentEntity
import com.openminis.app.data.db.AgentIds
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/** Application-scoped source of truth for Agent metadata. */
class AgentRepository(private val dao: AgentDao) {
    fun observeAll(): Flow<List<AgentEntity>> = dao.observeAll()
    fun observe(id: String): Flow<AgentEntity?> = dao.observe(id)
    suspend fun listAll(): List<AgentEntity> = dao.listAll()
    suspend fun get(id: String): AgentEntity? = dao.get(id)

    suspend fun defaultAgent(): AgentEntity =
        dao.getDefault() ?: dao.get(AgentIds.DEFAULT)
        ?: error("Agent migration invariant broken: default Agent is missing")

    suspend fun create(
        name: String,
        instructions: String = "",
        preferredLanguage: String? = null,
        defaultModelBinding: String? = null,
    ): AgentEntity {
        val cleanName = name.trim()
        require(cleanName.isNotEmpty()) { "Agent name must not be blank" }
        val now = System.currentTimeMillis()
        val nextOrder = (dao.listAll().maxOfOrNull { it.sortOrder } ?: -1) + 1
        return AgentEntity(
            id = UUID.randomUUID().toString(),
            name = cleanName,
            instructions = instructions.trim(),
            preferredLanguage = preferredLanguage?.trim()?.takeIf { it.isNotEmpty() },
            defaultModelBinding = defaultModelBinding,
            createdAt = now,
            updatedAt = now,
            sortOrder = nextOrder,
        ).also { dao.insert(it) }
    }

    suspend fun importAgent(source: AgentEntity): AgentEntity {
        val existing = dao.get(source.id)
        if (source.id == AgentIds.DEFAULT && existing != null) {
            val merged = existing.copy(
                name = source.name,
                instructions = source.instructions,
                preferredLanguage = source.preferredLanguage,
                defaultModelBinding = source.defaultModelBinding,
                toolPromptEnabled = source.toolPromptEnabled,
                customToolPromptEnabled = source.customToolPromptEnabled,
                customToolPrompt = source.customToolPrompt,
                updatedAt = System.currentTimeMillis(),
            )
            dao.update(merged)
            return merged
        }
        val target = if (existing == null) source else source.copy(id = UUID.randomUUID().toString())
        val imported = target.copy(isDefault = 0, updatedAt = System.currentTimeMillis())
        dao.upsert(imported)
        return imported
    }

    suspend fun save(agent: AgentEntity) {
        require(agent.name.isNotBlank()) { "Agent name must not be blank" }
        dao.update(agent.copy(name = agent.name.trim(), updatedAt = System.currentTimeMillis()))
    }

    suspend fun setDefault(id: String) {
        requireNotNull(dao.get(id)) { "Agent not found: $id" }
        dao.setDefault(id, System.currentTimeMillis())
    }

    suspend fun deleteWithSessions(id: String): List<String> {
        val target = requireNotNull(dao.get(id)) { "Agent not found: $id" }
        val fallbackId = if (target.isDefault != 0) {
            dao.listAll().firstOrNull { it.id != id }?.id
                ?: error("At least one Agent must remain")
        } else {
            null
        }
        val sessionIds = dao.sessionIds(id)
        dao.deleteWithSessions(id, fallbackId, System.currentTimeMillis())
        return sessionIds
    }

    suspend fun sessionIds(id: String): List<String> = dao.sessionIds(id)
}
