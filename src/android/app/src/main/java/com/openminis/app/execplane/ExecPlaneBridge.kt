package com.openminis.app.execplane

import android.util.Log
import com.openminis.app.execplane.connection.ConnectionManager
import com.openminis.app.execplane.connection.ExecutorConnection
import com.openminis.app.execplane.protocol.ConnectionDirection
import com.openminis.app.execplane.protocol.EXECPLANE_PROTOCOL_VERSION
import com.openminis.app.execplane.protocol.ExecPlaneErrorCode
import com.openminis.app.execplane.protocol.ExecPlaneJson
import com.openminis.app.execplane.protocol.ExecutorTrust
import com.openminis.app.execplane.protocol.ProtocolValidator
import com.openminis.app.execplane.protocol.RegisterParams
import com.openminis.app.execplane.protocol.RpcError
import com.openminis.app.execplane.protocol.RpcRequest
import com.openminis.app.execplane.protocol.RpcResponse
import com.openminis.app.execplane.protocol.ValidationResult
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
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

/** Reverse WS connection manager. It intentionally exposes no exec dispatch yet. */
class ExecPlaneBridge(
    private val settings: ExecPlaneSettingsRepository,
    val connections: ConnectionManager = ConnectionManager(),
) {
    private val lock = Any()
    private val _status = MutableStateFlow(WsBridgeStatus())
    val status: StateFlow<WsBridgeStatus> = _status.asStateFlow()
    private var server: ReverseServer? = null
    private val forwardClient = OkHttpClient.Builder()
        .pingInterval(20, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private val forwardConnections = ConcurrentHashMap<String, ForwardConnection>()

    fun apply(enabled: Boolean = settings.enabled.value, port: Int = settings.port.value) {
        if (!enabled) stop() else start(port)
    }

    fun start(port: Int = settings.port.value) {
        synchronized(lock) {
            if (server?.listenPort == port && _status.value.state == WsBridgeState.LISTENING) return
            stopLocked()
            _status.value = WsBridgeStatus(WsBridgeState.STARTING, port)
            ReverseServer(port, settings.token()).also {
                server = it
                runCatching { it.start() }.onFailure { error -> reportError(port, error) }
            }
        }
    }

    fun stop() = synchronized(lock) { stopLocked() }

    fun connect(config: ForwardServerConfig) {
        forwardConnections.remove(config.id)?.close(1000, "Reconnecting")
        ForwardConnection(config, connections, forwardClient).also {
            forwardConnections[config.id] = it
            it.connect()
        }
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

    suspend fun exec(name: String, command: String, timeoutMs: Long = 600_000): RemoteExecResult {
        val connection = connections.connection(name) as? RemoteCommandConnection
            ?: throw RemoteChannelException("WebSocket Server is offline")
        return connection.exec(command, timeoutMs)
    }

    private fun stopLocked() {
        val active = server
        server = null
        runCatching { active?.closePeers() }
        runCatching { active?.stop(1_000) }
        _status.value = WsBridgeStatus()
    }

    private fun reportError(port: Int, error: Throwable) {
        Log.w(TAG, "WS bridge failed on 127.0.0.1:$port: ${error.message}")
        _status.value = WsBridgeStatus(WsBridgeState.ERROR, port, error.message ?: error.javaClass.simpleName)
    }

    private inner class ReverseServer(
        val listenPort: Int,
        private val expectedToken: String,
    ) : WebSocketServer(InetSocketAddress("127.0.0.1", listenPort)) {
        private val peers = ConcurrentHashMap<WebSocket, Peer>()
        private val pending = ConcurrentHashMap<Long, CompletableDeferred<RpcResponse>>()

        fun closePeers() {
            peers.keys.forEach { it.close(1001, "Bridge stopped") }
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
            peer.execHandler = { command, timeoutMs -> executeReverse(peer, command, timeoutMs) }
            peers[conn] = peer
        }

        override fun onMessage(conn: WebSocket, message: String) {
            val peer = peers[conn] ?: return
            val response = runCatching { ExecPlaneJson.codec.decodeFromString<RpcResponse>(message) }.getOrNull()
            if (response != null) {
                pending[response.id]?.complete(response)
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
                // No remote shell until Guard + audit are implemented.
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
            peer.name?.let { this@ExecPlaneBridge.connections.disconnect(it, peer.id) }
            peer.name = params.name
            this@ExecPlaneBridge.connections.register(peer, params)
            sendOk(peer.socket, request.id, JsonObject(emptyMap()))
        }

        override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
            peers.remove(conn)?.let { peer -> peer.name?.let { this@ExecPlaneBridge.connections.disconnect(it, peer.id) } }
        }

        override fun onError(conn: WebSocket?, ex: Exception) {
            if (conn == null) reportError(listenPort, ex) else Log.w(TAG, "WS peer error: ${ex.message}")
        }

        private suspend fun executeReverse(peer: Peer, command: String, timeoutMs: Long): RemoteExecResult {
            val requestId = REQUEST_IDS.getAndIncrement()
            val waiter = CompletableDeferred<RpcResponse>()
            pending[requestId] = waiter
            val params = buildJsonObject { put("cmd", command); put("timeoutMs", timeoutMs) }
            val request = buildJsonObject {
                put("id", requestId); put("method", "exec"); put("params", params); put("ts", System.currentTimeMillis())
            }
            if (!peer.socket.isOpen) {
                pending.remove(requestId)
                error("WebSocket Server is offline")
            }
            peer.socket.send(request.toString())
            val response = try { withTimeout(timeoutMs + 5_000) { waiter.await() } }
                finally { pending.remove(requestId) }
            if (!response.ok) error(response.error?.message ?: "Remote command failed")
            val result = response.result?.jsonObject ?: JsonObject(emptyMap())
            return RemoteExecResult(
                stdout = result["stdout"]?.jsonPrimitive?.content.orEmpty(),
                stderr = result["stderr"]?.jsonPrimitive?.content.orEmpty(),
                exitCode = result["exitCode"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                durationMs = result["durationMs"]?.jsonPrimitive?.content?.toLongOrNull(),
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
        @Volatile var execHandler: (suspend (String, Long) -> RemoteExecResult)? = null
        override val direction = ConnectionDirection.REVERSE
        override suspend fun exec(command: String, timeoutMs: Long): RemoteExecResult =
            execHandler?.invoke(command, timeoutMs) ?: error("Command channel is not ready")
        override fun close(code: Int, reason: String) = socket.close(code, reason)
    }

    companion object {
        private const val TAG = "ExecPlaneBridge"
        private val REQUEST_IDS = AtomicLong(1_000_000)

        fun constantTimeEquals(provided: String?, expected: String): Boolean {
            if (provided == null || provided.length != expected.length || expected.isEmpty()) return false
            var diff = 0
            expected.indices.forEach { diff = diff or (provided[it].code xor expected[it].code) }
            return diff == 0
        }
    }
}
