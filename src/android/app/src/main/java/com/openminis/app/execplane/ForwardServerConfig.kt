package com.openminis.app.execplane

import kotlinx.serialization.Serializable

@Serializable
data class ForwardServerConfig(
    val id: String,
    val name: String,
    val url: String,
    val token: String,
    val enabled: Boolean = true,
    val envPolicy: EnvironmentPolicy = EnvironmentPolicy.NONE,
    val authorizedEnvKeys: Set<String> = emptySet(),
    val allowRoots: Set<String> = emptySet(),
    val maxConcurrentCommands: Int = SandboxConcurrencyLimiter.DEFAULT_LIMIT,
)

@Serializable
enum class EnvironmentPolicy { NONE, SELECTED, ALL }
