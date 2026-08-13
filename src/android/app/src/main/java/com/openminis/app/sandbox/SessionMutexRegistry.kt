package com.openminis.app.sandbox

import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-lifetime mutex identity for PRoot sessions.
 *
 * Shell recycling must never replace a session's mutex: a coroutine may have
 * already captured the old instance while another command is being queued.
 */
internal class SessionMutexRegistry {
    private val mutexes = ConcurrentHashMap<String, Mutex>()

    fun get(sessionId: String): Mutex =
        mutexes.computeIfAbsent(sessionId) { Mutex() }
}
