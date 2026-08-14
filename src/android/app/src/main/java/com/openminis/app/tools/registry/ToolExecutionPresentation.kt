package com.openminis.app.tools.registry

enum class ToolExecutionStatus { PENDING, RUNNING, SUCCEEDED, FAILED, TIMED_OUT, CANCELLED }

data class ToolExecutionPresentation(
    val invocationId: String,
    val toolName: String,
    val sandboxId: String,
    val sandboxName: String,
    val status: ToolExecutionStatus = ToolExecutionStatus.PENDING,
    val output: String = "",
    val durationMs: Long? = null,
    val exitCode: Int? = null,
    val timedOut: Boolean = false,
    val cancelled: Boolean = false,
    val truncated: Boolean = false,
)

object ToolExecutionPresentationReducer {
    fun reduce(current: ToolExecutionPresentation, event: ToolInvocationEvent): ToolExecutionPresentation = when (event) {
        is ToolInvocationEvent.Started -> current.copy(status = ToolExecutionStatus.RUNNING)
        is ToolInvocationEvent.Output -> if (current.status.isTerminal) current else current.copy(output = current.output + event.text)
        is ToolInvocationEvent.Completed -> current.copy(
            status = when { event.result.cancelled -> ToolExecutionStatus.CANCELLED; event.result.timedOut -> ToolExecutionStatus.TIMED_OUT; event.result.success -> ToolExecutionStatus.SUCCEEDED; else -> ToolExecutionStatus.FAILED },
            output = event.result.output,
            durationMs = event.result.durationMs,
            exitCode = event.result.exitCode,
            timedOut = event.result.timedOut,
            cancelled = event.result.cancelled,
            truncated = event.result.truncated,
            sandboxId = event.result.sandboxId ?: current.sandboxId,
            sandboxName = event.result.sandboxName ?: current.sandboxName,
        )
        is ToolInvocationEvent.Failed -> current.copy(status = ToolExecutionStatus.FAILED, output = event.message)
        else -> current
    }
    private val ToolExecutionStatus.isTerminal get() = this in setOf(ToolExecutionStatus.SUCCEEDED, ToolExecutionStatus.FAILED, ToolExecutionStatus.TIMED_OUT, ToolExecutionStatus.CANCELLED)
}
