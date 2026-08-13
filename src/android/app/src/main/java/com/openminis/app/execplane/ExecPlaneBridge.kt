package com.openminis.app.execplane

import android.util.Log
import com.openminis.app.execplane.connection.ConnectionManager
import com.openminis.app.execplane.connection.ExecutorConnection
import com.openminis.app.execplane.protocol.CapabilitiesResult
import com.openminis.app.execplane.protocol.ConnectionDirection
import com.openminis.app.execplane.protocol.EXECPLANE_PROTOCOL_VERSION
import com.openminis.app.execplane.protocol.ExecOutputEvent
import com.openminis.app.execplane.protocol.ExecutorLimits
import com.openminis.app.execplane.protocol.ExecPlaneErrorCode
import com.openminis.app.execplane.protocol.ExecPlaneJson
import com.openminis.app.execplane.protocol.ExecutorTrust
import com.openminis.app.execplane.protocol.ProtocolValidator
import com.openminis.app.execplane.protocol.RegisterParams
import com.openminis.app.execplane.protocol.RpcError
import com.openminis.app.execplane.protocol.RpcEvent
import com.openminis.app.execplane.protocol.RpcRequest
import com.openminis.app.execplane.protocol.RpcResponse
import com.openminis.app.execplane.protocol.ValidationResult
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer

enum class WsBridgeState { STOPPED, STARTING, LISTENING, ERROR }

data class WsBridgeStatus(
    val state: WsBridgeState = WsBridgeState.STOPPED,
    val port: Int? = null,
    val error: String? = null,
)

/** Application-scoped WS execution bridge for forward servers and reverse executors. */
class ExecPlaneBridge(
    private val settings: ExecPlaneSettingsRepository,
    val connections: ConnectionManager = ConnectionManager(),
) {
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _status = MutableStateFlow(WsBridgeStatus())
    val status: StateFlow<WsBridgeStatus> = _status.asStateFlow()
    private var server: ReverseServer? = null
    private var listenerRetryJob: Job? = null
    private val forwardClient = OkHttpClient.Builder()
        .pingInterval(20, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private val forwardConnections = ConcurrentHashMap<String, ForwardConnection>()
    private val commandLimiter = SandboxConcurrencyLimiter(settings::concurrencyLimit)

    fun apply(enabled: Boolean = settings.enabled.value, port: Int = settings.port.value) {
        if (!enabled) stop() else start(port)
    }

    fun start(port: Int = settings.port.value) {
        synchronized(lock) {
            listenerRetryJob?.cancel()
            listenerRetryJob = null
            startLocked(port)
        }
    }

    private fun startLocked(port: Int) {
        if (server?.listenPort == port && _status.value.state == WsBridgeState.LISTENING) return
        stopServerLocked()
        _status.value = WsBridgeStatus(WsBridgeState.STARTING, port)
        ReverseServer(port, settings.token()).also {
            server = it
            runCatching { it.start() }.onFailure { error ->
                reportError(port, error)
                scheduleListenerRetryLocked(port)
            }
        }
        // Java-WebSocket starts asynchronously. If bind/start fails later,
        // onError(null, ...) schedules the same self-healing retry loop.
    }

    fun stop() = synchronized(lock) {
        listenerRetryJob?.cancel()
        listenerRetryJob = null
        stopLocked()
    }

    fun connect(config: ForwardServerConfig) {
        forwardConnections.remove(config.id)?.close(1000, "Reconnecting")
        ForwardConnection(config, connections, forwardClient).also {
            forwardConnections[config.id] = it
            it.connect()
        }
    }

    fun reloadSettings() {
        apply()
        forwardConnections.values.forEach { it.close(1000, "Settings restored") }
        forwardConnections.clear()
        settings.forwardServers.value.filter { it.enabled && it.token.isNotBlank() }.forEach(::connect)
    }

    fun handshake(name: String): CapabilitiesResult? {
        val resolved = connections.resolveOnlineName(name) ?: return null
        return (connections.connection(resolved) as? RemoteCommandConnection)?.handshake
    }

    fun disconnect(name: String): Boolean = connections.disconnectByUser(name)

    fun delete(name: String): Boolean {
        val forward = settings.forwardServers.value.firstOrNull { it.name == name }
        if (forward != null) {
            forwardConnections.remove(forward.id)?.close(1000, "Deleted by user")
            settings.deleteForwardServer(forward.id)
        }
        return connections.delete(name) || forward != null
    }

    suspend fun request(name: String, method: String, params: JsonObject, timeoutMs: Long = 600_000): RpcResponse {
        val connection = remoteConnection(name)
        if (method != "capabilities" && !supports(connection.capabilities, method)) {
            throw RemoteExecutionException(
                ExecPlaneErrorCode.CAPABILITY_UNSUPPORTED,
                "$method is not supported by '$name'",
            )
        }
        return connection.request(method, params, timeoutMs)
    }

    private fun supports(caps: Set<String>, method: String): Boolean = when {
        method.startsWith("transfer.") ->
            "transfer.push" in caps || "transfer.pull" in caps
        else -> method in caps
    }

    suspend fun dispatch(
        name: String,
        payload: String,
        timeoutMs: Long = 600_000,
        outputCallback: ((String) -> Unit)? = null,
    ): RemoteDispatchResult {
        val resolvedName = connections.resolveOnlineName(name)
            ?: throw RemoteChannelException(
                ExecPlaneErrorCode.CHANNEL_EXECUTOR_OFFLINE,
                "WebSocket Server is offline",
            )
        return commandLimiter.withPermit(resolvedName) {
            val connection = remoteConnection(resolvedName)
            val streamedBytes = AtomicLong(0L)
            val outputExceeded = java.util.concurrent.atomic.AtomicBoolean(false)
            val response = connection.requestWithOutput(
                method = "dispatch",
                params = buildJsonObject {
                    put("payload", payload)
                    put("timeoutMs", timeoutMs)
                },
                timeoutMs = timeoutMs,
            ) { _, data ->
                val size = data.toByteArray(Charsets.UTF_8).size
                if (size > SandboxDispatchService.MAX_EVENT_BYTES ||
                    streamedBytes.addAndGet(size.toLong()) > SandboxDispatchService.MAX_FINAL_OUTPUT_BYTES
                ) {
                    outputExceeded.set(true)
                } else if (!outputExceeded.get()) {
                    outputCallback?.invoke(data)
                }
            }
            if (outputExceeded.get()) throw RemoteExecutionException(
                ExecPlaneErrorCode.EXEC_OUTPUT_LIMIT,
                "Dispatch output exceeded Android limit",
            )
            if (!response.ok) throw response.remoteFailure("Remote dispatch failed")
            SandboxDispatchService(this).decodeResult(response.result).let {
                RemoteDispatchResult(it.output, it.durationMs, it.truncated)
            }
        }
    }

    suspend fun exec(
        name: String,
        command: String,
        timeoutMs: Long = 600_000,
        env: Map<String, String> = emptyMap(),
        outputCallback: ((String, String) -> Unit)? = null,
    ): RemoteExecResult {
        val resolvedName = connections.resolveOnlineName(name)
            ?: throw RemoteChannelException(
                ExecPlaneErrorCode.CHANNEL_EXECUTOR_OFFLINE,
                "WebSocket Server is offline",
            )
        return commandLimiter.withPermit(resolvedName) {
            remoteConnection(resolvedName).exec(command, timeoutMs, env, outputCallback)
        }
    }

    private fun remoteConnection(name: String): RemoteCommandConnection {
        val resolvedName = connections.resolveOnlineName(name)
            ?: throw RemoteChannelException(
                ExecPlaneErrorCode.CHANNEL_EXECUTOR_OFFLINE,
                "WebSocket Server is offline",
            )
        return connections.connection(resolvedName) as? RemoteCommandConnection
            ?: throw RemoteChannelException(
                ExecPlaneErrorCode.CHANNEL_EXECUTOR_OFFLINE,
                "WebSocket Server is offline",
            )
    }

    private fun stopLocked() {
        stopServerLocked()
        _status.value = WsBridgeStatus()
    }

    private fun stopServerLocked() {
        val active = server
        server = null
        runCatching { active?.closePeers() }
        runCatching { active?.stop(1_000) }
    }

    private fun reportError(port: Int, error: Throwable) {
        Log.w(TAG, "WS bridge failed on 127.0.0.1:$port: ${error.message}")
        _status.value = WsBridgeStatus(WsBridgeState.ERROR, port, error.message ?: error.javaClass.simpleName)
    }

    private fun scheduleListenerRetryLocked(port: Int) {
        if (!settings.enabled.value || listenerRetryJob?.isActive == true) return
        listenerRetryJob = scope.launch {
            var waitMs = LISTENER_RETRY_INITIAL_MS
            while (isActive && settings.enabled.value) {
                delay(waitMs)
                synchronized(lock) {
                    if (!settings.enabled.value || _status.value.state == WsBridgeState.LISTENING) return@launch
                    Log.i(TAG, "Retrying WS bridge on 127.0.0.1:$port")
                    startLocked(port)
                }
                waitMs = (waitMs * 2).coerceAtMost(LISTENER_RETRY_MAX_MS)
            }
        }
    }

    private inner class ReverseServer(
        val listenPort: Int,
        private val expectedToken: String,
    ) : WebSocketServer(InetSocketAddress("127.0.0.1", listenPort)) {
        private val peers = ConcurrentHashMap<WebSocket, Peer>()

        fun closePeers() {
            peers.values.forEach { peer ->
                peer.failPending("WebSocket bridge stopped")
                peer.socket.close(1001, "Bridge stopped")
            }
        }

        override fun onStart() {
            connectionLostTimeout = 15
            _status.value = WsBridgeStatus(WsBridgeState.LISTENING, listenPort)
            Log.i(TAG, "WS bridge listening on 127.0.0.1:$listenPort")
        }

        override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
            val supplied = handshake.getFieldValue("X-Minis-Token").ifBlank {
                handshake.getFieldValue("Authorization").removePrefix("Bearer ").trim()
            }
            if (!constantTimeEquals(supplied, expectedToken)) {
                conn.close(1008, "Unauthorized")
                return
            }
            val peer = Peer(UUID.randomUUID().toString(), conn)
            peer.execHandler = { command, timeoutMs, env, callback -> executeReverse(peer, command, timeoutMs, env, callback) }
            peer.requestHandler = { method, params, timeoutMs -> requestReverse(peer, method, params, timeoutMs) }
            peer.requestHandlerWithId = { method, params, timeoutMs, requestId ->
                requestReverse(peer, method, params, timeoutMs, requestId)
            }
            peers[conn] = peer
        }

        override fun onMessage(conn: WebSocket, message: String) {
            val peer = peers[conn] ?: return
            val response = runCatching { ExecPlaneJson.codec.decodeFromString<RpcResponse>(message) }.getOrNull()
            if (response != null) {
                peer.completePending(response)
                return
            }
            val event = runCatching { ExecPlaneJson.codec.decodeFromString<RpcEvent>(message) }.getOrNull()
            if (event != null) {
                if (event.event == "exec.output" || event.event == "dispatch.output") runCatching {
                    val output = ExecPlaneJson.codec.decodeFromJsonElement<ExecOutputEvent>(event.data)
                    peer.emitOutput(output)
                }
                return
            }
            val request = runCatching { ExecPlaneJson.codec.decodeFromString<RpcRequest>(message) }
                .getOrElse {
                    sendError(conn, 0, ExecPlaneErrorCode.EXEC_INVALID_REQUEST, "Invalid request")
                    return
                }
            if (request.method != "register" && peer.name == null) {
                sendError(conn, request.id, ExecPlaneErrorCode.EXEC_FORBIDDEN, "Register first")
                return
            }
            when (request.method) {
                "register" -> register(peer, request)
                "ping" -> {
                    peer.name?.let { this@ExecPlaneBridge.connections.markSeen(it, peer.id) }
                    sendOk(conn, request.id, JsonObject(emptyMap()))
                }
                "status" -> {
                    peer.name?.let { this@ExecPlaneBridge.connections.markSeen(it, peer.id) }
                    sendOk(conn, request.id, JsonObject(emptyMap()))
                }
                // Executors may receive outbound exec/file RPC from the App, but
                // they cannot ask the App to execute inbound shell/file methods.
                "exec", "cancel", "file_get", "file_put" ->
                    sendError(conn, request.id, ExecPlaneErrorCode.EXEC_FORBIDDEN, "Method is not enabled")
                else -> sendError(conn, request.id, ExecPlaneErrorCode.EXEC_METHOD_NOT_FOUND, "Unknown method")
            }
        }

        private fun register(peer: Peer, request: RpcRequest) {
            val decoded = runCatching {
                ExecPlaneJson.codec.decodeFromString<RegisterParams>(request.params.toString())
            }.getOrElse {
                sendError(peer.socket, request.id, ExecPlaneErrorCode.EXEC_INVALID_PARAMS, "Invalid register params")
                return
            }
            // Trust is assigned by the brain, never accepted from an executor.
            val params = decoded.copy(trust = ExecutorTrust.LOCAL)
            val validation = ProtocolValidator.validateRegister(params)
            if (validation is ValidationResult.Invalid) {
                send(peer.socket, RpcResponse(request.id, false, error = validation.error))
                return
            }
            if (conflictsWithForwardServer(params.name, settings.forwardServers.value)) {
                sendError(
                    peer.socket,
                    request.id,
                    ExecPlaneErrorCode.EXEC_FORBIDDEN,
                    "Executor name conflicts with a saved forward server",
                )
                return
            }
            peer.name?.let { this@ExecPlaneBridge.connections.disconnect(it, peer.id) }
            peer.name = params.name
            peer.capabilities = params.caps
            peer.handshake = CapabilitiesResult(
                protocol = params.protocol,
                serverId = peer.id,
                name = params.name,
                caps = params.caps,
                limits = params.limits,
                instructionSet = params.instructionSet,
            )
            this@ExecPlaneBridge.connections.register(peer, params)
            sendOk(peer.socket, request.id, JsonObject(emptyMap()))
        }

        override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
            peers.remove(conn)?.let { peer ->
                peer.failPending(reason.ifBlank { "WebSocket connection was closed" })
                peer.name?.let { this@ExecPlaneBridge.connections.disconnect(it, peer.id) }
            }
        }

        override fun onError(conn: WebSocket?, ex: Exception) {
            if (conn == null) {
                synchronized(lock) {
                    if (server === this) {
                        reportError(listenPort, ex)
                        scheduleListenerRetryLocked(listenPort)
                    }
                }
            } else {
                peers[conn]?.failPending("WebSocket connection failed: ${ex.message ?: ex.javaClass.simpleName}", ex)
                Log.w(TAG, "WS peer error: ${ex.message}")
            }
        }

        private suspend fun requestReverse(
            peer: Peer,
            method: String,
            params: JsonObject,
            timeoutMs: Long,
            fixedRequestId: Long? = null,
        ): RpcResponse {
            val requestId = fixedRequestId ?: REQUEST_IDS.getAndIncrement()
            val waiter = peer.addPending(requestId)
            val request = buildJsonObject {
                put("id", requestId); put("method", method); put("params", params); put("ts", System.currentTimeMillis())
            }
            if (!peer.socket.isOpen) {
                peer.removePending(requestId)
                throw RemoteChannelException(
                    ExecPlaneErrorCode.CHANNEL_EXECUTOR_OFFLINE,
                    "WebSocket Server is offline",
                )
            }
            try {
                peer.socket.send(request.toString())
            } catch (error: Throwable) {
                peer.removePending(requestId)
                throw RemoteChannelException(
                    ExecPlaneErrorCode.CHANNEL_DISCONNECTED,
                    "WebSocket request could not be sent",
                    error,
                )
            }
            return try {
                withTimeout(timeoutMs + 5_000) { waiter.await() }
            } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
                if (method == "exec" || method == "dispatch") peer.socket.send(buildJsonObject {
                    put("id", REQUEST_IDS.getAndIncrement()); put("method", "cancel")
                    put("params", buildJsonObject { put("requestId", requestId) }); put("ts", System.currentTimeMillis())
                }.toString())
                throw RemoteChannelException(
                    ExecPlaneErrorCode.CHANNEL_TIMEOUT,
                    "WebSocket request timed out after ${timeoutMs}ms",
                    error,
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                if (method == "exec" || method == "dispatch") peer.socket.send(buildJsonObject {
                    put("id", REQUEST_IDS.getAndIncrement()); put("method", "cancel")
                    put("params", buildJsonObject { put("requestId", requestId) }); put("ts", System.currentTimeMillis())
                }.toString())
                throw cancelled
            } finally {
                peer.removePending(requestId)
            }
        }

        private suspend fun executeReverse(
            peer: Peer,
            command: String,
            timeoutMs: Long,
            env: Map<String, String>,
            outputCallback: ((String, String) -> Unit)?,
        ): RemoteExecResult {
            val params = buildJsonObject {
                put("cmd", buildJsonArray {
                    add(kotlinx.serialization.json.JsonPrimitive("/bin/sh"))
                    add(kotlinx.serialization.json.JsonPrimitive("-lc"))
                    add(kotlinx.serialization.json.JsonPrimitive(command))
                })
                put("shell", true)
                put("timeoutMs", timeoutMs)
                put("env", buildJsonObject { env.forEach { (key, value) -> put(key, value) } })
                put("envMode", "overlay")
            }
            val requestId = REQUEST_IDS.getAndIncrement()
            outputCallback?.let { peer.addOutputCallback(requestId, it) }
            val response = try {
                requestReverse(peer, "exec", params, timeoutMs, requestId)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                peer.socket.send(buildJsonObject {
                    put("id", REQUEST_IDS.getAndIncrement()); put("method", "cancel")
                    put("params", buildJsonObject { put("requestId", requestId) }); put("ts", System.currentTimeMillis())
                }.toString())
                throw cancelled
            } finally {
                peer.removeOutputCallback(requestId)
            }
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

        private fun sendOk(conn: WebSocket, id: Long, result: JsonObject) =
            send(conn, RpcResponse(id, true, result = result))

        private fun sendError(conn: WebSocket, id: Long, code: ExecPlaneErrorCode, message: String) =
            send(conn, RpcResponse(id, false, error = RpcError(code, message)))

        private fun send(conn: WebSocket, response: RpcResponse) {
            conn.send(ExecPlaneJson.codec.encodeToString(response))
        }
    }

    private class Peer(
        override val id: String,
        val socket: WebSocket,
    ) : RemoteCommandConnection {
        @Volatile var name: String? = null
        @Volatile override var capabilities: Set<String> = emptySet()
        @Volatile override var handshake: CapabilitiesResult? = null
        @Volatile var execHandler: (suspend (String, Long, Map<String, String>, ((String, String) -> Unit)?) -> RemoteExecResult)? = null
        @Volatile var requestHandler: (suspend (String, JsonObject, Long) -> RpcResponse)? = null
        @Volatile var requestHandlerWithId: (suspend (String, JsonObject, Long, Long) -> RpcResponse)? = null
        private val pending = ConnectionPendingRequests()
        private val outputCallbacks = ConcurrentHashMap<Long, (String, String) -> Unit>()
        override val direction = ConnectionDirection.REVERSE

        fun addPending(requestId: Long): CompletableDeferred<RpcResponse> = pending.add(requestId)

        fun removePending(requestId: Long) = pending.remove(requestId)

        fun completePending(response: RpcResponse): Boolean = pending.complete(response)
        fun addOutputCallback(requestId: Long, callback: (String, String) -> Unit) { outputCallbacks[requestId] = callback }
        fun removeOutputCallback(requestId: Long) { outputCallbacks.remove(requestId) }
        fun emitOutput(output: ExecOutputEvent) { outputCallbacks[output.requestId]?.invoke(output.stream, output.data) }

        fun failPending(reason: String, cause: Throwable? = null) {
            pending.failAll(RemoteChannelException(
                ExecPlaneErrorCode.CHANNEL_DISCONNECTED,
                reason.ifBlank { "WebSocket connection was closed" },
                cause,
            ))
        }

        internal fun pendingCount(): Int = pending.size()
        override suspend fun request(method: String, params: JsonObject, timeoutMs: Long): RpcResponse =
            requestHandler?.invoke(method, params, timeoutMs) ?: error("RPC channel is not ready")
        override suspend fun requestWithOutput(
            method: String,
            params: JsonObject,
            timeoutMs: Long,
            outputCallback: ((String, String) -> Unit)?,
        ): RpcResponse {
            val requestId = REQUEST_IDS.getAndIncrement()
            outputCallback?.let { addOutputCallback(requestId, it) }
            return try {
                requestHandlerWithId?.invoke(method, params, timeoutMs, requestId)
                    ?: error("RPC channel is not ready")
            } finally {
                removeOutputCallback(requestId)
            }
        }
        override suspend fun exec(
            command: String,
            timeoutMs: Long,
            env: Map<String, String>,
            outputCallback: ((String, String) -> Unit)?,
        ): RemoteExecResult = execHandler?.invoke(command, timeoutMs, env, outputCallback)
            ?: error("Command channel is not ready")
        override fun close(code: Int, reason: String) {
            failPending(reason)
            socket.close(code, reason)
        }
    }

    companion object {
        private const val TAG = "ExecPlaneBridge"
        private const val LISTENER_RETRY_INITIAL_MS = 1_000L
        private const val LISTENER_RETRY_MAX_MS = 30_000L
        private val REQUEST_IDS = AtomicLong(1_000_000)

        internal fun conflictsWithForwardServer(name: String, servers: List<ForwardServerConfig>): Boolean =
            servers.any { it.name.equals(name, ignoreCase = true) }

        fun constantTimeEquals(provided: String?, expected: String): Boolean {
            if (provided == null || provided.length != expected.length || expected.isEmpty()) return false
            var diff = 0
            expected.indices.forEach { diff = diff or (provided[it].code xor expected[it].code) }
            return diff == 0
        }
    }
}
