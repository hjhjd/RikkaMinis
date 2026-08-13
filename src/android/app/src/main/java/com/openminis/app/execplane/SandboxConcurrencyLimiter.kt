package com.openminis.app.execplane

import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Per-executor App-side concurrency gate. Limits may change while requests are queued. */
class SandboxConcurrencyLimiter(
    private val limitProvider: (String) -> Int,
) {
    private val mutex = Mutex()
    private val active = mutableMapOf<String, Int>()
    private val waiters = mutableMapOf<String, MutableSet<CompletableDeferred<Unit>>>()

    suspend fun <T> withPermit(name: String, block: suspend () -> T): T {
        val key = normalize(name)
        acquire(key)
        return try {
            block()
        } finally {
            release(key)
        }
    }

    private suspend fun acquire(key: String) {
        while (true) {
            var waiter: CompletableDeferred<Unit>? = null
            val acquired = mutex.withLock {
                val limit = limitProvider(key).coerceIn(MIN_LIMIT, MAX_LIMIT)
                val count = active[key] ?: 0
                if (count < limit) {
                    active[key] = count + 1
                    true
                } else {
                    waiter = CompletableDeferred<Unit>().also {
                        waiters.getOrPut(key) { linkedSetOf() }.add(it)
                    }
                    false
                }
            }
            if (acquired) return
            val pending = waiter ?: continue
            try {
                pending.await()
            } finally {
                mutex.withLock {
                    waiters[key]?.remove(pending)
                    if (waiters[key].isNullOrEmpty()) waiters.remove(key)
                }
            }
        }
    }

    private suspend fun release(key: String) {
        val pending = mutex.withLock {
            val count = (active[key] ?: 1) - 1
            if (count <= 0) active.remove(key) else active[key] = count
            waiters[key]?.toList().orEmpty()
        }
        // Wake all because the configured limit may have increased by more than one.
        pending.forEach { it.complete(Unit) }
    }

    internal suspend fun activeCount(name: String): Int = mutex.withLock {
        active[normalize(name)] ?: 0
    }

    companion object {
        const val DEFAULT_LIMIT = 4
        const val MIN_LIMIT = 1
        const val MAX_LIMIT = 256
        fun normalize(name: String): String = name.trim().lowercase(Locale.ROOT)
    }
}
