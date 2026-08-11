package com.openminis.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "tarven_rules",
    indices = [
        Index(value = ["rule_type", "is_enabled", "sort_order"]),
        Index(value = ["agent_id"]),
    ],
)
data class TarvenRuleEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "rule_type") val ruleType: String,
    @ColumnInfo(name = "is_enabled") val isEnabled: Int = 1,
    val content: String,
    val scope: String = "global",
    @ColumnInfo(name = "agent_id") val agentId: String? = null,
    val wrap: Int = 1,
    val role: String? = null,
    val depth: Int? = null,
    val position: String? = null,
    @ColumnInfo(name = "sort_order") val sortOrder: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

object TarvenRuleType {
    const val SYSTEM_SUFFIX = "system_suffix"
    const val USER_SUFFIX = "user_suffix"
    const val CONTEXT_INJECT = "context_inject"
    val ALL = listOf(SYSTEM_SUFFIX, USER_SUFFIX, CONTEXT_INJECT)
}

object TarvenScope {
    const val GLOBAL = "global"
    const val AGENT = "agent"
}
