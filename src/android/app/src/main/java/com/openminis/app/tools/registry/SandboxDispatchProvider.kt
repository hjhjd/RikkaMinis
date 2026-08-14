package com.openminis.app.tools.registry

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.execplane.SandboxDispatchService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.callbackFlow
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/** Converts opaque WS dispatch into the common invocation event stream. */
class SandboxDispatchProvider(
    private val service: SandboxDispatchService,
    private val definitionProvider: () -> AgentToolDefinition,
    private val enabledProvider: () -> Boolean = { true },
) : ToolProvider {
    override val id = ID
    private val active = InvocationHandleRegistry()
    override fun tools() = listOf(ToolDescriptor(ToolIdentity(id, TOOL_NAME), definitionProvider(), enabled = enabledProvider()))

    override fun invoke(invocation: ToolInvocation): Flow<ToolInvocationEvent> = callbackFlow {
        val args=JSONObject(invocation.argumentsJson); val sandbox=args.getString("sandbox").trim(); val payload=args.getString("payload")
        require(sandbox.isNotEmpty() && !sandbox.equals("proot",true)); val timeout=args.optLong("timeout",900).coerceIn(1,3600)*1000
        val delay=args.optLong("delay",0).coerceIn(0,86400)
        val terminal=AtomicBoolean(false); val jobRef=java.util.concurrent.atomic.AtomicReference<kotlinx.coroutines.Job?>()
        active.register(invocation.invocationId) { jobRef.get()?.cancel() }
        trySend(ToolInvocationEvent.Started(args.optString("tool_title",TOOL_NAME)))
        val job=launch {
            try {
                if (delay > 0) kotlinx.coroutines.delay(delay * 1000)
                val r=service.dispatch(sandbox,payload,timeout, outputCallback={ trySend(ToolInvocationEvent.Output(it,"remote")) })
                if(terminal.compareAndSet(false,true)) trySend(ToolInvocationEvent.Completed(ToolInvocationResult(r.output,true,truncated=r.truncated,durationMs=r.durationMs,sandboxId=sandbox,sandboxName=sandbox)))
            } catch(c:CancellationException) {
                if(terminal.compareAndSet(false,true)) trySend(ToolInvocationEvent.Completed(ToolInvocationResult("",false,cancelled=true,sandboxId=sandbox,sandboxName=sandbox)))
            } catch(e:Throwable) { if(terminal.compareAndSet(false,true)) trySend(ToolInvocationEvent.Failed(e.message?:"Dispatch failed")) }
            finally { active.complete(invocation.invocationId); close() }
        }
        jobRef.set(job)
        awaitClose { if(job.isActive) job.cancel(); active.complete(invocation.invocationId) }
    }
    override suspend fun cancel(invocationId:String)=active.cancel(invocationId)
    companion object { const val TOOL_NAME="sandbox_dispatch"; val ID=ToolProviderId("websocket-dispatch") }
}
