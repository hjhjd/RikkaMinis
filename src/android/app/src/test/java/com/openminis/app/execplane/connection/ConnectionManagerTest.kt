package com.openminis.app.execplane.connection

import com.openminis.app.execplane.protocol.ConnectionDirection
import com.openminis.app.execplane.protocol.EXECPLANE_PROTOCOL_VERSION
import com.openminis.app.execplane.protocol.ExecutorTrust
import com.openminis.app.execplane.protocol.RegisterParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionManagerTest {
    private class FakeConnection(
        override val id: String,
        override val direction: ConnectionDirection = ConnectionDirection.REVERSE,
    ) : ExecutorConnection {
        var closed = false
        override fun close(code: Int, reason: String) { closed = true }
    }

    private fun registration(name: String = "droidspaces", caps: Set<String> = setOf("exec")) =
        RegisterParams(EXECPLANE_PROTOCOL_VERSION, name, caps, trust = ExecutorTrust.LOCAL)

    @Test
    fun newerNamesakeReplacesAndClosesOldConnection() {
        var now = 10L
        val manager = ConnectionManager { now++ }
        val old = FakeConnection("old")
        val fresh = FakeConnection("fresh")

        manager.register(old, registration())
        manager.register(fresh, registration())

        assertTrue(old.closed)
        assertFalse(fresh.closed)
        assertEquals("fresh", manager.online("droidspaces")?.connectionId)
    }

    @Test
    fun staleDisconnectCannotRemoveReplacement() {
        val manager = ConnectionManager { 1L }
        manager.register(FakeConnection("old"), registration())
        manager.register(FakeConnection("fresh"), registration())

        assertFalse(manager.disconnect("droidspaces", "old"))
        assertEquals("fresh", manager.online("droidspaces")?.connectionId)
        assertTrue(manager.disconnect("droidspaces", "fresh"))
        assertNull(manager.online("droidspaces"))
        assertFalse(manager.snapshots.value.getValue("droidspaces").online)
    }

    @Test
    fun matchingRequiresEveryCapability() {
        val manager = ConnectionManager { 1L }
        manager.register(FakeConnection("build"), registration("build", setOf("exec", "android-build")))
        manager.register(FakeConnection("shell"), registration("shell", setOf("exec")))

        assertEquals(listOf("build"), manager.onlineMatching(setOf("exec", "android-build")).map { it.name })
    }
}
