package com.openminis.app.vcpinfo

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 进程内有界消息中心；列表和详情同源，避免 UI 保存第二份 payload。 */
class VcpInfoStore(private val capacity: Int = 500, private val maxPayloadBytes: Int = 512 * 1024) {
    private val lock = Any()
    private val counter = AtomicLong(0)
    private val mutableMessages = MutableStateFlow<List<VcpInfoMessage>>(emptyList())
    val messages: StateFlow<List<VcpInfoMessage>> = mutableMessages.asStateFlow()
    private val mutableUnread = MutableStateFlow(0)
    val unreadCount: StateFlow<Int> = mutableUnread.asStateFlow()
    @Volatile private var observing = false

    fun accept(raw: String): VcpInfoMessage? {
        if (raw.toByteArray(Charsets.UTF_8).size > maxPayloadBytes) return null
        val now = System.currentTimeMillis()
        val id = "vcp_info_${now}_${counter.getAndIncrement()}"
        val message = VcpInfoMessageParser.parse(id, raw, now) ?: return null
        synchronized(lock) {
            mutableMessages.value = (listOf(message) + mutableMessages.value).take(capacity)
            if (!observing) mutableUnread.value = (mutableUnread.value + 1).coerceAtMost(capacity)
        }
        return message
    }

    fun setObserving(value: Boolean) = synchronized(lock) {
        observing = value
        if (value) mutableUnread.value = 0
    }

    fun clear() = synchronized(lock) {
        mutableMessages.value = emptyList()
        mutableUnread.value = 0
    }

    fun payload(id: String): String? = mutableMessages.value.firstOrNull { it.id == id }?.rawJson
}
