package com.openminis.app.sandbox

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Incrementally decodes UTF-8 and separates a command completion marker. */
internal class ShellOutputFramer(private val marker: String) {
    data class Frame(val output: String, val exitCode: Int? = null)

    private val prefix = "__MINIS_DONE_${marker}_EXIT_"
    private val decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    private var undecoded = ByteArray(0)
    private val text = StringBuilder()
    private var completed = false

    fun feed(bytes: ByteArray, length: Int = bytes.size): Frame {
        if (completed) return Frame("")
        require(length in 0..bytes.size)
        val inputBytes = ByteArray(undecoded.size + length)
        undecoded.copyInto(inputBytes)
        bytes.copyInto(inputBytes, undecoded.size, 0, length)
        val input = ByteBuffer.wrap(inputBytes)
        val chars = CharBuffer.allocate(inputBytes.size + 1)
        decoder.decode(input, chars, false)
        undecoded = ByteArray(input.remaining()).also { input.get(it) }
        chars.flip()
        text.append(chars)
        return drain(endOfInput = false)
    }

    fun finish(): Frame {
        if (completed) return Frame("")
        val input = ByteBuffer.wrap(undecoded)
        val chars = CharBuffer.allocate(undecoded.size + 2)
        decoder.decode(input, chars, true)
        decoder.flush(chars)
        chars.flip()
        text.append(chars)
        undecoded = ByteArray(0)
        return drain(endOfInput = true)
    }

    private fun drain(endOfInput: Boolean): Frame {
        val markerStart = text.indexOf(prefix)
        if (markerStart >= 0) {
            val markerEnd = text.indexOf("__", markerStart + prefix.length)
            if (markerEnd >= 0) {
                val code = text.substring(markerStart + prefix.length, markerEnd).toIntOrNull()
                if (code != null) {
                    val output = text.substring(0, markerStart)
                    text.setLength(0)
                    completed = true
                    return Frame(output, code)
                }
                // Not our valid marker; release its first character and retry later.
                val output = text.substring(0, markerStart + 1)
                text.delete(0, markerStart + 1)
                return Frame(output)
            }
            val output = text.substring(0, markerStart)
            text.delete(0, markerStart)
            return Frame(output)
        }
        if (endOfInput) return Frame(text.toString().also { text.setLength(0) })

        // Retain only a suffix that could become the marker prefix next chunk.
        var retained = minOf(text.length, prefix.length - 1)
        while (retained > 0 && !prefix.startsWith(text.substring(text.length - retained))) retained--
        val releasedLength = text.length - retained
        val output = text.substring(0, releasedLength)
        text.delete(0, releasedLength)
        return Frame(output)
    }
}
