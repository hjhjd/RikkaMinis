package com.openminis.app.sandbox

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SessionMutexRegistryTest {
    @Test
    fun concurrentLookupReturnsSingleMutexIdentity() {
        val registry = SessionMutexRegistry()
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val futures = (1..64).map {
            pool.submit<Any> {
                start.await()
                registry.get("same-session")
            }
        }
        start.countDown()
        val mutexes = futures.map { it.get(5, TimeUnit.SECONDS) }
        pool.shutdownNow()

        mutexes.forEach { assertSame(mutexes.first(), it) }
    }

    @Test
    fun repeatedLookupPreservesSerialization() = runBlocking {
        val registry = SessionMutexRegistry()
        val first = registry.get("session")
        val second = registry.get("session")
        assertSame(first, second)

        first.lock()
        val waiter = async { second.lock(); second.unlock() }
        assertTrue(waiter.isActive)
        first.unlock()
        awaitAll(waiter)
        Unit
    }
}
