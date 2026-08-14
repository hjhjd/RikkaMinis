package com.openminis.app.vcplog

import android.util.Log
import com.openminis.app.distributed.DistributedConnectionConfig
import com.openminis.app.distributed.DistributedSettingsRepository
import com.openminis.app.network.NetworkMonitor
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.java_websocket.client.WebSocketClient
import org.java_websocket.drafts.Draft_6455
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject

/** VCPToolBox /VCPlog 独立通道；复用服务器配置，不复用分布式或 VCPInfo socket。 */
class VcpLogConnectionManager(
    private val settings: DistributedSettingsRepository,
    val store: VcpLogStore = VcpLogStore(),
    networkMonitor: NetworkMonitor? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val generations = AtomicLong(0)
    private val stopped = AtomicBoolean(true)
    @Volatile private var socket: WebSocketClient? = null
    @Volatile private var retryJob: Job? = null
    @Volatile private var heartbeatJob: Job? = null
    @Volatile private var retryDelayMs = INITIAL_RETRY_MS
    @Volatile private var heartbeatIntervalMs = FOREGROUND_HEARTBEAT_MS
    @Volatile private var activeConfig: DistributedConnectionConfig? = null
    private val mutableStatus = MutableStateFlow(VcpLogConnectionStatus())
    val status: StateFlow<VcpLogConnectionStatus> = mutableStatus.asStateFlow()

    init {
        networkMonitor?.let { monitor ->
            scope.launch {
                monitor.status.drop(1).distinctUntilChanged().collect { state ->
                    if (state == NetworkMonitor.NetworkStatus.CONNECTED &&
                        mutableStatus.value.state == VcpLogConnectionState.ERROR
                    ) reconnectNow()
                }
            }
        }
    }

    fun reconcile() {
        val config = settings.config.value
        if (!config.enabled || !DistributedSettingsRepository.isValidWsUrl(config.wsUrl) || config.vcpKey.isBlank()) {
            stop()
        } else if (!stopped.get() && activeConfig == config && socket != null) {
            return
        } else {
            start(config)
        }
    }

    @Synchronized
    fun start(config: DistributedConnectionConfig = settings.config.value) {
        if (!config.enabled || !DistributedSettingsRepository.isValidWsUrl(config.wsUrl) || config.vcpKey.isBlank()) {
            stop(); return
        }
        stopped.set(false)
        activeConfig = config
        invalidateAndDetachSocket("VCPLog reconfigured")
        retryDelayMs = INITIAL_RETRY_MS
        open(config, includeDeviceName = true)
    }

    fun reconnectNow() {
        val latest = settings.config.value
        if (isUsable(latest)) start(latest) else stop()
    }

    fun stopForBackgroundLinger() {
        if (isUsable(settings.config.value)) stop()
    }

    @Synchronized
    fun stop() {
        if (stopped.getAndSet(true) && socket == null && retryJob == null) return
        activeConfig = null
        invalidateAndDetachSocket("VCPLog disabled")
        mutableStatus.value = VcpLogConnectionStatus()
    }

    fun setForeground(isForeground: Boolean) {
        heartbeatIntervalMs = if (isForeground) FOREGROUND_HEARTBEAT_MS else BACKGROUND_HEARTBEAT_MS
        if (socket?.isOpen == true) startHeartbeat(generations.get())
    }

    fun send(payload: JSONObject): Result<Unit> = runCatching {
        val current = socket
        check(current != null && current.isOpen && mutableStatus.value.state == VcpLogConnectionState.CONNECTED) {
            "VCPLog connection is not active"
        }
        current.send(payload.toString())
    }

    @Synchronized
    private fun invalidateAndDetachSocket(reason: String) {
        generations.incrementAndGet()
        retryJob?.cancel(); retryJob = null
        heartbeatJob?.cancel(); heartbeatJob = null
        val oldSocket = socket
        socket = null
        oldSocket?.close(1000, reason)
    }

    private fun open(config: DistributedConnectionConfig, includeDeviceName: Boolean) {
        if (stopped.get()) return
        val url = buildLogUrl(config.wsUrl, config.vcpKey, config.deviceName, includeDeviceName) ?: run {
            mutableStatus.value = VcpLogConnectionStatus(VcpLogConnectionState.ERROR, "VCPLog 地址无效")
            return
        }
        val generation = generations.incrementAndGet()
        mutableStatus.value = VcpLogConnectionStatus(VcpLogConnectionState.CONNECTING)
        Log.i(TAG, "Connecting VCPLog to ${redact(url)}")
        val headers = mapOf(
            "Origin" to originFor(url),
            "User-Agent" to "VCPMinis/Android VCPLog",
        )
        val client = object : WebSocketClient(URI(url), Draft_6455(), headers, CONNECT_TIMEOUT_MS.toInt()) {
            override fun onOpen(handshakedata: ServerHandshake) {
                if (!isCurrent(this, generation)) { close(1000, "Superseded"); return }
                retryDelayMs = INITIAL_RETRY_MS
                mutableStatus.value = VcpLogConnectionStatus(
                    state = VcpLogConnectionState.CONNECTED,
                    connectedUrl = redact(url),
                )
                startHeartbeat(generation)
                Log.i(TAG, "VCPLog connected to ${redact(url)}")
            }

            override fun onMessage(message: String) {
                if (isCurrent(this, generation)) store.accept(message)
            }

            override fun onClose(code: Int, reason: String, remote: Boolean) {
                if (!isCurrent(this, generation)) return
                socket = null
                heartbeatJob?.cancel(); heartbeatJob = null
                scheduleReconnect("服务器已断开（$code）")
            }

            override fun onError(ex: Exception) {
                if (!isCurrent(this, generation)) return
                val reason = ex.message?.takeIf(String::isNotBlank) ?: ex.javaClass.simpleName
                Log.w(TAG, "VCPLog connection failed: ${redact(reason)}")
                if (includeDeviceName && shouldRetryWithoutDeviceName(ex)) {
                    socket = null
                    generations.incrementAndGet()
                    open(config, includeDeviceName = false)
                } else {
                    socket = null
                    scheduleReconnect(redact(reason))
                }
            }
        }
        if (url.startsWith("wss://", ignoreCase = true)) {
            client.setSocketFactory(SSLSocketFactory.getDefault() as SSLSocketFactory)
        }
        socket = client
        client.connect()
    }

    private fun isCurrent(client: WebSocketClient, generation: Long): Boolean =
        !stopped.get() && socket === client && generations.get() == generation

    @Synchronized
    private fun scheduleReconnect(reason: String) {
        if (stopped.get() || retryJob?.isActive == true) return
        val wait = retryDelayMs
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(MAX_RETRY_MS)
        mutableStatus.value = VcpLogConnectionStatus(
            VcpLogConnectionState.ERROR,
            reason,
            wait / 1000,
        )
        retryJob = scope.launch {
            delay(wait)
            retryJob = null
            val latest = settings.config.value
            if (!stopped.get() && isUsable(latest)) {
                activeConfig = latest
                open(latest, true)
            } else {
                stop()
            }
        }
    }

    @Synchronized
    private fun startHeartbeat(generation: Long) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (!stopped.get() && generations.get() == generation) {
                delay(heartbeatIntervalMs)
                val current = socket
                if (current == null || !current.isOpen || generations.get() != generation) break
                runCatching { current.sendPing() }.onFailure {
                    Log.w(TAG, "VCPLog ping failed: ${it.javaClass.simpleName}")
                    current.closeConnection(1006, "Ping failed")
                }
            }
        }
    }

    companion object {
        private const val TAG = "VcpLogConnection"
        private const val CONNECT_TIMEOUT_MS = 5_000L
        private const val INITIAL_RETRY_MS = 1_000L
        private const val MAX_RETRY_MS = 60_000L
        private const val FOREGROUND_HEARTBEAT_MS = 15_000L
        private const val BACKGROUND_HEARTBEAT_MS = 120_000L

        internal fun buildLogUrl(baseUrl: String, key: String, deviceName: String, includeDeviceName: Boolean = true): String? = runCatching {
            require(DistributedSettingsRepository.isValidWsUrl(baseUrl))
            val scheme = URI(baseUrl).scheme.lowercase()
            val http = baseUrl.trim().replaceFirst(Regex("^wss://", RegexOption.IGNORE_CASE), "https://")
                .replaceFirst(Regex("^ws://", RegexOption.IGNORE_CASE), "http://").toHttpUrl()
            val builder = http.newBuilder().encodedPath("/").query(null)
                .addPathSegment("VCPlog").addPathSegment("VCP_Key=$key")
            if (includeDeviceName) builder.addQueryParameter("deviceName", deviceName.ifBlank { "VCPMinis" })
            builder.build().toString().replaceFirst(if (scheme == "wss") "https://" else "http://", "$scheme://")
        }.getOrNull()

        internal fun redact(value: String): String = value.replace(Regex("VCP_Key(?:=|%3D)[^/?&#\\s]+", RegexOption.IGNORE_CASE), "VCP_Key=***")
        internal fun originFor(url: String): String {
            val uri = URI(url)
            val scheme = if (uri.scheme.equals("wss", true)) "https" else "http"
            return "$scheme://${uri.rawAuthority}"
        }
        internal fun shouldRetryWithoutDeviceName(error: Throwable): Boolean {
            if (!error.javaClass.name.contains("InvalidStatus", true)) return false
            val text = error.message.orEmpty()
            return listOf(400, 404).any { code -> Regex("(?:^|\\D)$code(?:\\D|$)").containsMatchIn(text) }
        }

        private fun isUsable(config: DistributedConnectionConfig): Boolean =
            config.enabled && config.vcpKey.isNotBlank() &&
                DistributedSettingsRepository.isValidWsUrl(config.wsUrl)
    }
}
