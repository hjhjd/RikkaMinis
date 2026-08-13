package com.openminis.app.execplane

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SandboxConcurrencyLimiterTest {
    @Test
    fun limitsEachSandboxIndependently() = runBlocking {
        val limiter = SandboxConcurrencyLimiter { name -> if (name == "fast") 2 else 1 }
        val fastActive = AtomicInteger(0)
        val fastPeak = AtomicInteger(0)
        val slowActive = AtomicInteger(0)
        val slowPeak = AtomicInteger(0)
        val gate = CompletableDeferred<Unit>()

        val jobs = listOf(
            async { limiter.withPermit("FAST") { track(fastActive, fastPeak, gate) } },
            async { limiter.withPermit("fast") { track(fastActive, fastPeak, gate) } },
            async { limiter.withPermit("fast") { track(fastActive, fastPeak, gate) } },
            async { limiter.withPermit("slow") { track(slowActive, slowPeak, gate) } },
            async { limiter.withPermit("slow") { track(slowActive, slowPeak, gate) } },
        )
        delay(100)

        assertEquals(2, fastPeak.get())
        assertEquals(1, slowPeak.get())
        gate.complete(Unit)
        withTimeout(2_000) { jobs.awaitAll() }
        assertEquals(0, limiter.activeCount("fast"))
        assertEquals(0, limiter.activeCount("slow"))
    }

    @Test
    fun increasingLimitWakesQueuedCommands() = runBlocking {
        var limit = 1
        val limiter = SandboxConcurrencyLimiter { limit }
        val firstGate = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val first = async { limiter.withPermit("node") { firstGate.await() } }
        val second = async { limiter.withPermit("node") { secondEntered.complete(Unit) } }
        delay(50)
        assertTrue(!secondEntered.isCompleted)

        limit = 2
        // Completing one holder wakes queued calls, which re-read the new limit.
        firstGate.complete(Unit)
        first.await()
        second.await()
        assertTrue(secondEntered.isCompleted)
    }

    private suspend fun track(
        active: AtomicInteger,
        peak: AtomicInteger,
        gate: CompletableDeferred<Unit>,
    ) {
        val now = active.incrementAndGet()
        peak.updateAndGet { maxOf(it, now) }
        try {
            gate.await()
        } finally {
            active.decrementAndGet()
        }
    }
}
