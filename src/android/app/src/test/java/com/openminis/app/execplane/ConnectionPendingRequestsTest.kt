package com.openminis.app.execplane

import com.openminis.app.execplane.protocol.ExecPlaneErrorCode
import com.openminis.app.execplane.protocol.RpcError
import com.openminis.app.execplane.protocol.RpcResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionPendingRequestsTest {
    @Test
    fun responseOnlyCompletesOwningConnection() = runBlocking {
        val first = ConnectionPendingRequests()
        val second = ConnectionPendingRequests()
        val firstWaiter = first.add(7)
        val secondWaiter = second.add(7)
        val response = RpcResponse(7, true)

        assertTrue(first.complete(response))
        assertFalse(secondWaiter.isCompleted)
        assertEquals(response, firstWaiter.await())
        assertTrue(second.complete(response))
        assertEquals(response, secondWaiter.await())
    }

    @Test
    fun disconnectFailsAndClearsEveryPendingRequest() = runBlocking {
        val pending = ConnectionPendingRequests()
        val first = pending.add(1)
        val second = pending.add(2)
        val failure = RemoteChannelException(
            ExecPlaneErrorCode.CHANNEL_DISCONNECTED,
            "connection closed",
        )

        pending.failAll(failure)

        assertEquals(0, pending.size())
        assertTrue(first.isCompleted)
        assertTrue(second.isCompleted)
        assertEquals(failure, runCatching { first.await() }.exceptionOrNull())
        assertEquals(failure, runCatching { second.await() }.exceptionOrNull())
        val late = pending.add(3)
        assertEquals(failure, runCatching { late.await() }.exceptionOrNull())
        assertEquals(0, pending.size())
    }

    @Test
    fun remoteFailurePreservesNonChannelCode() {
        val response = RpcResponse(
            id = 9,
            ok = false,
            error = RpcError(ExecPlaneErrorCode.EXEC_TIMEOUT, "timed out"),
        )

        val failure = response.remoteFailure("fallback") as RemoteExecutionException

        assertEquals(ExecPlaneErrorCode.EXEC_TIMEOUT, failure.code)
        assertEquals("timed out", failure.message)
    }

    @Test
    fun channelErrorRemainsEligibleForDefaultRouteFallback() {
        val response = RpcResponse(
            id = 10,
            ok = false,
            error = RpcError(ExecPlaneErrorCode.CHANNEL_TIMEOUT, "remote channel timed out"),
        )

        val failure = response.remoteFailure("fallback") as RemoteChannelException
        assertEquals(ExecPlaneErrorCode.CHANNEL_TIMEOUT, failure.code)
    }
}