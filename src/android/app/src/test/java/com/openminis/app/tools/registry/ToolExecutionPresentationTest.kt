package com.openminis.app.tools.registry

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolExecutionPresentationTest {
    @Test fun legalLifecycleReducesToCompletedState() {
        var p = ToolExecutionPresentation("i", "shell_execute", "proot", "PRoot")
        p = ToolExecutionPresentationReducer.reduce(p, ToolInvocationEvent.Started())
        p = ToolExecutionPresentationReducer.reduce(p, ToolInvocationEvent.Output("hello"))
        p = ToolExecutionPresentationReducer.reduce(p, ToolInvocationEvent.Completed(ToolInvocationResult("hello", true, durationMs=3, exitCode=0)))
        assertEquals(ToolExecutionStatus.SUCCEEDED, p.status)
        assertEquals(3L, p.durationMs)
        assertEquals(0, p.exitCode)
    }
    @Test fun terminalStateIgnoresLateOutput() {
        val done = ToolExecutionPresentation("i","x","s","s",status=ToolExecutionStatus.CANCELLED, cancelled=true)
        assertEquals(done, ToolExecutionPresentationReducer.reduce(done, ToolInvocationEvent.Output("late")))
    }
}
