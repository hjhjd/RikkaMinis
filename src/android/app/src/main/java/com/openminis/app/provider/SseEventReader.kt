package com.openminis.app.provider

import java.io.BufferedReader

/** Minimal SSE framing: blank-line delimited events, comments ignored, data fields joined. */
internal class SseEventReader(private val reader: BufferedReader) {
    fun readData(): String? {
        val data = ArrayList<String>(1)
        while (true) {
            val line = reader.readLine()
            if (line == null) return data.takeIf { it.isNotEmpty() }?.joinToString("\n")
            if (line.isEmpty()) {
                if (data.isNotEmpty()) return data.joinToString("\n")
                continue
            }
            if (line.startsWith(":")) continue
            if (line == "data") data.add("")
            else if (line.startsWith("data:")) {
                val value = line.substring(5).let { if (it.startsWith(" ")) it.substring(1) else it }
                data.add(value)
            }
        }
    }
}
