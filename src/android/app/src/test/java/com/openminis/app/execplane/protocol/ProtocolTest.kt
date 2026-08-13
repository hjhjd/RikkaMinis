package com.openminis.app.execplane.protocol

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.decodeFromJsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {
    @Test
    fun unknownFieldsAreForwardCompatible() {
        val request = ExecPlaneJson.codec.decodeFromString<RpcRequest>(
            """{"id":7,"method":"ping","params":{},"ts":1,"future":"ignored"}"""
        )
        assertEquals(7L, request.id)
        assertEquals("ping", request.method)
    }

    @Test
    fun channelErrorsAreExplicitlyClassified() {
        assertTrue(ExecPlaneErrorCode.CHANNEL_TIMEOUT.isChannelError)
        assertFalse(ExecPlaneErrorCode.EXEC_TIMEOUT.isChannelError)
        assertFalse(ExecPlaneErrorCode.EXEC_FAILED.isChannelError)
    }

    @Test
    fun registerRejectsUnsupportedVersion() {
        val result = ProtocolValidator.validateRegister(
            RegisterParams("9.0", "droidspaces", setOf("exec"), trust = ExecutorTrust.LOCAL)
        ) as ValidationResult.Invalid
        assertEquals(ExecPlaneErrorCode.EXEC_UNSUPPORTED_VERSION, result.error.code)
    }

    @Test
    fun execUsesArgvAndRejectsNul() {
        assertEquals(ValidationResult.Valid, ProtocolValidator.validateExec(ExecParams(listOf("uname", "-a"))))
        val invalid = ProtocolValidator.validateExec(ExecParams(listOf("echo", "bad\u0000arg")))
        assertTrue(invalid is ValidationResult.Invalid)
    }

    @Test
    fun shellExecRequiresExplicitShArgv() {
        assertEquals(
            ValidationResult.Valid,
            ProtocolValidator.validateExec(ExecParams(listOf("/bin/sh", "-lc", "echo ok"), shell = true)),
        )
        assertTrue(ProtocolValidator.validateExec(ExecParams(listOf("echo", "ok"), shell = true)) is ValidationResult.Invalid)
    }

    @Test
    fun outputEventDecodesWithRequestIdentity() {
        val event = ExecPlaneJson.codec.decodeFromString<RpcEvent>(
            """{"event":"exec.output","data":{"requestId":2,"sequence":0,"stream":"stdout","data":"ok"}}""",
        )
        val output = ExecPlaneJson.codec.decodeFromJsonElement<ExecOutputEvent>(event.data)
        assertEquals(2L, output.requestId)
        assertEquals("stdout", output.stream)
    }

    @Test
    fun execFailureNeverLooksLikeChannelFailure() {
        val error = RpcError(ExecPlaneErrorCode.EXEC_FAILED, "exit code 1")
        assertFalse(error.code.isChannelError)
    }
}
