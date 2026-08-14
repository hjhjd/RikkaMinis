package com.openminis.app.ui.chat.vcp

/** A valid, append-only projection of a possibly incomplete VCP root div. */
internal data class VcpHtmlStreamSnapshot(
    val openingTag: String,
    val committedInner: String,
    val complete: Boolean,
) {
    val documentContent: String get() = openingTag + committedInner + "</div>"
}

/**
 * Commits only complete direct children of the outer VCP div. This keeps the
 * live WebView valid while the model is still producing a nested element.
 */
internal fun parseVcpHtmlStreamSnapshot(content: String): VcpHtmlStreamSnapshot? {
    val rootStart = Regex("^\\s*<div\\b", RegexOption.IGNORE_CASE).find(content)?.range?.last?.plus(1) ?: return null
    val openEnd = findHtmlTagClose(content, rootStart - 4) ?: return null
    val opening = content.substring(content.indexOf('<'), openEnd)
    var i = openEnd
    var depth = 0
    var committedEnd = openEnd
    while (i < content.length) {
        val lt = content.indexOf('<', i)
        if (lt < 0) break
        if (content.startsWith("<!--", lt)) {
            val end = content.indexOf("-->", lt + 4)
            if (end < 0) break
            i = end + 3
            if (depth == 0) committedEnd = i
            continue
        }
        val tokenEnd = findHtmlTagClose(content, lt) ?: break
        val token = content.substring(lt, tokenEnd)
        val tag = TAG.matchEntire(token)
        if (tag == null) {
            i = tokenEnd
            continue
        }
        val closing = tag.groupValues[1].isNotEmpty()
        val name = tag.groupValues[2].lowercase()
        if (closing && name == "div" && depth == 0) {
            return VcpHtmlStreamSnapshot(opening, content.substring(openEnd, lt), true)
        }
        if (!closing && name in RAW_TEXT_TAGS) {
            val close = Regex("</${Regex.escape(name)}\\s*>", RegexOption.IGNORE_CASE).find(content, tokenEnd) ?: break
            i = close.range.last + 1
            if (depth == 0) committedEnd = i
            continue
        }
        val selfClosing = token.trimEnd().endsWith("/>") || name in VOID_TAGS
        if (closing) {
            if (depth > 0) depth--
            i = tokenEnd
            if (depth == 0) committedEnd = i
        } else {
            i = tokenEnd
            if (!selfClosing) depth++ else if (depth == 0) committedEnd = i
        }
    }
    return VcpHtmlStreamSnapshot(opening, content.substring(openEnd, committedEnd), false)
}

private fun findHtmlTagClose(text: String, start: Int): Int? {
    var quote: Char? = null
    var i = start
    while (i < text.length) {
        val c = text[i]
        when {
            quote != null && c == quote -> quote = null
            quote == null && (c == '\'' || c == '"') -> quote = c
            quote == null && c == '>' -> return i + 1
        }
        i++
    }
    return null
}

private val TAG = Regex("<\\s*(/?)\\s*([a-zA-Z][a-zA-Z0-9:-]*)\\b[\\s\\S]*>")
private val RAW_TEXT_TAGS = setOf("script", "style", "textarea", "title")
private val VOID_TAGS = setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr")
