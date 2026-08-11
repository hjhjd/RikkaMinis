package com.openminis.app.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface TarvenRuleDao {
    @Query("SELECT * FROM tarven_rules ORDER BY rule_type ASC, sort_order ASC, created_at ASC")
    fun observeAll(): Flow<List<TarvenRuleEntity>>

    @Query("SELECT * FROM tarven_rules ORDER BY rule_type ASC, sort_order ASC, created_at ASC")
    suspend fun listAll(): List<TarvenRuleEntity>

    @Query("SELECT * FROM tarven_rules WHERE id = :id LIMIT 1")
    suspend fun get(id: String): TarvenRuleEntity?

    @Query("SELECT * FROM tarven_rules WHERE is_enabled = 1 AND (scope = 'global' OR (scope = 'agent' AND agent_id = :agentId)) ORDER BY rule_type ASC, sort_order ASC, created_at ASC")
    suspend fun activeForAgent(agentId: String): List<TarvenRuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: TarvenRuleEntity)

    @Delete
    suspend fun delete(rule: TarvenRuleEntity)

    @Query("DELETE FROM tarven_rules WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE tarven_rules SET is_enabled = :enabled, updated_at = :updatedAt WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Int, updatedAt: Long)

    @Query("UPDATE tarven_rules SET sort_order = :order, updated_at = :updatedAt WHERE id = :id AND rule_type = :ruleType")
    suspend fun setOrder(id: String, ruleType: String, order: Int, updatedAt: Long)

    @Transaction
    suspend fun reorder(ruleType: String, orderedIds: List<String>) {
        val now = System.currentTimeMillis()
        orderedIds.forEachIndexed { index, id -> setOrder(id, ruleType, index, now) }
    }
}
