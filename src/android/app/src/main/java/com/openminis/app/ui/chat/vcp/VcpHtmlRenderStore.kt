package com.openminis.app.ui.chat.vcp

import android.content.Context
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** State survives LazyColumn item disposal for the lifetime of the process. */
internal class VcpHtmlRenderState {
    var rootLeftCss by mutableIntStateOf(0)
    var rootTopCss by mutableIntStateOf(0)
    var rootWidthCss by mutableIntStateOf(0)
    var rootHeightCss by mutableIntStateOf(160)
    var hasMeasured by mutableStateOf(false)
    var showPreview by mutableStateOf(true)
    var hasLoaded by mutableStateOf(false)
    var loadedOpeningTag: String = ""
    var committedInner: String = ""
    var completionDispatched: Boolean = false
}

internal object VcpHtmlRenderStore {
    private const val MAX_STATES = 128
    private val states = object : LinkedHashMap<String, VcpHtmlRenderState>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, VcpHtmlRenderState>?): Boolean = size > MAX_STATES
    }
    @Synchronized fun state(key: String): VcpHtmlRenderState = states.getOrPut(key) { VcpHtmlRenderState() }
}

/** Retained WebViews with generation leases to prevent stale release races. */
internal object VcpHtmlWebViewPool {
    private const val MAX_IDLE = 6
    internal data class Lease(val webView: WebView, val generation: Long, val created: Boolean)
    private data class Entry(val key: String, val webView: WebView, var generation: Long, var active: Boolean)
    private val entries = LinkedHashMap<String, Entry>(8, .75f, true)
    private var nextGeneration = 1L

    @Synchronized
    fun obtain(context: Context, key: String, create: (Context) -> WebView): Lease {
        val generation = nextGeneration++
        entries[key]?.let { entry ->
            detach(entry.webView)
            entry.generation = generation
            entry.active = true
            revive(entry.webView)
            return Lease(entry.webView, generation, false)
        }
        val view = create(context.applicationContext)
        entries[key] = Entry(key, view, generation, true)
        return Lease(view, generation, true)
    }

    /** Ignore a release from an old Lazy item after this view was re-leased. */
    @Synchronized
    fun retain(key: String, view: WebView, generation: Long) {
        val entry = entries[key] ?: return
        if (entry.webView !== view || entry.generation != generation) return
        detach(view)
        entry.active = false
        trimIdle()
    }

    private fun trimIdle() {
        val idle = entries.values.filter { !it.active }
        if (idle.size <= MAX_IDLE) return
        repeat(idle.size - MAX_IDLE) {
            val victim = entries.entries.firstOrNull { !it.value.active } ?: return
            entries.remove(victim.key)
            destroy(victim.value.webView)
        }
    }

    private fun revive(view: WebView) {
        view.visibility = android.view.View.VISIBLE
        view.alpha = 1f
        runCatching { view.onResume() }
        view.post {
            view.requestLayout()
            view.invalidate()
            view.evaluateJavascript("window.dispatchEvent(new Event('resize'));void(0)", null)
        }
    }

    private fun detach(view: WebView) { (view.parent as? ViewGroup)?.removeView(view) }
    private fun destroy(view: WebView) {
        detach(view)
        runCatching { view.stopLoading() }
        runCatching { view.loadUrl("about:blank") }
        runCatching { view.destroy() }
    }
}
