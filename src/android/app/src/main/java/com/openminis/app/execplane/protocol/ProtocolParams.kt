package com.openminis.app.execplane.protocol

import kotlinx.serialization.Serializable

@Serializable
enum class ConnectionDirection { REVERSE, FORWARD, LOCAL }

@Serializable
enum class ExecutorTrust { LOCAL, TRUSTED, STANDARD, RESTRICTED }

@Serializable
data class ExecutorResources(
    val cpuCores: Int? = null,
    val memoryMb: Long? = null,
    val diskFreeMb: Long? = null,
    val os: String? = null,
    val arch: String? = null,
)

@Serializable
data class RegisterParams(
    val protocol: String,
    val name: String,
    val caps: Set<String>,
    val resources: ExecutorResources = ExecutorResources(),
    val limits: ExecutorLimits = ExecutorLimits(
        maxStdoutBytes = 16L * 1024 * 1024,
        maxStderrBytes = 8L * 1024 * 1024,
        maxTotalOutputBytes = 20L * 1024 * 1024,
        maxTransferBytes = 512L * 1024 * 1024,
        maxConcurrentCommands = 4,
        maxTimeoutMs = 3_600_000,
    ),
    val trust: ExecutorTrust,
    val tags: Set<String> = emptySet(),
    val identityPublicKey: String? = null,
    val identitySignature: String? = null,
    val identityChallenge: String? = null,
    val instructionSet: SandboxInstructionSet? = null,
    /** Stable server identity; omitted only by legacy peers. */
    val serverId: String? = null,
)

@Serializable
data class ExecParams(
    val cmd: List<String>,
    val cwd: String? = null,
    val env: Map<String, String> = emptyMap(),
    val timeoutMs: Long = 600_000,
    val shell: Boolean = false,
    val envMode: String = "overlay",
)

@Serializable
data class ExecutorLimits(
    val maxStdoutBytes: Long,
    val maxStderrBytes: Long,
    val maxTotalOutputBytes: Long,
    val maxTransferBytes: Long,
    val maxConcurrentCommands: Int,
    val maxTimeoutMs: Long,
)

@Serializable
data class SandboxInstructionSet(
    val title: String,
    val revision: String,
    val content: String,
    val updatedAt: Long? = null,
)

@Serializable
data class CapabilitiesResult(
    val protocol: String,
    val serverId: String,
    val name: String,
    val caps: Set<String>,
    val limits: ExecutorLimits,
    val instructionSet: SandboxInstructionSet? = null,
)

sealed interface ValidationResult {
    data object Valid : ValidationResult
    data class Invalid(val error: RpcError) : ValidationResult
}

object ProtocolValidator {
    const val MAX_INSTRUCTION_SET_BYTES = 256 * 1024
    private val safeName = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
    private val safeCapability = Regex("[a-z][a-z0-9._-]{0,63}")
    private val safeServerId = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")

    fun validateRegister(params: RegisterParams): ValidationResult {
        if (params.protocol != EXECPLANE_PROTOCOL_VERSION) {
            return invalid(ExecPlaneErrorCode.EXEC_UNSUPPORTED_VERSION, "Unsupported protocol version")
        }
        if (!safeName.matches(params.name)) {
            return invalid(ExecPlaneErrorCode.EXEC_INVALID_PARAMS, "Invalid executor name")
        }
        if (params.caps.isEmpty() || params.caps.any { !safeCapability.matches(it) }) {
            return invalid(ExecPlaneErrorCode.EXEC_INVALID_PARAMS, "Invalid capabilities")
        }
        if (params.resources.cpuCores != null && params.resources.cpuCores !in 1..1024) {
            return invalid(ExecPlaneErrorCode.EXEC_INVALID_PARAMS, "Invalid CPU resource value")
        }
        if ((params.resources.memoryMb ?: 0) < 0 || (params.resources.diskFreeMb ?: 0) < 0) {
            return invalid(ExecPlaneErrorCode.EXEC_INVALID_PARAMS, "Resource values cannot be negative")
        }
        if (params.limits.maxConcurrentCommands !in 1..4096 || params.limits.maxTimeoutMs !in 1_000..3_600_000 ||
            minOf(params.limits.maxStdoutBytes, params.limits.maxStderrBytes, params.limits.maxTotalOutputBytes, params.limits.maxTransferBytes) < 1
        ) {
            return invalid(ExecPlaneErrorCode.EXEC_INVALID_PARAMS, "Invalid executor limits")
        }
        if (params.serverId != null && !safeServerId.matches(params.serverId)) {
            return invalid(ExecPlaneErrorCode.EXEC_INVALID_PARAMS, "Invalid server ID")
        }
        if (params.tags.any { !safeCapability.matches(it) }) {
            return invalid(ExecPlaneErrorCode.EXEC_INVALID_PARAMS, "Invalid tags")
        }
        if (!isValidInstructionSet(params.instructionSet)) {
            return invalid(ExecPlaneErrorCode.EXEC_INVALID_PARAMS, "Invalid instruction set")
        }
        return ValidationResult.Valid
    }

    fun isValidInstructionSet(instructions: SandboxInstructionSet?): Boolean {
        if (instructions == null) return true
        return instructions.title.isNotBlank() && instructions.title.length <= 128 &&
            instructions.revision.isNotBlank() && instructions.revision.length <= 128 &&
            instructions.content.toByteArray(Charsets.UTF_8).size <= MAX_INSTRUCTION_SET_BYTES &&
            instructions.content.indexOf('\u0000') < 0
    }

    fun validateExec(params: ExecParams): ValidationResult {
        if (params.cmd.isEmpty() || params.cmd.any { it.isEmpty() || it.indexOf('\u0000') >= 0 }) {
            return invalid(ExecPlaneErrorCode.EXEC_INVALID_PARAMS, "Command argv is invalid")
        }
        if (params.cmd.size > 256 || params.cmd.sumOf { it.length } > 64 * 1024) {
            return invalid(ExecPlaneErrorCode.EXEC_INVALID_PARAMS, "Command argv is too large")
        }
        if (params.cwd?.indexOf('\u0000')?.let { it >= 0 } == true) {
            return invalid(ExecPlaneErrorCode.EXEC_INVALID_PARAMS, "Working directory is invalid")
        }
        if (params.env.any { (key, value) ->
                !Regex("[A-Za-z_][A-Za-z0-9_]*").matches(key) || value.indexOf('\u0000') >= 0
            }
        ) {
            return invalid(ExecPlaneErrorCode.EXEC_INVALID_PARAMS, "Environment is invalid")
        }
        if (params.timeoutMs !in 1_000..3_600_000) {
            return invalid(ExecPlaneErrorCode.EXEC_INVALID_PARAMS, "Timeout is outside allowed range")
        }
        if (params.envMode != "overlay") {
            return invalid(ExecPlaneErrorCode.EXEC_INVALID_PARAMS, "Unsupported environment mode")
        }
        if (params.shell && params.cmd.take(2) != listOf("/bin/sh", "-lc")) {
            return invalid(ExecPlaneErrorCode.EXEC_INVALID_PARAMS, "Shell commands must use /bin/sh -lc")
        }
        return ValidationResult.Valid
    }

    private fun invalid(code: ExecPlaneErrorCode, message: String) =
        ValidationResult.Invalid(RpcError(code, message))
}
