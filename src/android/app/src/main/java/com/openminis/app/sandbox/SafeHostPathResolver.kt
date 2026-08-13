package com.openminis.app.sandbox

import java.io.File

/** Canonical containment for Linux-to-host path translation. */
internal object SafeHostPathResolver {
    private val safeSessionId = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")

    fun validateSessionId(sessionId: String): Boolean = safeSessionId.matches(sessionId)

    fun resolve(base: File, tail: String): File? {
        if ('\u0000' in tail) return null
        if (tail.startsWith('/') || tail.startsWith('\\')) return null
        val root = runCatching { base.canonicalFile }.getOrNull() ?: return null
        val candidate = runCatching { File(root, tail).canonicalFile }.getOrNull() ?: return null
        if (!contains(root, candidate)) return null
        return candidate
    }

    fun contains(root: File, candidate: File): Boolean {
        val r = root.path.trimEnd(File.separatorChar) + File.separator
        val c = candidate.path.trimEnd(File.separatorChar) + File.separator
        return c == r || c.startsWith(r)
    }
}
