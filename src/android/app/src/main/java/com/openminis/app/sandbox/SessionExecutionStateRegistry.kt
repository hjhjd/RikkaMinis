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
    fun clear(): List<SessionExecutionState> = states.values.toList().also { states.clear() }
}
