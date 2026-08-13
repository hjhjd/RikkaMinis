package com.openminis.app.execplane.connection

import com.openminis.app.execplane.protocol.ConnectionDirection
import com.openminis.app.execplane.protocol.ExecutorResources
import com.openminis.app.execplane.protocol.ExecutorTrust
import com.openminis.app.execplane.protocol.RegisterParams
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface ExecutorConnection {
    val id: String
    val direction: ConnectionDirection
    fun close(code: Int, reason: String)
}

data class ExecutorSnapshot(
    val name: String,
    val connectionId: String,
    val direction: ConnectionDirection,
    val caps: Set<String>,
    val resources: ExecutorResources,
    val trust: ExecutorTrust,
    val tags: Set<String>,
    val online: Boolean,
    val registeredAtMs: Long,
    val lastSeenAtMs: Long,
)

/** Thread-safe executor registry. Transport code owns connections; this class owns identity. */
class ConnectionManager(
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private data class Entry(
        val connection: ExecutorConnection,
        val snapshot: ExecutorSnapshot,
    )

    private val lock = Any()
    private val entries = ConcurrentHashMap<String, Entry>()
    private val mutableSnapshots = MutableStateFlow<Map<String, ExecutorSnapshot>>(emptyMap())
    val snapshots: StateFlow<Map<String, ExecutorSnapshot>> = mutableSnapshots.asStateFlow()

    /** Registers [connection]. A newer connection atomically replaces and closes an old namesake. */
    fun register(connection: ExecutorConnection, params: RegisterParams): ExecutorSnapshot {
        val timestamp = nowMs()
        val snapshot = ExecutorSnapshot(
            name = params.name,
            connectionId = connection.id,
            direction = connection.direction,
            caps = params.caps.toSet(),
            resources = params.resources,
            trust = params.trust,
            tags = params.tags.toSet(),
            online = true,
            registeredAtMs = timestamp,
            lastSeenAtMs = timestamp,
        )
        val replaced = synchronized(lock) {
            val old = entries.put(params.name, Entry(connection, snapshot))
            publishLocked()
            old?.connection?.takeIf { it.id != connection.id }
        }
        replaced?.close(4001, "Replaced by a newer connection")
        return snapshot
    }

    fun markSeen(name: String, connectionId: String): Boolean = synchronized(lock) {
        val current = entries[name] ?: return@synchronized false
        if (current.connection.id != connectionId) return@synchronized false
        entries[name] = current.copy(snapshot = current.snapshot.copy(lastSeenAtMs = nowMs()))
        publishLocked()
        true
    }

    /** Ignores stale close callbacks from a connection already replaced by a newer one. */
    fun disconnect(name: String, connectionId: String): Boolean = synchronized(lock) {
        val current = entries[name] ?: return@synchronized false
        if (current.connection.id != connectionId) return@synchronized false
        entries.remove(name)
        mutableSnapshots.value = mutableSnapshots.value +
            (name to current.snapshot.copy(online = false, lastSeenAtMs = nowMs()))
        true
    }

    fun rememberOffline(
        name: String,
        connectionId: String,
        direction: ConnectionDirection,
        caps: Set<String>,
        trust: ExecutorTrust,
        tags: Set<String> = emptySet(),
    ) = synchronized(lock) {
        if (entries.containsKey(name)) return@synchronized
        val previous = mutableSnapshots.value[name]
        mutableSnapshots.value = mutableSnapshots.value + (name to ExecutorSnapshot(
            name = name,
            connectionId = connectionId,
            direction = direction,
            caps = caps,
            resources = previous?.resources ?: ExecutorResources(),
            trust = trust,
            tags = tags,
            online = false,
            registeredAtMs = previous?.registeredAtMs ?: nowMs(),
            lastSeenAtMs = previous?.lastSeenAtMs ?: nowMs(),
        ))
    }

    fun online(name: String): ExecutorSnapshot? =
        entries[name]?.snapshot?.takeIf { it.online }

    /** Resolves a user-facing executor name without silently picking an ambiguous case variant. */
    fun resolveOnlineName(requested: String): String? {
        entries[requested]?.snapshot?.takeIf { it.online }?.let { return it.name }
        val matches = entries.values.asSequence()
            .map { it.snapshot }
            .filter { it.online && it.name.equals(requested, ignoreCase = true) }
            .map { it.name }
            .distinct()
            .take(2)
            .toList()
        return matches.singleOrNull()
    }

    fun connection(name: String): ExecutorConnection? = entries[name]?.connection

    /** User-initiated disconnect. Keeps an offline row for later inspection/deletion. */
    fun disconnectByUser(name: String): Boolean {
        val connection = synchronized(lock) { entries[name]?.connection } ?: return false
        // Mark offline before close so UI updates even if a transport doesn't
        // deliver its close callback (common with abrupt remote peers).
        disconnect(name, connection.id)
        connection.close(1000, "Disconnected by user")
        return true
    }

    /** Removes a saved/offline row. Online connections must be disconnected first. */
    fun delete(name: String): Boolean = synchronized(lock) {
        if (entries.containsKey(name)) return@synchronized false
        if (!mutableSnapshots.value.containsKey(name)) return@synchronized false
        mutableSnapshots.value = mutableSnapshots.value - name
        true
    }

    fun onlineMatching(requiredCaps: Set<String>): List<ExecutorSnapshot> =
        entries.values.asSequence()
            .map { it.snapshot }
            .filter { it.online && it.caps.containsAll(requiredCaps) }
            .sortedWith(compareBy<ExecutorSnapshot>({ it.trust.ordinal }, { it.name }))
            .toList()

    private fun publishLocked() {
        // Keep disconnected executors visible in Settings as history; an
        // active registration with the same name replaces its offline row.
        val offline = mutableSnapshots.value.filterValues { !it.online }
        mutableSnapshots.value = offline + entries.mapValues { it.value.snapshot }
    }
}
