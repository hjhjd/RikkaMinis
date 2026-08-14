package com.openminis.app.tools.registry

import java.util.concurrent.ConcurrentHashMap

internal class InvocationHandleRegistry {
    private val handles = ConcurrentHashMap<String, () -> Unit>()
    fun register(invocationId: String, cancel: () -> Unit) { require(handles.putIfAbsent(invocationId, cancel) == null) }
    fun complete(invocationId: String) { handles.remove(invocationId) }
    fun cancel(invocationId: String): Boolean = handles[invocationId]?.let { it(); true } ?: false
    fun size(): Int = handles.size
}
