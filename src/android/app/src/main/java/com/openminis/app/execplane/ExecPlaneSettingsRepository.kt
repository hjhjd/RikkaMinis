package com.openminis.app.execplane

import android.content.Context
import com.openminis.app.execplane.protocol.ExecPlaneJson
import com.openminis.app.util.EncryptedPrefsFactory
import java.security.SecureRandom
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

class ExecPlaneSettingsRepository(context: Context) {
    private val prefs = EncryptedPrefsFactory.safeCreate(context.applicationContext, PREFS)
    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, false))
    private val _port = MutableStateFlow(prefs.getInt(KEY_PORT, DEFAULT_PORT))

    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    val port: StateFlow<Int> = _port.asStateFlow()
    private val _forwardServers = MutableStateFlow(loadForwardServers())
    val forwardServers: StateFlow<List<ForwardServerConfig>> = _forwardServers.asStateFlow()
    private val _defaultSandboxId = MutableStateFlow(prefs.getString(KEY_DEFAULT_SANDBOX, SANDBOX_PROOT) ?: SANDBOX_PROOT)
    val defaultSandboxId: StateFlow<String> = _defaultSandboxId.asStateFlow()

    fun setDefaultSandbox(id: String) {
        val valid = id == SANDBOX_PROOT || _forwardServers.value.any { it.id == id }
        if (!valid) return
        prefs.edit().putString(KEY_DEFAULT_SANDBOX, id).apply()
        _defaultSandboxId.value = id
    }

    fun selectedForwardServer(): ForwardServerConfig? =
        _forwardServers.value.firstOrNull { it.id == _defaultSandboxId.value }

    fun setEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        _enabled.value = value
    }

    fun setPort(value: Int): Boolean {
        if (value !in 1024..65535) return false
        prefs.edit().putInt(KEY_PORT, value).apply()
        _port.value = value
        return true
    }

    fun token(): String = prefs.getString(KEY_TOKEN, null)?.takeIf { it.length >= 32 }
        ?: generateToken().also { prefs.edit().putString(KEY_TOKEN, it).commit() }

    fun resetToken(): String = generateToken().also {
        prefs.edit().putString(KEY_TOKEN, it).commit()
    }

    fun saveForwardServer(name: String, url: String, token: String): ForwardServerConfig? {
        val normalized = url.trim()
        if (!isAllowedUrl(normalized) || name.isBlank() || token.isBlank()) return null
        val trimmedName = name.trim()
        val existing = _forwardServers.value.firstOrNull { it.name.equals(trimmedName, ignoreCase = true) }
        val config = ForwardServerConfig(existing?.id ?: UUID.randomUUID().toString(), trimmedName, normalized, token)
        persistForwardServers(_forwardServers.value.filterNot { it.id == config.id } + config)
        return config
    }

    fun deleteForwardServer(id: String): Boolean {
        val updated = _forwardServers.value.filterNot { it.id == id }
        if (updated.size == _forwardServers.value.size) return false
        persistForwardServers(updated)
        if (_defaultSandboxId.value == id) setDefaultSandbox(SANDBOX_PROOT)
        return true
    }

    private fun loadForwardServers(): List<ForwardServerConfig> = runCatching {
        ExecPlaneJson.codec.decodeFromString<List<ForwardServerConfig>>(
            prefs.getString(KEY_FORWARD_SERVERS, "[]") ?: "[]",
        )
    }.getOrDefault(emptyList())

    private fun persistForwardServers(value: List<ForwardServerConfig>) {
        prefs.edit().putString(KEY_FORWARD_SERVERS, ExecPlaneJson.codec.encodeToString(value)).apply()
        _forwardServers.value = value
    }

    private fun isAllowedUrl(url: String): Boolean =
        url.startsWith("wss://") || url.startsWith("ws://127.0.0.1:") || url.startsWith("ws://localhost:")

    private fun generateToken(): String = ByteArray(24).also(SecureRandom()::nextBytes)
        .joinToString("") { "%02x".format(it) }

    companion object {
        const val DEFAULT_PORT = 8765
        const val SANDBOX_PROOT = "proot"
        private const val PREFS = "execplane_settings"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_PORT = "port"
        private const val KEY_TOKEN = "token"
        private const val KEY_FORWARD_SERVERS = "forwardServers"
        private const val KEY_DEFAULT_SANDBOX = "defaultSandbox"
    }
}
