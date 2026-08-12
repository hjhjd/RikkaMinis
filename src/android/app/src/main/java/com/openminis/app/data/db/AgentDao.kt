package com.openminis.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AgentDao {
    @Query("SELECT * FROM agents ORDER BY sort_order ASC, created_at ASC")
    fun observeAll(): Flow<List<AgentEntity>>

    @Query("SELECT * FROM agents ORDER BY sort_order ASC, created_at ASC")
    suspend fun listAll(): List<AgentEntity>

    @Query("SELECT * FROM agents WHERE id = :id LIMIT 1")
    fun observe(id: String): Flow<AgentEntity?>

    @Query("SELECT * FROM agents WHERE id = :id LIMIT 1")
    suspend fun get(id: String): AgentEntity?

    @Query("SELECT * FROM agents WHERE is_default = 1 LIMIT 1")
    suspend fun getDefault(): AgentEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(agent: AgentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(agent: AgentEntity)

    @Update
    suspend fun update(agent: AgentEntity)

    @Query("UPDATE agents SET sort_order = :sortOrder, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateSortOrder(id: String, sortOrder: Int, updatedAt: Long)

    @Transaction
    suspend fun updateSortOrders(ids: List<String>, updatedAt: Long) {
        ids.forEachIndexed { index, id -> updateSortOrder(id, index, updatedAt) }
    }

    @Query("UPDATE agents SET is_default = CASE WHEN id = :id THEN 1 ELSE 0 END, updated_at = :updatedAt")
    suspend fun setDefault(id: String, updatedAt: Long)

    @Query("SELECT id FROM sessions WHERE agent_id = :agentId")
    suspend fun sessionIds(agentId: String): List<String>

    @Query("DELETE FROM sessions WHERE agent_id = :agentId")
    suspend fun deleteSessions(agentId: String)

    @Query("DELETE FROM webapp_shortcuts WHERE scope_context IN (SELECT id FROM sessions WHERE agent_id = :agentId) OR source_session_id IN (SELECT id FROM sessions WHERE agent_id = :agentId)")
    suspend fun deleteSessionWebApps(agentId: String)

    @Query("DELETE FROM tarven_rules WHERE agent_id = :agentId")
    suspend fun deleteRules(agentId: String)

    @Query("DELETE FROM agents WHERE id = :id")
    suspend fun delete(id: String)

    @Transaction
    suspend fun deleteWithSessions(id: String, fallbackId: String?, updatedAt: Long) {
        if (fallbackId != null) setDefault(fallbackId, updatedAt)
        deleteSessionWebApps(id)
        deleteSessions(id)
        deleteRules(id)
        delete(id)
    }
}
