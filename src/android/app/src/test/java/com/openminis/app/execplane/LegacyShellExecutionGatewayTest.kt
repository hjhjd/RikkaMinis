package com.openminis.app.execplane

import com.openminis.app.sandbox.ExecutionCoordinator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class LegacyShellExecutionGatewayTest {
    @Test
    fun explicitRemoteRouteUsesCompatibilityExecutor() = runBlocking {
        LegacyShellExecutionGateway.installRemoteExecutor { _, command, _, _, sandbox ->
            ExecutionCoordinator.CommandResult(
                output = "$sandbox:$command",
                exitCode = 0,
                durationMs = 1,
                sandboxName = sandbox ?: "remote",
            )
        }

        val result = LegacyShellExecutionGateway.execute(
            sessionId = "session",
            command = "opaque-to-gateway",
            sandbox = "legacy-ws",
        )

        assertEquals("legacy-ws:opaque-to-gateway", result.output)
        assertEquals("legacy-ws", result.sandboxName)
    }
}
