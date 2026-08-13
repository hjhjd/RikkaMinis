package com.openminis.app.sandbox

import android.content.Context
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import com.openminis.app.data.repository.EnvVarRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Manages per-session persistent shell processes.
 *
 * Architecture:
 * - PRootKernel (rootfs + proot binary): global singleton, booted once
 * - PersistentShell: one per sessionId, owns its bind mounts and /bin/sh process
 * - Per-session Mutex: different sessions can run commands concurrently
 * - ConcurrentHashMap: thread-safe shell/mutex registry
 *
 * Concurrency guarantees:
 * - Same session: commands are serialized by the per-session Mutex
 * - Different sessions: run concurrently (each has its own Mutex)
 * - Shell creation: protected by globalLock to prevent duplicate shells
 * - Shell death: detected on next command, shell is recreated with same bind mounts
 */
object ExecutionCoordinator {

    private const val TAG = "ExecutionCoordinator"

    // [P2-proot-native-leak]
    // High-water mark (MB) for PRoot *child process* RSS. Normal operation is
    // ~35-55MB. A long-lived PRoot tracer leaks native memory monotonically
    // (measured 6.2-6.9GB on 2026-08-07, enough to OOM the device). We read
    // the child process RSS via PersistentShell.nativeRssMB — NOT
    // Debug.getNativeHeapAllocatedSize(), which reports the *app-process*
    // heap and never sees the leaked memory held by the forked PRoot tracer.
    // When a command returns (or while it runs) and the child RSS exceeds
    // this, we recycle the session's shell so the next getOrCreateShell
    // spawns a fresh PRoot at baseline.
    private const val NATIVE_HEAP_HIGH_WATER_MARK_MB = 512L

    // [P2-app-native-oom] High-water mark for app-process native heap
    // (Debug.getNativeHeapAllocatedSize). This catches the actual OOM path
    // that nativeRssMB() misses: the PRoot tracer stays at 3MB while the
    // app process's own native heap (talloc inside PRoot's in-process
    // components, DirectByteBuffers, LOS objects) balloons past Scudo's
    // limit. Normal is ~50-100MB; 200MB leaves buffer before the 512MB
    // Java heap limit which indirectly pressures native allocation.
    private const val APP_NATIVE_HEAP_HIGH_WATER_MARK_MB = 200L

    // [P2-app-native-oom] Java heap utilization threshold. When the agent
    // runs a dense tool-call sequence, Java heap climbs (crash case:
    // 395MB/512MB = 77%). Recycle the shell at 70% to prevent the
    // NativeAlloc GC storms that precede Scudo OOM.
    private const val JAVA_HEAP_PRESSURE_THRESHOLD = 0.70

    // [P2-app-native-oom] Maximum commands on a single shell before
    // forced recycle. The crash case was ~20 git/shell commands in 2.5
    // minutes. 30 is a safe upper bound — well above normal usage but
    // well below the accumulation that triggers Scudo OOM.
    private const val MAX_COMMANDS_PER_SHELL = 30
    // A shell idle this long is terminated to release its PRoot native
    // footprint. Generous above any agent transition gap (model thinking).
    private const val SHELL_IDLE_TIMEOUT_MS = 10 * 60 * 1000L  // 10 min
    // Sweep cadence for idle shell recycling (public for MinisApp sweeper).
    const val IDLE_SWEEP_INTERVAL_MS = 60 * 1000L              // 1 min

    data class CommandResult(
        val output: String,
        val exitCode: Int,
        val durationMs: Long,
        val truncated: Boolean = false,
        val timedOut: Boolean = false,
        val cancelled: Boolean = false,
        val sandboxName: String = "proot",
        val degraded: Boolean = false,
    )

    private lateinit var appContext: Context
    var envVarRepository: EnvVarRepository? = null

    /** Single owner for shell, lock, activity and environment state. */
    private val sessions = SessionExecutionStateRegistry()

    /**
     * Global lock used only for shell creation to prevent duplicate shells
     * when the same session's first command arrives concurrently.
     */
    private val globalLock = Mutex()

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** Optional sandbox router. Returning null means fall back to local PRoot. */
    @Volatile
    var externalExecutor: (suspend (String, String, Long, ((String) -> Unit)?, String?) -> CommandResult?)? = null

    /**
     * Execute a command in the selected sandbox. The external router preserves
     * this existing API and returns null only for channel-level fallback.
     */
    suspend fun execute(
        sessionId: String,
        command: String,
        timeout: Long = 600_000L,
        lineCallback: ((String) -> Unit)? = null,
        sandbox: String? = null,
    ): CommandResult {
        if (sandbox.equals("proot", ignoreCase = true)) {
            return executeLocal(sessionId, command, timeout, lineCallback)
        }
        externalExecutor?.invoke(sessionId, command, timeout, lineCallback, sandbox)?.let { return it }
        return executeLocal(sessionId, command, timeout, lineCallback)
    }

    /** Execute directly in built-in PRoot, bypassing sandbox routing. */
    suspend fun executeLocal(
        sessionId: String,
        command: String,
        timeout: Long = 600_000L,
        lineCallback: ((String) -> Unit)? = null
    ): CommandResult {
        // ConcurrentHashMap.getOrPut is not atomic, use putIfAbsent pattern
        val state = sessions.get(sessionId)
        val mutex = state.mutex

        return mutex.withLock {
            val startTime = System.currentTimeMillis()

            // Auto-boot PRoot if not already booted
            if (!PRootKernel.isBooted) {
                Log.i(TAG, "[$sessionId] Auto-booting PRootKernel")
                PRootKernel.boot(appContext)
            }

            // Get or create shell — protected by globalLock to avoid duplicate creation
            val shell = getOrCreateShell(sessionId, state)

            // Inject user-defined environment variables as a *full snapshot*
            // (T124a). Pass the previously-injected key set so applyEnvironment
            // can `unset` anything the user has since removed from settings;
            // otherwise the long-lived shell would keep stale values.
            val envVars = envVarRepository?.allAsDict() ?: emptyMap()
            val previousKeys = state.injectedEnvKeys
            if (envVars.isNotEmpty() || previousKeys.isNotEmpty()) {
                shell.applyEnvironment(envVars, previousKeys = previousKeys)
                state.injectedEnvKeys = envVars.keys.toSet()
            }

            val executionHandle = ActiveExecutionHandle { shell.stop() }
            state.activeExecution = executionHandle
            val result = try {
                shell.executeCommand(
                    command = command,
                    timeout = timeout,
                    lineCallback = lineCallback,
                    memoryMonitor = { rssMB ->
                        midCommandRecycleIfOversized(state, sessionId, rssMB)
                    // [P2-app-native-oom] Also watch the app-process native
                    // heap in-flight: Debug.getNativeHeapAllocatedSize is a
                    // cheap O(1) read, and the actual Scudo OOM happens
                    // *during* a command (crash case: 12s of NativeAlloc GC
                    // storms right before SIGABRT). PRoot-child RSS alone
                    // stays flat at 3MB while this grows.
                    val appNativeMB = Debug.getNativeHeapAllocatedSize() / (1024L * 1024L)
                    if (appNativeMB > APP_NATIVE_HEAP_HIGH_WATER_MARK_MB) {
                        Log.w(TAG, "[$sessionId] App native heap ${appNativeMB}MB crossed mark in-flight — recycling shell")
                        state.recycleRequested = true
                    }
                    },
                )
            } finally {
                if (state.activeExecution === executionHandle) state.activeExecution = null
                state.lastActivityMs = SystemClock.elapsedRealtime()
            }

            val durationMs = System.currentTimeMillis() - startTime
            val sanitized = TerminalSanitizer.sanitize(result.output)
            val truncated = TerminalSanitizer.truncateIfNeeded(sanitized)
            // Combine host-side and shell-side truncation flags
            val outputTruncated = result.truncated || truncated != sanitized
            val output = if (result.exitCode != 0 && result.exitCode != 124) {
                "$truncated\n(exit code: ${result.exitCode})"
            } else {
                truncated
            }

            // [P2-proot-native-leak] mark active; recycle if the PRoot *child
            // process* RSS ballooned past the safe ceiling (tracer leak). This
            // reads the real child (PersistentShell.nativeRssMB) — NOT app
            // Debug.getNativeHeapAllocatedSize(), which misses the leak.
            val prootRssMB = shell.nativeRssMB()
            if (prootRssMB > NATIVE_HEAP_HIGH_WATER_MARK_MB) {
                Log.w(TAG, "[$sessionId] PRoot child RSS > ${NATIVE_HEAP_HIGH_WATER_MARK_MB}MB after command — recycle requested")
                state.recycleRequested = true
            } else {
                Log.d(TAG, "[$sessionId] PRoot child RSS ${prootRssMB}MB — within mark")
            }

            // [P2-app-native-oom] Post-command pressure check. The PRoot child
            // RSS monitor above is blind to app-process native heap growth
            // (crash case 2026-08-09: child RSS constant 3MB while app native
            // heap + Java heap climbed to Scudo OOM in 12s of NativeAlloc GC
            // storms). Recycle on any of: app native heap > 200MB, Java heap
            // utilization > 70%, or > MAX_COMMANDS_PER_SHELL commands on one
            // shell. Recycling tears down the PRoot tracer so its in-process
            // talloc/mmap reservations are returned to the OS, restoring
            // memory to baseline for the next command.
            val nativeHeapMB = Debug.getNativeHeapAllocatedSize() / (1024L * 1024L)
            val runtime = Runtime.getRuntime()
            val javaHeapUsed = runtime.totalMemory() - runtime.freeMemory()
            val javaHeapFrac = if (runtime.maxMemory() > 0L) javaHeapUsed.toDouble() / runtime.maxMemory().toDouble() else 0.0

            val nativeOversized = nativeHeapMB > APP_NATIVE_HEAP_HIGH_WATER_MARK_MB
            val javaPressured = javaHeapFrac > JAVA_HEAP_PRESSURE_THRESHOLD
            val cmdOverLimit = shell.commandCount >= MAX_COMMANDS_PER_SHELL

            when {
                nativeOversized -> Log.w(
                    TAG, "[$sessionId] App native heap ${nativeHeapMB}MB > ${APP_NATIVE_HEAP_HIGH_WATER_MARK_MB}MB — recycling shell"
                )
                javaPressured -> Log.w(
                    TAG, "[$sessionId] Java heap ${(javaHeapFrac * 100).toInt()}% > ${(JAVA_HEAP_PRESSURE_THRESHOLD * 100).toInt()}% — recycling shell"
                )
                cmdOverLimit -> Log.w(
                    TAG, "[$sessionId] Shell command count ${shell.commandCount} >= $MAX_COMMANDS_PER_SHELL — recycling shell"
                )
            }
            if (nativeOversized || javaPressured || cmdOverLimit || state.recycleRequested) {
                sessionDidTerminate(sessionId)
            }

            CommandResult(
                output = output,
                exitCode = result.exitCode,
                durationMs = durationMs,
                truncated = outputTruncated,
                timedOut = result.exitCode == 124,
            )
        }
    }

    /** [P2-proot-native-leak] If the PRoot child is already dead mid-command
     * (it OOM'd), immediately recycle the session so the shell isn't held as
     * a zombie and the next command spawns fresh. Called from the executeCommand
     * in-flight monitor; rssMB of 0 means the child already died. */
    private fun midCommandRecycleIfOversized(state: SessionExecutionState, sessionId: String, rssMB: Long) {
        if (rssMB > NATIVE_HEAP_HIGH_WATER_MARK_MB) {
            Log.w(TAG, "[$sessionId] PRoot child RSS ${rssMB}MB crossed mark mid-command — recycle requested")
            state.recycleRequested = true
        }
    }

    /**
     * Get the existing shell for this session, or create a new one.
     * Uses globalLock to prevent two coroutines from simultaneously creating
     * a shell for the same session (e.g. if the old shell just died).
     */
    private suspend fun getOrCreateShell(sessionId: String, state: SessionExecutionState): PersistentShell {
        // Fast path: existing alive shell
        val existing = state.shell
        if (existing != null && existing.isAlive) {
            Log.d(TAG, "[$sessionId] Reusing existing shell")
            return existing
        }

        // Slow path: need to create (or recreate after crash)
        return globalLock.withLock {
            // Double-check after acquiring lock
            val recheck = state.shell
            if (recheck != null && recheck.isAlive) {
                Log.d(TAG, "[$sessionId] Reusing existing shell (post-lock)")
                return@withLock recheck
            }

            // Shell is dead or missing — clean up and create fresh
            if (recheck != null) {
                Log.w(TAG, "[$sessionId] Shell died unexpectedly, recreating")
                recheck.stop()
            }

            val bindMounts = buildSessionBindMounts(sessionId)
            val shell = PersistentShell(appContext, sessionId, bindMounts)
            state.shell = shell
            shell.ensureStarted()
            Log.i(TAG, "[$sessionId] Shell created with ${bindMounts.size} bind mounts")
            shell
        }
    }

    /**
     * Build bind mounts for a session:
     * - Session-level: workspace, attachments, offloads, browser → per-session dirs
     * - Global: memory, skills, shared → shared dirs across all sessions
     */
    private fun buildSessionBindMounts(sessionId: String): Map<String, String> {
        val filesDir = appContext.filesDir
        val mounts = linkedMapOf<String, String>()

        // Session-specific directories — written ONLY into this shell's local
        // bind-mount map, never the global PRootKernel.bindMounts. The global
        // map is shared across all sessions; per-session host paths would
        // otherwise overwrite each other (last session to boot wins), and the
        // interactive terminal (which reads the global map) would point at the
        // wrong session's dirs. Callers that need a session's per-session dir
        // must go through PRootKernel.resolveSessionHostPath(sessionId, ...).
        val sessionBase = File(filesDir, "minis-sessions/$sessionId")
        listOf("attachments", "offloads", "workspace", "browser").forEach { subdir ->
            val hostDir = File(sessionBase, subdir).also { it.mkdirs() }
            val linuxPath = "/var/minis/$subdir"
            mounts[linuxPath] = hostDir.absolutePath
        }

        // Global shared directories.
        // [T-android-mcp-bind-mount] mcp-servers MUST be here, not only in
        // PRootKernel.registerGlobalBindMounts: PersistentShell builds PRoot's
        // `-b` argv from THIS map, so a subdir missing here is invisible to the
        // shell that runs minis-mcp-cli — /var/minis/mcp-servers/servers.json
        // then resolves to the empty rootfs placeholder and `minis-mcp-cli list`
        // returns {"servers": [], "count": 0} even though the UI wrote the
        // server (the UI / debug.ls read via resolveHostPath, a separate map,
        // which is why they disagreed). Same trap as the external-mounts note
        // below.
        // Agent-scoped memory compatibility alias. Unlike skills/shared this
        // stays local to the PersistentShell and is never written into the
        // global bind map, so concurrent Agent sessions cannot overwrite it.
        val agentMemoryDir = PRootKernel.sessionMemoryDirectory(sessionId)
            ?: File(filesDir, "minis-agents/default/memory").also { it.mkdirs() }
        mounts["/var/minis/memory"] = agentMemoryDir.absolutePath

        val globalBase = File(filesDir, "minis-global")
        listOf("skills", "shared", "mcp-servers").forEach { subdir ->
            val hostDir = File(globalBase, subdir).also { it.mkdirs() }
            val linuxPath = "/var/minis/$subdir"
            mounts[linuxPath] = hostDir.absolutePath
            PRootKernel.addBindMount(linuxPath, hostDir.absolutePath)
        }

        // [T-logs-bind-android] AppLogger writes daily logs to files/logs/
        // (minis-YYYY-MM-DD.log). Bind them into /var/minis/logs so the agent's
        // shell can read the app's own runtime logs directly instead of
        // requiring a manual share from LogManagementScreen. Host dir is
        // guaranteed to exist: AppLogger.init() mkdirs it in Application.onCreate.
        val logsDir = File(filesDir, "logs").also { it.mkdirs() }
        val logsLinuxPath = "/var/minis/logs"
        mounts[logsLinuxPath] = logsDir.absolutePath
        PRootKernel.addBindMount(logsLinuxPath, logsDir.absolutePath)

        // T277: user-mounted external folders (SAF-picked trees). PersistentShell
        // uses this map verbatim as PRoot's `-b` argv, so any mount missing here
        // is invisible to the shell — `ls /var/minis/mounts/<name>/` then shows
        // only the empty rootfs placeholder. PRootKernel.bindMounts is kept in
        // sync separately by applyMountedFoldersSnapshot for the resolveHostPath
        // path (debug.ls, file_read, …) but does NOT feed the live PRoot argv.
        // Skip entries whose SAF tree URI didn't decode to a POSIX path
        // (cloud providers, unmounted removable storage).
        PRootKernel.mountedFoldersStore?.entries?.value?.forEach { entry ->
            val host = entry.resolvedHostPath ?: return@forEach
            val linuxPath = "/var/minis/mounts/${entry.name}"
            mounts[linuxPath] = host
        }

        return mounts
    }

    /**
     * Read-only diagnostics for same-module tests. There is intentionally no
     * singular "mounted session": each session owns an independent shell.
     */
    internal fun activeShellSessionIds(): Set<String> = sessions.entries().filter { it.second.shell != null }.map { it.first }.toSet()

    internal fun hasActiveShell(sessionId: String): Boolean =
        sessions.existing(sessionId)?.shell?.isAlive == true

    /**
     * Called when a session is closed. Stops and removes the shell.
     */
    fun sessionDidTerminate(sessionId: String) {
        val state = sessions.existing(sessionId) ?: return
        val shell = state.shell
        state.activeExecution?.cancel()
        state.activeExecution = null
        state.shell = null
        state.injectedEnvKeys = emptySet()
        state.lastActivityMs = 0L
        state.recycleRequested = false
        shell?.stop()
        if (shell != null) Log.i(TAG, "[$sessionId] Shell terminated")
    }

    /**
     * [P2-proot-native-leak] Recycle shells idle past SHELL_IDLE_TIMEOUT_MS.
     * Long-lived PRoot shells leak native memory monotonically; terminating
     * idle ones releases their footprint. Next command re-spawns fresh.
     */
    fun recycleIdleShells() {
        val now = SystemClock.elapsedRealtime()
        for ((sessionId, state) in sessions.entries()) {
            if (!state.mutex.tryLock()) continue
            try {
                val last = state.lastActivityMs
                val shell = state.shell ?: continue
                if (!state.isExecuting && !shell.isExecuting && last != 0L &&
                    (now - last) > SHELL_IDLE_TIMEOUT_MS) {
                    Log.w(TAG, "[$sessionId] shell idle ${(now - last) / 1000}s — recycling")
                    sessionDidTerminate(sessionId)
                }
            } finally {
                state.mutex.unlock()
            }
        }
    }

    /**
     * [P2-proot-resource-hygiene] Clear accumulated junk from the PROOT_TMP_DIR
     * cache and the guest rootfs temp dirs. PRoot writes transient files
     * (loader cache, temp scratch) under app cache; apk/busybox and command
     * output also accumulate in rootfs /tmp and /var/tmp. Over weeks these grow
     * and push disk pressure / IO overhead (the "use it a long time → flash
     * crash" class of report). Mirrors RikkaHub's cleanupAllTempDirs, which runs
     * after each command — here we sweep on a low cadence instead so we never
     * delete a file a *running* command still needs.
     *
     * Only runs when NO shell is mid-command, to avoid racing an in-flight
     * command's temp files (they get reaped on the next sweep).
     */
    fun cleanupProotTmp() {
        // Skip entirely if any shell is alive and possibly executing — safest
        // and sufficient given the sweeper runs every minute.
        if (sessions.values().any { it.shell?.isAlive == true }) return
        if (!::appContext.isInitialized) return
        val ctx = appContext
        val tmpDir = PRootKernel.getProotTmpDir(ctx)
        try {
            var removed = 0L
            tmpDir.listFiles()?.forEach { f ->
                if (f.deleteRecursively()) removed++
            }
            if (removed > 0) {
                Log.i(TAG, "cleanupProotTmp: cleared $removed entries from ${tmpDir.name}")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "cleanupProotTmp failed: ${t.message}")
        }
    }

    /**
     * Stop the shell for a specific session (e.g. user tapped cancel).
     * The shell process is killed; next command will recreate it.
     */
    fun stopCurrentCommand(sessionId: String? = null) {
        if (sessionId != null) {
            sessionDidTerminate(sessionId)
            Log.i(TAG, "[$sessionId] Shell stopped by user")
        } else {
            // Stop all sessions (legacy/fallback)
            sessions.values().forEach { state ->
                state.activeExecution?.cancel()
                state.activeExecution = null
                state.shell?.stop()
                state.shell = null
                state.injectedEnvKeys = emptySet()
                state.lastActivityMs = 0L
                state.recycleRequested = false
            }
            @Suppress("DEPRECATION")
            ShellExecutor.destroyCurrent()
        }
    }

    /** Legacy overload for callers without sessionId. */
    fun stopCurrentCommand() = stopCurrentCommand(sessionId = null)

    /**
     * Propagate a system-timezone change to every live shell.
     *
     * - Updates [PRootKernel.customEnvironment]["TZ"] so future shells inherit
     *   the new value at spawn time.
     * - Exports the new TZ into every already-running [PersistentShell] via
     *   `export TZ=...` on stdin.
     * - Asks [TerminalSession] to do the same for every live interactive PTY.
     *
     * Safe to call before PRoot has booted — it's a no-op in that case.
     */
    suspend fun broadcastTimezoneChange() {
        if (!PRootKernel.isBooted) return
        val tz = PRootKernel.updateTimezone()
        val tzMap = mapOf("TZ" to tz)
        for ((_, state) in sessions.entries()) {
            val shell = state.shell ?: continue
            if (shell.isAlive) shell.applyEnvironment(tzMap)
        }
        TerminalSession.broadcastTimezone(tz)
    }

    /**
     * Propagate a system-proxy change to every live shell. Exports all six
     * proxy keys as a block — empty strings when no proxy is configured, so
     * a disable transition clears the old values in-place without needing
     * a separate `unset`.
     *
     * Safe to call before PRoot has booted — it's a no-op in that case.
     */
    suspend fun broadcastProxyChange() {
        if (!PRootKernel.isBooted) return
        val env = PRootKernel.updateProxy(appContext)
        for ((_, state) in sessions.entries()) {
            val shell = state.shell ?: continue
            if (shell.isAlive) shell.applyEnvironment(env)
        }
        TerminalSession.broadcastProxy(env)
    }
}
