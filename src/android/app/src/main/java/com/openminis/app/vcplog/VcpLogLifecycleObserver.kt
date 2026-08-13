package com.openminis.app.vcplog

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 应用前后台心跳切换，并在连续后台十分钟后冷断开 VCPLog。 */
class VcpLogLifecycleObserver(private val manager: VcpLogConnectionManager) : DefaultLifecycleObserver {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lingerJob: Job? = null
    @Volatile private var isForeground = true

    override fun onStart(owner: LifecycleOwner) {
        isForeground = true
        lingerJob?.cancel(); lingerJob = null
        manager.setForeground(true)
        manager.reconcile()
    }

    override fun onStop(owner: LifecycleOwner) {
        isForeground = false
        manager.setForeground(false)
        lingerJob?.cancel()
        lingerJob = scope.launch {
            delay(LINGER_MS)
            if (!isForeground) manager.stopForBackgroundLinger()
        }
    }

    companion object { private const val LINGER_MS = 10 * 60 * 1_000L }
}
