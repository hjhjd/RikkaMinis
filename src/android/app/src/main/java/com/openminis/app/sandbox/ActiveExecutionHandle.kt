package com.openminis.app.sandbox

/** Cancellation control for the command currently running in a PRoot session. */
internal fun interface ActiveExecutionHandle {
    fun cancel()
}
