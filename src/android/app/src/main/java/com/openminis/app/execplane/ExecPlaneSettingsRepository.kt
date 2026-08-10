package com.openminis.app.execplane

import android.content.Context
import com.openminis.app.util.EncryptedPrefsFactory
import java.security.SecureRandom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ExecPlaneSettingsRepository(context: Context) {
    private val prefs = EncryptedPrefsFactory.safeCreate(context.applicationContext, PREFS)
    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, false))
    private val _port = MutableStateFlow(prefs.getInt(KEY_PORT, DEFAULT_PORT))

    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    val port: StateFlow<Int> = _port.asStateFlow()

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

    private fun generateToken(): String = ByteArray(24).also(SecureRandom()::nextBytes)
        .joinToString("") { "%02x".format(it) }

    companion object {
        const val DEFAULT_PORT = 8765
        private const val PREFS = "execplane_settings"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_PORT = "port"
        private const val KEY_TOKEN = "token"
    }
}
