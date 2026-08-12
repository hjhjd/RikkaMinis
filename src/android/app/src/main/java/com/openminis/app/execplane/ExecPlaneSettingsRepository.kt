package com.openminis.app.execplane

import android.content.Context
import com.openminis.app.execplane.protocol.ExecPlaneJson
import com.openminis.app.util.EncryptedPrefsFactory
import org.json.JSONObject
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
    private val legacyDefault = prefs.getString(KEY_DEFAULT_SANDBOX, SANDBOX_PROOT) ?: SANDBOX_PROOT
    private val _sandboxMode = MutableStateFlow(
        prefs.getString(KEY_SANDBOX_MODE, null) ?: if (legacyDefault == SANDBOX_PROOT) MODE_PROOT else MODE_WS,
    )
    val sandboxMode: StateFlow<String> = _sandboxMode.asStateFlow()
    private val _defaultWsId = MutableStateFlow(
        prefs.getString(KEY_DEFAULT_WS, null) ?: legacyDefault.takeUnless { it == SANDBOX_PROOT },
    )
    val defaultWsId: StateFlow<String?> = _defaultWsId.asStateFlow()

    fun setSandboxMode(mode: String) {
        if (mode != MODE_PROOT && mode != MODE_WS) return
        prefs.edit().putString(KEY_SANDBOX_MODE, mode).apply()
        _sandboxMode.value = mode
    }

    fun setDefaultWsSandbox(id: String) {
        if (_forwardServers.value.none { it.id == id }) return
        prefs.edit().putString(KEY_DEFAULT_WS, id).apply()
        _defaultWsId.value = id
    }

    fun selectedForwardServer(): ForwardServerConfig? {
        if (_sandboxMode.value != MODE_WS) return null
        return _forwardServers.value.firstOrNull { it.id == _defaultWsId.value }
            ?: _forwardServers.value.firstOrNull()
    }

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
        if (_defaultWsId.value == null) setDefaultWsSandbox(config.id)
        return config
    }

    fun updateForwardServerPolicy(
        id: String,
        policy: EnvironmentPolicy,
        authorizedKeys: Set<String>,
    ): Boolean {
        val current = _forwardServers.value.firstOrNull { it.id == id } ?: return false
        val safeKeys = authorizedKeys.filter { ENV_KEY.matches(it) }.toSet()
        persistForwardServers(_forwardServers.value.map {
            if (it.id == id) current.copy(envPolicy = policy, authorizedEnvKeys = safeKeys) else it
        })
        return true
    }

    fun environmentFor(config: ForwardServerConfig, all: Map<String, String>): Map<String, String> {
        val candidates = when (config.envPolicy) {
            EnvironmentPolicy.NONE -> emptyMap()
            EnvironmentPolicy.SELECTED -> all.filterKeys { it in config.authorizedEnvKeys }
            EnvironmentPolicy.ALL -> all
        }
        return candidates.filterKeys { key -> key !in RESERVED_ENV && ENV_KEY.matches(key) }
    }

    fun deleteForwardServer(id: String): Boolean {
        val updated = _forwardServers.value.filterNot { it.id == id }
        if (updated.size == _forwardServers.value.size) return false
        persistForwardServers(updated)
        if (_defaultWsId.value == id) {
            val replacement = updated.firstOrNull()?.id
            prefs.edit().putString(KEY_DEFAULT_WS, replacement).apply()
            _defaultWsId.value = replacement
            if (replacement == null) setSandboxMode(MODE_PROOT)
        }
        return true
    }

    fun exportBackup(includeSecrets: Boolean): JSONObject = JSONObject().apply {
        put("enabled", _enabled.value)
        put("port", _port.value)
        put("sandboxMode", _sandboxMode.value)
        put("defaultWsSandbox", _defaultWsId.value)
        put("forwardServers", ExecPlaneJson.codec.encodeToString(
            _forwardServers.value.map { if (includeSecrets) it else it.copy(token = "") },
        ))
        if (includeSecrets) put("listenerToken", token())
    }

    fun importBackup(value: JSONObject, includesSecrets: Boolean): Int {
        var applied = 0
        if (value.has("port") && setPort(value.optInt("port", DEFAULT_PORT))) applied++
        if (value.has("enabled")) {
            setEnabled(value.optBoolean("enabled", false))
            applied++
        }
        value.optString("sandboxMode").takeIf { it == MODE_PROOT || it == MODE_WS }?.let {
            setSandboxMode(it)
            applied++
        }
        val importedServers = runCatching {
            ExecPlaneJson.codec.decodeFromString<List<ForwardServerConfig>>(
                value.optString("forwardServers", "[]"),
            )
        }.getOrDefault(emptyList()).filter { server ->
            server.name.isNotBlank() && isAllowedUrl(server.url)
        }.map { server ->
            if (includesSecrets) server else {
                val existingToken = _forwardServers.value.firstOrNull { it.id == server.id }?.token.orEmpty()
                server.copy(token = existingToken, enabled = server.enabled && existingToken.isNotBlank())
            }
        }
        if (value.has("forwardServers")) {
            persistForwardServers(importedServers)
            applied += importedServers.size
        }
        value.optString("defaultWsSandbox").takeIf { id -> importedServers.any { it.id == id } }?.let {
            setDefaultWsSandbox(it)
            applied++
        }
        if (includesSecrets) value.optString("listenerToken").takeIf { it.length >= 32 }?.let {
            prefs.edit().putString(KEY_TOKEN, it).commit()
            applied++
        }
        return applied
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
        const val MODE_PROOT = "proot"
        const val MODE_WS = "ws"
        private const val PREFS = "execplane_settings"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_PORT = "port"
        private const val KEY_TOKEN = "token"
        private const val KEY_FORWARD_SERVERS = "forwardServers"
        private const val KEY_DEFAULT_SANDBOX = "defaultSandbox"
        private const val KEY_SANDBOX_MODE = "sandboxMode"
        private const val KEY_DEFAULT_WS = "defaultWsSandbox"
        private val ENV_KEY = Regex("[A-Za-z_][A-Za-z0-9_]*")
        private val RESERVED_ENV = setOf(
            "EXECPLANE_TOKEN", "MINIS_EXECPLANE_TOKEN", "ANDROID_HOME", "ANDROID_DATA",
            "LD_PRELOAD", "LD_LIBRARY_PATH", "PROOT_LOADER", "PROOT_LOADER_32",
        )
    }
}
