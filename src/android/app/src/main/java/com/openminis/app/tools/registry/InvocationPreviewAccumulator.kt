package com.openminis.app.tools.registry

/** Bounded UI-only tail; authoritative final output remains in Completed. */
internal class InvocationPreviewAccumulator(private val maxChars: Int = 50_000) {
    private val text = StringBuilder()
    var truncated: Boolean = false
        private set

    fun append(chunk: String): String {
        text.append(chunk)
        if (text.length > maxChars) {
            text.delete(0, text.length - maxChars)
            truncated = true
        }
        return snapshot()
    }

    fun snapshot(): String = if (truncated) "[…UI preview truncated…]\n$text" else text.toString()
}
