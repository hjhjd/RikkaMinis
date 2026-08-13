package com.openminis.app.vcplog

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 进程内有界事件中心；不把远端 VCPLog 混入本地 AppLogger 文件。 */
class VcpLogStore(
    private val capacity: Int = 500,
    private val maxPayloadBytes: Int = 512 * 1024,
) {
    private val lock = Any()
    private val counter = AtomicLong(0)
    private val mutableEvents = MutableStateFlow<List<VcpLogEvent>>(emptyList())
    val events: StateFlow<List<VcpLogEvent>> = mutableEvents.asStateFlow()
    private val mutableUnread = MutableStateFlow(0)
    val unreadCount: StateFlow<Int> = mutableUnread.asStateFlow()
    private var observerCount = 0

    init {
        require(capacity > 0) { "capacity must be positive" }
        require(maxPayloadBytes > 0) { "maxPayloadBytes must be positive" }
    }

    fun accept(raw: String): VcpLogEvent? {
        if (raw.toByteArray(Charsets.UTF_8).size > maxPayloadBytes) return null
        val now = System.currentTimeMillis()
        val event = VcpLogEventParser.parse("vcp_log_${now}_${counter.getAndIncrement()}", raw, now)
        synchronized(lock) {
            mutableEvents.value = (listOf(event) + mutableEvents.value).take(capacity)
            if (observerCount == 0) mutableUnread.value = (mutableUnread.value + 1).coerceAtMost(capacity)
        }
        return event
    }

    fun beginObserving() = synchronized(lock) {
        observerCount++
        mutableUnread.value = 0
    }

    fun endObserving() = synchronized(lock) {
        observerCount = (observerCount - 1).coerceAtLeast(0)
    }

    fun clear() = synchronized(lock) {
        mutableEvents.value = emptyList()
        mutableUnread.value = 0
    }

    fun payload(id: String): String? = mutableEvents.value.firstOrNull { it.id == id }?.rawJson
}
