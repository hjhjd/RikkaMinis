package com.openminis.app.vcpinfo

import android.util.Log
import com.openminis.app.distributed.DistributedConnectionConfig
import com.openminis.app.distributed.DistributedSettingsRepository
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

enum class VcpInfoConnectionState { CLOSED, CONNECTING, CONNECTED, ERROR }
data class VcpInfoConnectionStatus(
    val state: VcpInfoConnectionState = VcpInfoConnectionState.CLOSED,
    val lastError: String? = null,
    val reconnectDelaySeconds: Long? = null,
)

/** 独立的只读认知广播通道；不复用分布式工具节点 socket。 */
class VcpInfoConnectionManager(
    private val settings: DistributedSettingsRepository,
    val store: VcpInfoStore = VcpInfoStore(),
    client: OkHttpClient? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = client ?: OkHttpClient.Builder().pingInterval(15, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()
    private val generations = AtomicLong(0)
    private val stopped = AtomicBoolean(true)
    @Volatile private var socket: WebSocket? = null
    @Volatile private var retryJob: Job? = null
    @Volatile private var retryDelayMs = 1_000L
    private val mutableStatus = MutableStateFlow(VcpInfoConnectionStatus())
    val status: StateFlow<VcpInfoConnectionStatus> = mutableStatus.asStateFlow()

    fun reconcile() {
        val config = settings.config.value
        // VCPInfo 跟随分布式总开关和同一服务器凭据，避免重复配置。
        if (!config.enabled || !DistributedSettingsRepository.isValidWsUrl(config.wsUrl) || config.vcpKey.isBlank()) stop()
        else start(config)
    }

    @Synchronized
    fun start(config: DistributedConnectionConfig = settings.config.value) {
        stopped.set(false); retryJob?.cancel(); retryJob = null; socket?.cancel(); socket = null
        retryDelayMs = 1_000L
        open(config)
    }

    @Synchronized
    fun stop() {
        stopped.set(true); generations.incrementAndGet(); retryJob?.cancel(); retryJob = null
        socket?.close(1000, "VCPInfo disabled"); socket = null
        mutableStatus.value = VcpInfoConnectionStatus()
    }

    fun reconnectNow() { if (settings.config.value.enabled) start() }

    private fun open(config: DistributedConnectionConfig) {
        if (stopped.get()) return
        val url = buildInfoUrl(config.wsUrl, config.vcpKey) ?: run {
            mutableStatus.value = VcpInfoConnectionStatus(VcpInfoConnectionState.ERROR, "VCPInfo 地址无效"); return
        }
        val generation = generations.incrementAndGet()
        mutableStatus.value = VcpInfoConnectionStatus(VcpInfoConnectionState.CONNECTING)
        Log.i(TAG, "Connecting VCPInfo to ${redact(url)}")
        val newSocket = client.newWebSocket(Request.Builder().url(url).build(), Listener(generation, config))
        socket = newSocket
    }

    private fun current(ws: WebSocket, generation: Long) = !stopped.get() && socket === ws && generations.get() == generation

    private fun retry(config: DistributedConnectionConfig, reason: String) {
        if (stopped.get() || retryJob?.isActive == true) return
        val wait = retryDelayMs; retryDelayMs = (retryDelayMs * 2).coerceAtMost(60_000L)
        mutableStatus.value = VcpInfoConnectionStatus(VcpInfoConnectionState.ERROR, reason, wait / 1000)
        retryJob = scope.launch { delay(wait); if (!stopped.get() && settings.config.value.enabled) open(settings.config.value) }
    }

    private inner class Listener(private val generation: Long, private val config: DistributedConnectionConfig) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (!current(webSocket, generation)) { webSocket.close(1000, "Superseded"); return }
            retryDelayMs = 1_000L
            mutableStatus.value = VcpInfoConnectionStatus(VcpInfoConnectionState.CONNECTED)
        }
        override fun onMessage(webSocket: WebSocket, text: String) { if (current(webSocket, generation)) store.accept(text) }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { if (current(webSocket, generation)) webSocket.close(code, reason) }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!current(webSocket, generation)) return; socket = null; retry(config, "服务器已断开（$code）")
        }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!current(webSocket, generation)) return; socket = null
            retry(config, t.message?.takeIf(String::isNotBlank) ?: t.javaClass.simpleName)
        }
    }

    companion object {
        private const val TAG = "VcpInfoConnection"
        internal fun buildInfoUrl(baseUrl: String, key: String): String? = runCatching {
            require(DistributedSettingsRepository.isValidWsUrl(baseUrl))
            val scheme = java.net.URI(baseUrl).scheme.lowercase()
            val http = baseUrl.trim().replaceFirst(Regex("^wss://", RegexOption.IGNORE_CASE), "https://")
                .replaceFirst(Regex("^ws://", RegexOption.IGNORE_CASE), "http://").toHttpUrl()
            val encoded = http.newBuilder().encodedPath("/").query(null)
                .addPathSegment("vcpinfo").addPathSegment("VCP_Key=$key").build().toString()
            encoded.replaceFirst(if (scheme == "wss") "https://" else "http://", "$scheme://")
        }.getOrNull()
        private fun redact(url: String) = url.replace(Regex("VCP_Key=[^/?#]+"), "VCP_Key=***")
    }
}
