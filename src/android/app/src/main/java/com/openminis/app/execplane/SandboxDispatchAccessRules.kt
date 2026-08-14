package com.openminis.app.execplane

internal object SandboxDispatchAccessRules {
    fun isAllowed(exposed: Boolean, allowedIds: Set<String>?, sandboxId: String): Boolean =
        exposed && (allowedIds?.contains(sandboxId) != false)
}
