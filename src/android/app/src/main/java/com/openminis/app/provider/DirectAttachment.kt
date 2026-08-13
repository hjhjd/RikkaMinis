package com.openminis.app.provider

import com.openminis.app.data.model.AgentContentPart
import java.io.File

/** Provider 边界的附件直传策略，避免把不受控大文件装进请求内存。 */
object DirectAttachment {
    const val MAX_TEXT_BYTES = 512 * 1024L
    const val MAX_BINARY_BYTES = 20 * 1024 * 1024L

    fun text(part: AgentContentPart.FileData): String? {
        if (part.size !in 1..MAX_TEXT_BYTES || !isText(part)) return null
        val file = File(part.hostPath)
        if (!file.isFile || file.length() != part.size) return null
        return runCatching {
            file.inputStream().bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrNull()?.let {
            "<附加文件 文件名=\"${xmlEscape(part.fileName)}\" 类型=\"${xmlEscape(part.mimeType)}\">\n$it\n</附加文件>"
        }
    }

    fun binary(part: AgentContentPart.FileData): ByteArray? {
        if (part.size !in 1..MAX_BINARY_BYTES) return null
        val file = File(part.hostPath)
        if (!file.isFile || file.length() != part.size) return null
        return runCatching { file.readBytes() }.getOrNull()
    }

    fun isPdf(part: AgentContentPart.FileData): Boolean =
        part.mimeType.equals("application/pdf", ignoreCase = true) ||
            part.fileName.endsWith(".pdf", ignoreCase = true)

    private fun isText(part: AgentContentPart.FileData): Boolean {
        if (part.mimeType.startsWith("text/")) return true
        return part.fileName.substringAfterLast('.', "").lowercase() in setOf(
            "txt", "md", "markdown", "json", "jsonl", "csv", "tsv", "xml", "yaml", "yml",
            "kt", "kts", "java", "py", "js", "ts", "tsx", "jsx", "html", "css", "sql",
            "sh", "bash", "zsh", "c", "h", "cpp", "hpp", "rs", "go", "swift", "toml", "ini",
        )
    }

    private fun xmlEscape(value: String): String = value
        .replace("&", "&amp;").replace("\"", "&quot;")
        .replace("<", "&lt;").replace(">", "&gt;")
}
