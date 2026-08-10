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
    val trust: ExecutorTrust,
    val tags: Set<String> = emptySet(),
)

@Serializable
data class ExecParams(
    val cmd: List<String>,
    val cwd: String? = null,
    val env: Map<String, String> = emptyMap(),
    val timeoutMs: Long = 600_000,
    val shell: Boolean = false,
)

sealed interface ValidationResult {
    data object Valid : ValidationResult
    data class Invalid(val error: RpcError) : ValidationResult
}

object ProtocolValidator {
    private val safeName = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
    private val safeCapability = Regex("[a-z][a-z0-9._-]{0,63}")

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
        if (params.tags.any { !safeCapability.matches(it) }) {
            return invalid(ExecPlaneErrorCode.EXEC_INVALID_PARAMS, "Invalid tags")
        }
        return ValidationResult.Valid
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
        return ValidationResult.Valid
    }

    private fun invalid(code: ExecPlaneErrorCode, message: String) =
        ValidationResult.Invalid(RpcError(code, message))
}
