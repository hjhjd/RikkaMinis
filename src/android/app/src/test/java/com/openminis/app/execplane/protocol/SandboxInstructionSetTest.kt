package com.openminis.app.execplane.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SandboxInstructionSetTest {
    private val limits = ExecutorLimits(1, 1, 1, 1, 1, 1_000)

    @Test
    fun `capabilities decodes optional instruction set`() {
        val decoded = ExecPlaneJson.codec.decodeFromString<CapabilitiesResult>(
            """{"protocol":"0.2","serverId":"stable","name":"box","caps":["dispatch"],"limits":{"maxStdoutBytes":1,"maxStderrBytes":1,"maxTotalOutputBytes":1,"maxTransferBytes":1,"maxConcurrentCommands":1,"maxTimeoutMs":1000},"instructionSet":{"title":"Box AI","revision":"r1","content":"exec ls","updatedAt":7}}"""
        )
        assertEquals("exec ls", decoded.instructionSet?.content)
        assertEquals(7L, decoded.instructionSet?.updatedAt)
    }

    @Test
    fun `register rejects oversized instruction set`() {
        val result = ProtocolValidator.validateRegister(RegisterParams(
            protocol = EXECPLANE_PROTOCOL_VERSION,
            name = "box",
            caps = setOf("dispatch"),
            limits = limits,
            trust = ExecutorTrust.RESTRICTED,
            instructionSet = SandboxInstructionSet("Box", "r1", "x".repeat(ProtocolValidator.MAX_INSTRUCTION_SET_BYTES + 1)),
        ))
        assertTrue(result is ValidationResult.Invalid)
    }
}
