package com.openminis.app.distributed

import android.util.Log
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

enum class DistributedConnectionState { DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING }

data class DistributedConnectionStatus(
    val state: DistributedConnectionState = DistributedConnectionState.DISCONNECTED,
    val serverId: String? = null,
    val clientId: String? = null,
    val lastError: String? = null,
    val reconnectDelaySeconds: Long? = null,
)

/**
 * VCPToolBox 分布式 WebSocket 的应用级连接管理器。
 * 当前只完成连接、ACK、断线和重连；工具注册与调用刻意留到下一阶段。
 */
class DistributedConnectionManager(
    private val settings: DistributedSettingsRepository,
    client: OkHttpClient? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = client ?: OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val generations = AtomicLong(0)
    private val stopped = AtomicBoolean(true)
    private val lock = Any()
    @Volatile private var socket: WebSocket? = null
    @Volatile private var retryJob: Job? = null
    @Volatile private var acknowledgementJob: Job? = null
    @Volatile private var retryDelayMs = INITIAL_RETRY_MS
    private val _status = MutableStateFlow(DistributedConnectionStatus())
    val status: StateFlow<DistributedConnectionStatus> = _status.asStateFlow()

    /** 把实际连接状态调和到持久化配置。配置变化时安全地替换旧连接。 */
    fun reconcile() {
        val config = settings.config.value
        if (!config.enabled) {
            stop()
            return
        }
        if (!DistributedSettingsRepository.isValidWsUrl(config.wsUrl) || config.vcpKey.isBlank()) {
            stopped.set(true)
            _status.value = DistributedConnectionStatus(
                lastError = "WebSocket 地址或 VCP Key 未配置",
            )
            return
        }
        start(config)
    }

    fun start(config: DistributedConnectionConfig = settings.config.value) {
        synchronized(lock) {
            stopped.set(false)
            retryJob?.cancel()
            retryJob = null
            acknowledgementJob?.cancel()
            acknowledgementJob = null
            socket?.cancel()
            socket = null
            retryDelayMs = INITIAL_RETRY_MS
            openSocket(config)
        }
    }

    fun reconnectNow() {
        val config = settings.config.value
        if (!config.enabled) return
        start(config)
    }

    fun stop() {
        synchronized(lock) {
            if (stopped.getAndSet(true) && socket == null && retryJob == null) return
            generations.incrementAndGet()
            retryJob?.cancel()
            retryJob = null
            acknowledgementJob?.cancel()
            acknowledgementJob = null
            _status.value = _status.value.copy(
                state = DistributedConnectionState.DISCONNECTING,
                reconnectDelaySeconds = null,
            )
            socket?.close(1000, "Distributed connection disabled")
            socket = null
            _status.value = DistributedConnectionStatus()
        }
    }

    private fun openSocket(config: DistributedConnectionConfig) {
        if (stopped.get()) return
        val url = buildConnectionUrl(config.wsUrl, config.vcpKey)
        if (url == null) {
            _status.value = DistributedConnectionStatus(lastError = "WebSocket 地址无效")
            return
        }
        val generation = generations.incrementAndGet()
        _status.value = DistributedConnectionStatus(state = DistributedConnectionState.CONNECTING)
        Log.i(TAG, "Connecting distributed node to ${redactConnectionUrl(url)}")
        val listener = Listener(generation, config)
        socket = client.newWebSocket(Request.Builder().url(url).build(), listener)
    }

    private fun isCurrent(webSocket: WebSocket, generation: Long): Boolean =
        !stopped.get() && socket === webSocket && generations.get() == generation

    private fun scheduleReconnect(config: DistributedConnectionConfig, reason: String) {
        if (stopped.get() || retryJob?.isActive == true) return
        val waitMs = retryDelayMs
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(MAX_RETRY_MS)
        _status.value = DistributedConnectionStatus(
            state = DistributedConnectionState.CONNECTING,
            lastError = reason,
            reconnectDelaySeconds = waitMs / 1000,
        )
        retryJob = scope.launch {
            delay(waitMs)
            if (!stopped.get() && settings.config.value.enabled) openSocket(settings.config.value)
        }
    }

    private inner class Listener(
        private val generation: Long,
        private val config: DistributedConnectionConfig,
    ) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (!isCurrent(webSocket, generation)) {
                webSocket.close(1000, "Superseded connection")
                return
            }
            retryDelayMs = INITIAL_RETRY_MS
            // TCP/WS 已建立，但只有收到 VCPToolBox connection_ack 才算业务连接成功。
            _status.value = DistributedConnectionStatus(state = DistributedConnectionState.CONNECTING)
            acknowledgementJob?.cancel()
            acknowledgementJob = scope.launch {
                delay(ACK_TIMEOUT_MS)
                if (isCurrent(webSocket, generation) &&
                    _status.value.state != DistributedConnectionState.CONNECTED
                ) {
                    webSocket.cancel()
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!isCurrent(webSocket, generation)) return
            val message = runCatching { JSONObject(text) }.getOrNull() ?: return
            if (message.optString("type") != "connection_ack") return
            acknowledgementJob?.cancel()
            acknowledgementJob = null
            val data = message.optJSONObject("data")
            _status.value = DistributedConnectionStatus(
                state = DistributedConnectionState.CONNECTED,
                serverId = data?.optString("serverId")?.takeIf { it.isNotBlank() },
                clientId = data?.optString("clientId")?.takeIf { it.isNotBlank() },
            )
            Log.i(TAG, "Distributed node acknowledged by server")
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            if (isCurrent(webSocket, generation)) webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!isCurrent(webSocket, generation)) return
            acknowledgementJob?.cancel()
            acknowledgementJob = null
            socket = null
            scheduleReconnect(config, "服务器已断开连接（$code）")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!isCurrent(webSocket, generation)) return
            acknowledgementJob?.cancel()
            acknowledgementJob = null
            socket = null
            val reason = t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
            Log.w(TAG, "Distributed connection failed: $reason")
            scheduleReconnect(config, reason)
        }
    }

    companion object {
        private const val TAG = "DistributedConnection"
        private const val INITIAL_RETRY_MS = 5_000L
        private const val MAX_RETRY_MS = 60_000L
        private const val ACK_TIMEOUT_MS = 15_000L

        internal fun buildConnectionUrl(baseUrl: String, vcpKey: String): String? = runCatching {
            require(DistributedSettingsRepository.isValidWsUrl(baseUrl))
            val wsScheme = java.net.URI(baseUrl).scheme.lowercase()
            val httpBase = baseUrl.trim().trimEnd('/')
                .replaceFirst(Regex("^wss://", RegexOption.IGNORE_CASE), "https://")
                .replaceFirst(Regex("^ws://", RegexOption.IGNORE_CASE), "http://")
                .plus("/")
                .toHttpUrl()
            val encoded = httpBase.newBuilder()
                .addPathSegment("vcp-distributed-server")
                .addPathSegment("VCP_Key=$vcpKey")
                .build()
                .toString()
            encoded.replaceFirst(if (wsScheme == "wss") "https://" else "http://", "$wsScheme://")
        }.getOrNull()

        private fun redactConnectionUrl(url: String): String =
            url.replace(Regex("VCP_Key=[^/?#]+"), "VCP_Key=***")
    }
}
