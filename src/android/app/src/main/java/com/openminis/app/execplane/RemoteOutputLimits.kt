package com.openminis.app.execplane

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Client-side hard limits for untrusted legacy exec output. */
internal object RemoteOutputLimits {
    const val MAX_EVENT_BYTES = 256 * 1024
    const val MAX_TOTAL_BYTES = 1024 * 1024

    class StreamGuard {
        private val totalBytes = AtomicLong(0)
        private val exceeded = AtomicBoolean(false)

        val truncated: Boolean get() = exceeded.get()

        fun accept(text: String): Boolean {
            val bytes = text.toByteArray(Charsets.UTF_8).size
            if (bytes > MAX_EVENT_BYTES || totalBytes.addAndGet(bytes.toLong()) > MAX_TOTAL_BYTES) {
                exceeded.set(true)
                return false
            }
            return !exceeded.get()
        }
    }

    fun bound(result: RemoteExecResult, streamTruncated: Boolean): RemoteExecResult {
        var budget = MAX_TOTAL_BYTES
        val stdout = utf8Prefix(result.stdout, budget)
        budget -= stdout.toByteArray(Charsets.UTF_8).size
        val stderr = utf8Prefix(result.stderr, budget)
        val finalTruncated = stdout.length != result.stdout.length || stderr.length != result.stderr.length
        return result.copy(
            stdout = stdout,
            stderr = stderr,
            truncated = result.truncated || streamTruncated || finalTruncated,
        )
    }

    private fun utf8Prefix(value: String, maxBytes: Int): String {
        if (value.toByteArray(Charsets.UTF_8).size <= maxBytes) return value
        val output = StringBuilder(minOf(value.length, maxBytes))
        var bytes = 0
        var index = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            val width = when {
                codePoint <= 0x7f -> 1
                codePoint <= 0x7ff -> 2
                codePoint <= 0xffff -> 3
                else -> 4
            }
            if (bytes + width > maxBytes) break
            output.appendCodePoint(codePoint)
            bytes += width
            index += Character.charCount(codePoint)
        }
        return output.toString()
    }
}
