package com.openminis.app.sandbox

import kotlinx.coroutines.sync.Mutex

/** All mutable PRoot execution state owned by one chat session. */
internal class SessionExecutionState(
    val mutex: Mutex = Mutex(),
) {
    @Volatile var shell: PersistentShell? = null
    @Volatile var activeExecution: ActiveExecutionHandle? = null
    val isExecuting: Boolean get() = activeExecution != null
    @Volatile var lastActivityMs: Long = 0L
    @Volatile var recycleRequested: Boolean = false
    @Volatile var injectedEnvKeys: Set<String> = emptySet()
}
