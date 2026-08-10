package com.openminis.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AgentDao {
    @Query("SELECT * FROM agents WHERE is_archived = 0 ORDER BY sort_order ASC, created_at ASC")
    fun observeActive(): Flow<List<AgentEntity>>

    @Query("SELECT * FROM agents ORDER BY sort_order ASC, created_at ASC")
    suspend fun listAll(): List<AgentEntity>

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

    @Query("UPDATE agents SET is_archived = :archived, updated_at = :updatedAt WHERE id = :id")
    suspend fun setArchived(id: String, archived: Int, updatedAt: Long)

    @Query("UPDATE agents SET sort_order = :sortOrder, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateSortOrder(id: String, sortOrder: Int, updatedAt: Long)

    @Query("UPDATE agents SET is_default = CASE WHEN id = :id THEN 1 ELSE 0 END, updated_at = :updatedAt")
    suspend fun setDefault(id: String, updatedAt: Long)

    @Query("SELECT COUNT(*) FROM sessions WHERE agent_id = :agentId")
    suspend fun sessionCount(agentId: String): Int

    @Query("DELETE FROM agents WHERE id = :id")
    suspend fun delete(id: String)
}
