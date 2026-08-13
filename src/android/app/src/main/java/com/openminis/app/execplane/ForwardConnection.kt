package com.openminis.app.execplane

import com.openminis.app.execplane.connection.ConnectionManager
import com.openminis.app.execplane.connection.ExecutorConnection
import com.openminis.app.execplane.protocol.CapabilitiesResult
import com.openminis.app.execplane.protocol.ConnectionDirection
import com.openminis.app.execplane.protocol.EXECPLANE_PROTOCOL_VERSION
import com.openminis.app.execplane.protocol.ExecOutputEvent
import com.openminis.app.execplane.protocol.ExecPlaneErrorCode
import com.openminis.app.execplane.protocol.ExecPlaneJson
import com.openminis.app.execplane.protocol.ExecutorTrust
import com.openminis.app.execplane.protocol.RegisterParams
import com.openminis.app.execplane.protocol.RpcEvent
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
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.buildJsonArray
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
    val stdoutBytes: Long? = null,
    val stderrBytes: Long? = null,
    val truncated: Boolean = false,
)

class RemoteChannelException(
    val code: ExecPlaneErrorCode,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    init { require(code.isChannelError) { "$code is not a channel error" } }
}

class RemoteExecutionException(
    val code: ExecPlaneErrorCode,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    init { require(!code.isChannelError) { "$code is a channel error" } }
}

internal fun RpcResponse.remoteFailure(defaultMessage: String): Exception {
    val rpcError = error
    val code = rpcError?.code ?: ExecPlaneErrorCode.EXEC_FAILED
    val message = rpcError?.message ?: defaultMessage
    return if (code.isChannelError) {
        RemoteChannelException(code, message)
    } else {
        RemoteExecutionException(code, message)
    }
}

internal class ConnectionPendingRequests {
    private val lock = Any()
    private val items = mutableMapOf<Long, CompletableDeferred<RpcResponse>>()
    private var closedFailure: Throwable? = null

    fun add(requestId: Long): CompletableDeferred<RpcResponse> = synchronized(lock) {
        CompletableDeferred<RpcResponse>().also { waiter ->
            val failure = closedFailure
            if (failure == null) items[requestId] = waiter
            else waiter.completeExceptionally(failure)
        }
    }

    fun remove(requestId: Long) = synchronized(lock) {
        items.remove(requestId)
        Unit
    }

    fun complete(response: RpcResponse): Boolean = synchronized(lock) {
        items[response.id]?.complete(response) == true
    }

    fun failAll(error: Throwable) {
        val waiters = synchronized(lock) {
            if (closedFailure == null) closedFailure = error
            items.values.toList().also { items.clear() }
        }
        waiters.forEach { it.completeExceptionally(error) }
    }

    fun size(): Int = synchronized(lock) { items.size }
}

interface RemoteCommandConnection : ExecutorConnection {
    val capabilities: Set<String>
    val handshake: CapabilitiesResult?
    suspend fun request(method: String, params: JsonObject, timeoutMs: Long = 600_000): RpcResponse
    suspend fun exec(
        command: String,
        timeoutMs: Long = 600_000,
        env: Map<String, String> = emptyMap(),
        outputCallback: ((String, String) -> Unit)? = null,
    ): RemoteExecResult
}

class ForwardConnection(
    private val config: ForwardServerConfig,
    private val manager: ConnectionManager,
    private val client: OkHttpClient,
) : RemoteCommandConnection {
    override val id: String = "forward:${config.id}"
    override val direction = ConnectionDirection.FORWARD
    @Volatile override var capabilities: Set<String> = emptySet()
        private set
    @Volatile override var handshake: CapabilitiesResult? = null
        private set
    private val nextId = AtomicLong(1)
    private val generations = AtomicLong(0)
    @Volatile private var activeGeneration = 0L
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<RpcResponse>>()
    private val outputCallbacks = ConcurrentHashMap<Long, (String, String) -> Unit>()
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

    override suspend fun request(method: String, params: JsonObject, timeoutMs: Long): RpcResponse =
        requestInternal(method, params, timeoutMs, null)

    private suspend fun requestInternal(
        method: String,
        params: JsonObject,
        timeoutMs: Long,
        outputCallback: ((String, String) -> Unit)?,
    ): RpcResponse {
        val requestId = nextId.getAndIncrement()
        val waiter = CompletableDeferred<RpcResponse>()
        pending[requestId] = waiter
        outputCallback?.let { outputCallbacks[requestId] = it }
        val request = buildJsonObject {
            put("id", requestId)
            put("method", method)
            put("params", params)
            put("ts", System.currentTimeMillis())
        }
        if (socket?.send(request.toString()) != true) {
            pending.remove(requestId)
            throw RemoteChannelException(ExecPlaneErrorCode.CHANNEL_EXECUTOR_OFFLINE, "WebSocket Server is offline")
        }
        return try {
            withTimeout(timeoutMs + 5_000) { waiter.await() }
        } catch (e: CancellationException) {
            if (e is TimeoutCancellationException) {
                if (method == "exec") sendCancel(requestId)
                throw RemoteChannelException(
                    ExecPlaneErrorCode.CHANNEL_TIMEOUT,
                    "WebSocket request timed out after ${timeoutMs}ms",
                    e,
                )
            }
            if (method == "exec") sendCancel(requestId)
            throw e
        } catch (e: Throwable) {
            if (e is RemoteChannelException) throw e
            throw RemoteChannelException(
                ExecPlaneErrorCode.CHANNEL_DISCONNECTED,
                "WebSocket request channel failed: ${e.message ?: e.javaClass.simpleName}",
                e,
            )
        } finally {
            pending.remove(requestId)
            outputCallbacks.remove(requestId)
        }
    }

    override suspend fun exec(
        command: String,
        timeoutMs: Long,
        env: Map<String, String>,
        outputCallback: ((String, String) -> Unit)?,
    ): RemoteExecResult {
        require(command.isNotBlank()) { "Command cannot be empty" }
        val params = buildJsonObject {
            put("cmd", buildJsonArray {
                add(kotlinx.serialization.json.JsonPrimitive("/bin/sh"))
                add(kotlinx.serialization.json.JsonPrimitive("-lc"))
                add(kotlinx.serialization.json.JsonPrimitive(command))
            })
            put("shell", true)
            put("timeoutMs", timeoutMs)
            put("env", kotlinx.serialization.json.buildJsonObject { env.forEach { (key, value) -> put(key, value) } })
            put("envMode", "overlay")
        }
        val response = requestInternal("exec", params, timeoutMs, outputCallback)
        if (!response.ok) throw response.remoteFailure("Remote command failed")
        val result = response.result?.jsonObject ?: JsonObject(emptyMap())
        return RemoteExecResult(
            stdout = result["stdout"]?.jsonPrimitive?.content.orEmpty(),
            stderr = result["stderr"]?.jsonPrimitive?.content.orEmpty(),
            exitCode = result["exitCode"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            durationMs = result["durationMs"]?.jsonPrimitive?.content?.toLongOrNull(),
            stdoutBytes = result["stdoutBytes"]?.jsonPrimitive?.content?.toLongOrNull(),
            stderrBytes = result["stderrBytes"]?.jsonPrimitive?.content?.toLongOrNull(),
            truncated = result["truncated"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
        )
    }

    private fun sendCancel(requestId: Long) {
        val cancelId = nextId.getAndIncrement()
        socket?.send(buildJsonObject {
            put("id", cancelId)
            put("method", "cancel")
            put("params", buildJsonObject { put("requestId", requestId) })
            put("ts", System.currentTimeMillis())
        }.toString())
    }

    override fun close(code: Int, reason: String) {
        stopped.set(true)
        retryJob?.cancel()
        retryJob = null
        socket?.close(code, reason)
        socket = null
        failPending(reason)
    }

    private fun failPending(reason: String, cause: Throwable? = null) {
        val failure = RemoteChannelException(
            ExecPlaneErrorCode.CHANNEL_DISCONNECTED,
            reason.ifBlank { "WebSocket connection was closed" },
            cause,
        )
        pending.values.forEach { it.completeExceptionally(failure) }
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
                    val reply = request("capabilities", buildJsonObject { put("protocol", EXECPLANE_PROTOCOL_VERSION) }, 10_000)
                    if (!reply.ok) throw reply.remoteFailure("Capability handshake failed")
                    val result = ExecPlaneJson.codec.decodeFromJsonElement<CapabilitiesResult>(
                        reply.result ?: error("Capability handshake result is missing"),
                    )
                    require(result.protocol == EXECPLANE_PROTOCOL_VERSION) { "Unsupported executor protocol ${result.protocol}" }
                    require("exec" in result.caps) { "Executor does not support exec" }
                    result
                }.getOrElse { error ->
                    webSocket.close(1002, "Capability handshake failed: ${error.message}")
                    return@launch
                }
                handshake = discovered
                capabilities = discovered.caps
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
            val response = runCatching { ExecPlaneJson.codec.decodeFromString<RpcResponse>(text) }.getOrNull()
            if (response != null) {
                pending[response.id]?.complete(response)
                manager.markSeen(config.name, id)
                return
            }
            val event = runCatching { ExecPlaneJson.codec.decodeFromString<RpcEvent>(text) }.getOrNull() ?: return
            if (event.event == "exec.output") {
                val output = runCatching {
                    ExecPlaneJson.codec.decodeFromJsonElement<ExecOutputEvent>(event.data)
                }.getOrNull() ?: return
                outputCallbacks[output.requestId]?.invoke(output.stream, output.data)
                manager.markSeen(config.name, id)
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!isCurrent(webSocket, generation)) return
            socket = null
            failPending(reason.ifBlank { "WebSocket connection was closed" })
            manager.disconnect(config.name, id)
            scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!isCurrent(webSocket, generation)) return
            socket = null
            failPending("WebSocket connection failed: ${t.message ?: t.javaClass.simpleName}", t)
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
