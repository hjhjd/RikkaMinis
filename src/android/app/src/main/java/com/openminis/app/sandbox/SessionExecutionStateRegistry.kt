package com.openminis.app.sandbox

import java.util.concurrent.ConcurrentHashMap

internal class SessionExecutionStateRegistry {
    private val states = ConcurrentHashMap<String, SessionExecutionState>()
    fun get(sessionId: String): SessionExecutionState =
        states.computeIfAbsent(sessionId) { SessionExecutionState() }
    fun existing(sessionId: String): SessionExecutionState? = states[sessionId]
    fun entries(): List<Pair<String, SessionExecutionState>> =
        states.entries.map { it.key to it.value }
    fun values(): List<SessionExecutionState> = states.values.toList()
    fun remove(sessionId: String, expected: SessionExecutionState): Boolean =
        states.remove(sessionId, expected)
    fun size(): Int = states.size
    fun clear(): List<SessionExecutionState> = states.values.toList().also { states.clear() }
}
