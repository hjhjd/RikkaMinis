package com.openminis.app.execplane

import kotlinx.serialization.Serializable

@Serializable
data class ForwardServerConfig(
    val id: String,
    val name: String,
    val url: String,
    val token: String,
    val enabled: Boolean = true,
)
