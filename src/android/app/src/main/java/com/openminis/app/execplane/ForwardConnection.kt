package com.openminis.app.execplane

import com.openminis.app.execplane.connection.ConnectionManager
import com.openminis.app.execplane.connection.ExecutorConnection
import com.openminis.app.execplane.protocol.ConnectionDirection
import com.openminis.app.execplane.protocol.EXECPLANE_PROTOCOL_VERSION
import com.openminis.app.execplane.protocol.ExecPlaneJson
import com.openminis.app.execplane.protocol.ExecutorTrust
import com.openminis.app.execplane.protocol.RegisterParams
import com.openminis.app.execplane.protocol.RpcResponse
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

data class RemoteExecResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val durationMs: Long? = null,
)

interface RemoteCommandConnection : ExecutorConnection {
    suspend fun exec(command: String, timeoutMs: Long = 600_000): RemoteExecResult
}

class ForwardConnection(
    private val config: ForwardServerConfig,
    private val manager: ConnectionManager,
    private val client: OkHttpClient,
) : RemoteCommandConnection {
    override val id: String = "forward:${config.id}"
    override val direction = ConnectionDirection.FORWARD
    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<RpcResponse>>()
    private val scope = CoroutineScope(Dispatchers.IO)
    private val stopped = AtomicBoolean(false)
    @Volatile private var socket: WebSocket? = null
    @Volatile private var retryJob: Job? = null
    @Volatile private var retryDelayMs = 1_000L

    fun connect() {
        stopped.set(false)
        retryJob?.cancel()
        manager.rememberOffline(
            config.name, id, direction, setOf("exec", "status"), ExecutorTrust.STANDARD,
            setOf("forward", "remote"),
        )
        openSocket()
    }

    private fun openSocket() {
        val request = Request.Builder().url(config.url)
            .header("X-Minis-Token", config.token)
            .build()
        socket = client.newWebSocket(request, Listener())
    }

    override suspend fun exec(command: String, timeoutMs: Long): RemoteExecResult {
        require(command.isNotBlank()) { "Command cannot be empty" }
        val requestId = nextId.getAndIncrement()
        val waiter = CompletableDeferred<RpcResponse>()
        pending[requestId] = waiter
        val params = buildJsonObject {
            put("cmd", command)
            put("timeoutMs", timeoutMs)
        }
        val request = buildJsonObject {
            put("id", requestId)
            put("method", "exec")
            put("params", params)
            put("ts", System.currentTimeMillis())
        }
        if (socket?.send(request.toString()) != true) {
            pending.remove(requestId)
            error("WebSocket Server is offline")
        }
        val response = try {
            withTimeout(timeoutMs + 5_000) { waiter.await() }
        } finally {
            pending.remove(requestId)
        }
        if (!response.ok) error(response.error?.message ?: "Remote command failed")
        val result = response.result?.jsonObject ?: JsonObject(emptyMap())
        return RemoteExecResult(
            stdout = result["stdout"]?.jsonPrimitive?.content.orEmpty(),
            stderr = result["stderr"]?.jsonPrimitive?.content.orEmpty(),
            exitCode = result["exitCode"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            durationMs = result["durationMs"]?.jsonPrimitive?.content?.toLongOrNull(),
        )
    }

    override fun close(code: Int, reason: String) {
        stopped.set(true)
        retryJob?.cancel()
        retryJob = null
        socket?.close(code, reason)
        socket = null
        pending.values.forEach { it.completeExceptionally(IllegalStateException(reason)) }
        pending.clear()
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            retryDelayMs = 1_000L
            manager.register(
                this@ForwardConnection,
                RegisterParams(
                    protocol = EXECPLANE_PROTOCOL_VERSION,
                    name = config.name,
                    caps = setOf("exec", "status"),
                    trust = ExecutorTrust.STANDARD,
                    tags = setOf("forward", "remote"),
                ),
            )
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val response = runCatching { ExecPlaneJson.codec.decodeFromString<RpcResponse>(text) }.getOrNull() ?: return
            pending[response.id]?.complete(response)
            manager.markSeen(config.name, id)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            manager.disconnect(config.name, id)
            scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            pending.values.forEach { it.completeExceptionally(t) }
            pending.clear()
            manager.disconnect(config.name, id)
            manager.rememberOffline(
                config.name, id, direction, setOf("exec", "status"), ExecutorTrust.STANDARD,
                setOf("forward", "remote"),
            )
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (stopped.get() || retryJob?.isActive == true) return
        val waitMs = retryDelayMs
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(30_000L)
        retryJob = scope.launch {
            delay(waitMs)
            if (!stopped.get()) openSocket()
        }
    }
}
