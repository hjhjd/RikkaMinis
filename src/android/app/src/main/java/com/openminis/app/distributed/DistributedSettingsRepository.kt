package com.openminis.app.distributed

import android.content.Context
import com.openminis.app.util.EncryptedPrefsFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DistributedConnectionConfig(
    val enabled: Boolean = false,
    val wsUrl: String = "",
    val vcpKey: String = "",
    val deviceName: String = "RikkaMinis",
)

/** 分布式节点的期望配置。密钥与其他凭据一样存入加密偏好。 */
class DistributedSettingsRepository(context: Context) {
    private val prefs = EncryptedPrefsFactory.safeCreate(context.applicationContext, PREFS)
    private val _config = MutableStateFlow(load())
    val config: StateFlow<DistributedConnectionConfig> = _config.asStateFlow()

    @Synchronized
    fun save(wsUrl: String, vcpKey: String, deviceName: String, enabled: Boolean): Boolean {
        val normalizedUrl = wsUrl.trim().trimEnd('/')
        val normalizedName = deviceName.trim().ifBlank { DEFAULT_DEVICE_NAME }
        if (enabled && (!isValidWsUrl(normalizedUrl) || vcpKey.isBlank())) return false
        val value = DistributedConnectionConfig(enabled, normalizedUrl, vcpKey.trim(), normalizedName)
        prefs.edit()
            .putBoolean(KEY_ENABLED, value.enabled)
            .putString(KEY_WS_URL, value.wsUrl)
            .putString(KEY_VCP_KEY, value.vcpKey)
            .putString(KEY_DEVICE_NAME, value.deviceName)
            .apply()
        _config.value = value
        return true
    }

    fun setEnabled(enabled: Boolean): Boolean {
        val current = _config.value
        return save(current.wsUrl, current.vcpKey, current.deviceName, enabled)
    }

    private fun load() = DistributedConnectionConfig(
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        wsUrl = prefs.getString(KEY_WS_URL, "").orEmpty(),
        vcpKey = prefs.getString(KEY_VCP_KEY, "").orEmpty(),
        deviceName = prefs.getString(KEY_DEVICE_NAME, DEFAULT_DEVICE_NAME).orEmpty().ifBlank { DEFAULT_DEVICE_NAME },
    )

    companion object {
        const val DEFAULT_DEVICE_NAME = "RikkaMinis"
        private const val PREFS = "distributed_connection_settings"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_WS_URL = "wsUrl"
        private const val KEY_VCP_KEY = "vcpKey"
        private const val KEY_DEVICE_NAME = "deviceName"

        fun isValidWsUrl(value: String): Boolean = runCatching {
            val uri = java.net.URI(value)
            (uri.scheme.equals("ws", true) || uri.scheme.equals("wss", true)) &&
                !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null
        }.getOrDefault(false)
    }
}
