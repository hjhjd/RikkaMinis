package com.openminis.app.sandbox

import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SessionExecutionStateRegistryTest {
    @Test
    fun concurrentLookupReturnsSingleStateAndMutexIdentity() {
        val registry = SessionExecutionStateRegistry()
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val futures = (1..64).map {
            pool.submit<SessionExecutionState> {
                start.await()
                registry.get("same-session")
            }
        }
        start.countDown()
        val states = futures.map { it.get(5, TimeUnit.SECONDS) }
        pool.shutdownNow()

        states.forEach {
            assertSame(states.first(), it)
            assertSame(states.first().mutex, it.mutex)
        }
    }

    @Test
    fun stateAggregatesExecutionAndRecycleLifecycle() {
        val state = SessionExecutionStateRegistry().get("session")
        state.activeExecution = ActiveExecutionHandle { }
        state.lastActivityMs = 42L
        state.recycleRequested = true
        state.injectedEnvKeys = setOf("TZ")

        assertTrue(state.isExecuting)
        assertTrue(state.recycleRequested)
        assertTrue(state.injectedEnvKeys.contains("TZ"))
    }
}
