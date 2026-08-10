package com.openminis.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** Stable identifiers shared by migrations, repositories, and legacy callers. */
object AgentIds {
    const val DEFAULT = "default"
}

/**
 * Persistent configuration root for one user-defined Agent.
 *
 * Large mutable resources (avatar and memory files) live on disk; this row
 * stores only their stable relative references and the prompt/model metadata.
 */
@Entity(tableName = "agents")
data class AgentEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "avatar_path") val avatarPath: String? = null,
    val instructions: String = "",
    @ColumnInfo(name = "preferred_language") val preferredLanguage: String? = null,
    @ColumnInfo(name = "default_model_binding") val defaultModelBinding: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "sort_order") val sortOrder: Int = 0,
    @ColumnInfo(name = "is_default") val isDefault: Int = 0,
    @ColumnInfo(name = "is_archived") val isArchived: Int = 0,
)
