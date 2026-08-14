package com.openminis.app.sandbox

import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicInteger

/** All mutable PRoot execution state owned by one chat session. */
internal class SessionExecutionState(
    val mutex: Mutex = Mutex(),
) {
    @Volatile var shell: PersistentShell? = null
    @Volatile var activeExecution: ActiveExecutionHandle? = null
    val isExecuting: Boolean get() = activeExecution != null
    @Volatile var lastActivityMs: Long = 0L
    @Volatile var recycleRequested: Boolean = false
    @Volatile var closeRequested: Boolean = false
    val inFlightCalls = AtomicInteger(0)
    @Volatile var injectedEnvKeys: Set<String> = emptySet()
}
