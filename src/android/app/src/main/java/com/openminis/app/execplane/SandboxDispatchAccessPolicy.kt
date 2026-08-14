package com.openminis.app.execplane

import android.content.Context

/** Persisted per-Agent exposure and stable sandbox-ID allowlist. */
class SandboxDispatchAccessPolicy(context: Context) {
    private val prefs = context.getSharedPreferences("sandbox-dispatch-access", Context.MODE_PRIVATE)

    fun isExposed(agentId: String): Boolean = prefs.getBoolean("$agentId.exposed", true)

    fun allowedSandboxIds(agentId: String): Set<String>? =
        prefs.getStringSet("$agentId.allowed", null)?.toSet()

    fun isAllowed(agentId: String, sandboxId: String): Boolean =
        SandboxDispatchAccessRules.isAllowed(isExposed(agentId), allowedSandboxIds(agentId), sandboxId)

    fun update(agentId: String, exposed: Boolean, allowedSandboxIds: Set<String>?) {
        require(agentId.isNotBlank())
        prefs.edit().putBoolean("$agentId.exposed", exposed).apply {
            if (allowedSandboxIds == null) remove("$agentId.allowed")
            else putStringSet("$agentId.allowed", allowedSandboxIds.filter(String::isNotBlank).toSet())
        }.apply()
    }
}
