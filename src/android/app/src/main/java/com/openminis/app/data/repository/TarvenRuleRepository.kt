package com.openminis.app.data.repository

import com.openminis.app.data.db.TarvenRuleDao
import com.openminis.app.data.db.TarvenRuleEntity
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class TarvenRuleRepository(private val dao: TarvenRuleDao) {
    fun observeAll(): Flow<List<TarvenRuleEntity>> = dao.observeAll()
    suspend fun listAll(): List<TarvenRuleEntity> = dao.listAll()
    suspend fun get(id: String): TarvenRuleEntity? = dao.get(id)
    suspend fun activeForAgent(agentId: String): List<TarvenRuleEntity> = dao.activeForAgent(agentId)

    suspend fun save(rule: TarvenRuleEntity): TarvenRuleEntity {
        require(rule.name.isNotBlank()) { "规则名称不能为空" }
        require(rule.content.isNotBlank()) { "规则内容不能为空" }
        val now = System.currentTimeMillis()
        val normalized = rule.copy(
            id = rule.id.ifBlank { "rule_${UUID.randomUUID()}" },
            name = rule.name.trim(),
            content = rule.content.trim(),
            agentId = rule.agentId?.takeIf { rule.scope == "agent" },
            depth = rule.depth?.coerceIn(0, 20),
            createdAt = rule.createdAt.takeIf { it > 0 } ?: now,
            updatedAt = now,
        )
        dao.upsert(normalized)
        return normalized
    }

    suspend fun importRule(rule: TarvenRuleEntity) = dao.upsert(rule)
    suspend fun delete(id: String) = dao.deleteById(id)
    suspend fun setEnabled(id: String, enabled: Boolean) =
        dao.setEnabled(id, if (enabled) 1 else 0, System.currentTimeMillis())
    suspend fun reorder(ruleType: String, orderedIds: List<String>) = dao.reorder(ruleType, orderedIds)
}
