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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
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

class RemoteChannelException(message: String, cause: Throwable? = null) : Exception(message, cause)
class RemoteExecutionException(message: String) : Exception(message)

interface RemoteCommandConnection : ExecutorConnection {
    val capabilities: Set<String>
    suspend fun request(method: String, params: JsonObject, timeoutMs: Long = 600_000): RpcResponse
    suspend fun exec(command: String, timeoutMs: Long = 600_000, env: Map<String, String> = emptyMap()): RemoteExecResult
}

class ForwardConnection(
    private val config: ForwardServerConfig,
    private val manager: ConnectionManager,
    private val client: OkHttpClient,
) : RemoteCommandConnection {
    override val id: String = "forward:${config.id}"
    override val direction = ConnectionDirection.FORWARD
    @Volatile override var capabilities: Set<String> = setOf("exec", "status")
        private set
    private val nextId = AtomicLong(1)
    private val generations = AtomicLong(0)
    @Volatile private var activeGeneration = 0L
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
        val generation = generations.incrementAndGet()
        activeGeneration = generation
        val listener = Listener(generation)
        val newSocket = client.newWebSocket(request, listener)
        socket = newSocket
    }

    private fun isCurrent(webSocket: WebSocket, generation: Long): Boolean =
        socket === webSocket && activeGeneration == generation

    override suspend fun request(method: String, params: JsonObject, timeoutMs: Long): RpcResponse {
        val requestId = nextId.getAndIncrement()
        val waiter = CompletableDeferred<RpcResponse>()
        pending[requestId] = waiter
        val request = buildJsonObject {
            put("id", requestId)
            put("method", method)
            put("params", params)
            put("ts", System.currentTimeMillis())
        }
        if (socket?.send(request.toString()) != true) {
            pending.remove(requestId)
            throw RemoteChannelException("WebSocket Server is offline")
        }
        return try {
            withTimeout(timeoutMs + 5_000) { waiter.await() }
        } catch (e: CancellationException) {
            if (e is TimeoutCancellationException) {
                throw RemoteChannelException("WebSocket request timed out after ${timeoutMs}ms", e)
            }
            throw e
        } catch (e: Throwable) {
            throw RemoteChannelException("WebSocket request channel failed: ${e.message ?: e.javaClass.simpleName}", e)
        } finally {
            pending.remove(requestId)
        }
    }

    override suspend fun exec(command: String, timeoutMs: Long, env: Map<String, String>): RemoteExecResult {
        require(command.isNotBlank()) { "Command cannot be empty" }
        val params = buildJsonObject {
            put("cmd", command)
            put("timeoutMs", timeoutMs)
            put("env", kotlinx.serialization.json.buildJsonObject { env.forEach { (key, value) -> put(key, value) } })
            put("envMode", "overlay")
        }
        val response = request("exec", params, timeoutMs)
        if (!response.ok) throw RemoteExecutionException(response.error?.message ?: "Remote command failed")
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

    private inner class Listener(private val generation: Long) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (!isCurrent(webSocket, generation)) {
                webSocket.close(1000, "Superseded connection")
                return
            }
            socket = webSocket
            retryDelayMs = 1_000L
            scope.launch {
                val discovered = runCatching {
                    val reply = request("capabilities", JsonObject(emptyMap()), 10_000)
                    reply.result?.jsonObject?.get("caps")?.let { element ->
                        element.jsonArray.map { it.jsonPrimitive.content }.toSet()
                    }
                }.getOrNull().orEmpty()
                capabilities = discovered.ifEmpty { setOf("exec", "status") }
                manager.register(
                    this@ForwardConnection,
                    RegisterParams(
                        protocol = EXECPLANE_PROTOCOL_VERSION,
                        name = config.name,
                        caps = capabilities,
                        trust = ExecutorTrust.STANDARD,
                        tags = setOf("forward", "remote"),
                    ),
                )
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!isCurrent(webSocket, generation)) return
            val response = runCatching { ExecPlaneJson.codec.decodeFromString<RpcResponse>(text) }.getOrNull() ?: return
            pending[response.id]?.complete(response)
            manager.markSeen(config.name, id)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!isCurrent(webSocket, generation)) return
            socket = null
            manager.disconnect(config.name, id)
            scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!isCurrent(webSocket, generation)) return
            socket = null
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
