package com.openminis.app.ui.chat.vcp

/** Parses VCP's textual protocol without interpreting examples inside code fences. */
internal object VcpContentParser {
    private enum class Kind { THOUGHT, THINK, TOOL, TOOL_RESULT, HTML_FENCE, HTML_DOCUMENT, HTML_CONTAINER, IMAGE }
    private data class Start(
        val kind: Kind,
        val start: Int,
        val contentStart: Int,
        val theme: String = "",
        val tagName: String = "",
        val markerEnd: Int = contentStart,
    )

    private val thoughtStart = Regex("^[ \\t]*\\[--- VCP元思考链(?::\\s*([^]]*?))?\\s*---]\\s*$", RegexOption.IGNORE_CASE)
    private val toolStart = Regex("^[ \\t]*<<<\\[TOOL_REQUEST]>>>\\s*$", RegexOption.IGNORE_CASE)
    private val resultStart = Regex("^[ \\t]*\\[\\[VCP调用结果信息汇总:\\s*$", RegexOption.IGNORE_CASE)
    private val htmlFence = Regex("^[ \\t]*```html[ \\t]*$", RegexOption.IGNORE_CASE)
    private val genericFence = Regex("^[ \\t]*```.*$")
    private val htmlDoc = Regex("^[ \\t]*(?:<!doctype html>|<html(?:\\s|>))", RegexOption.IGNORE_CASE)
    // Prefix-only patterns: VCP commonly emits long style attributes with the
    // opening tag spread over several lines. Requiring `>` on the first line
    // loses the real outer container and incorrectly promotes its inner divs.
    private val htmlContainerPrefix = Regex("^[ \\t]*<(div|section|article|header|footer|main|aside|figure|figcaption)\\b", RegexOption.IGNORE_CASE)
    private val imagePrefix = Regex("^[ \\t]*<img\\b", RegexOption.IGNORE_CASE)
    private val htmlAttribute = Regex("([a-zA-Z_:][-a-zA-Z0-9_:.]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))")
    private val toolNameTagged = Regex("<tool_name>([\\s\\S]*?)</tool_name>", RegexOption.IGNORE_CASE)
    private val toolNameVcp = Regex("tool_name:\\s*「始(?:exp)?」([^「」]*)「末(?:exp)?」", RegexOption.IGNORE_CASE)
    private val detailLine = Regex("^-\\s*([^:：]+)[:：]\\s*(.*)$")

    fun parse(content: String, streaming: Boolean = false): VcpStreamParseResult {
        if (content.isEmpty()) return VcpStreamParseResult(emptyList(), null)
        val stable = mutableListOf<VcpContentBlock>()
        var cursor = 0
        while (cursor < content.length) {
            val start = findStart(content, cursor) ?: break
            if (start.start > cursor) addMarkdown(stable, content.substring(cursor, start.start))
            val end = findEnd(content, start)
            if (end == null) {
                val raw = content.substring(start.start)
                val completion = if (streaming) VcpBlockCompletion.STREAMING else VcpBlockCompletion.INCOMPLETE
                return VcpStreamParseResult(stable, build(start, content.substring(start.contentStart), raw, completion))
            }
            val (innerEnd, rawEnd) = end
            val raw = content.substring(start.start, rawEnd)
            stable += build(start, content.substring(start.contentStart, innerEnd), raw, VcpBlockCompletion.STABLE)
            cursor = rawEnd
        }
        val tail = content.substring(cursor)
        if (tail.isNotEmpty()) {
            val block = VcpContentBlock.Markdown(
                content = tail,
                completion = if (streaming) VcpBlockCompletion.STREAMING else VcpBlockCompletion.STABLE,
            )
            return if (streaming) VcpStreamParseResult(stable, block) else VcpStreamParseResult(stable + block, null)
        }
        return VcpStreamParseResult(stable, null)
    }

    private fun addMarkdown(out: MutableList<VcpContentBlock>, text: String) {
        if (text.isNotEmpty()) out += VcpContentBlock.Markdown(text)
    }

    private fun findStart(text: String, from: Int): Start? {
        var offset = from
        var inFence = false
        while (offset < text.length) {
            val end = text.indexOf('\n', offset).let { if (it < 0) text.length else it }
            val line = text.substring(offset, end).trimEnd('\r')
            if (!inFence) {
                thoughtStart.matchEntire(line)?.let { return Start(Kind.THOUGHT, offset, afterLine(text, end), it.groupValues[1].trim().trim('"').ifBlank { "元思考链" }) }
                toolStart.matchEntire(line)?.let { return Start(Kind.TOOL, offset, afterLine(text, end)) }
                resultStart.matchEntire(line)?.let { return Start(Kind.TOOL_RESULT, offset, afterLine(text, end)) }
                htmlFence.matchEntire(line)?.let { return Start(Kind.HTML_FENCE, offset, afterLine(text, end)) }
                htmlDoc.find(line)?.let { return Start(Kind.HTML_DOCUMENT, offset + it.range.first, offset + it.range.first) }
                imagePrefix.find(line)?.let {
                    val startAt = offset + it.range.first
                    val openEnd = findTagClose(text, startAt) ?: return Start(Kind.IMAGE, startAt, startAt)
                    val afterTagLine = consumeLineRemainder(text, openEnd)
                    return Start(Kind.IMAGE, startAt, startAt, markerEnd = afterTagLine)
                }
                htmlContainerPrefix.find(line)?.let {
                    val startAt = offset + it.range.first
                    val openEnd = findTagClose(text, startAt)
                        ?: return Start(
                            kind = Kind.HTML_CONTAINER,
                            start = startAt,
                            contentStart = text.length,
                            tagName = it.groupValues[1].lowercase(),
                            markerEnd = text.length,
                        )
                    return Start(
                        kind = Kind.HTML_CONTAINER,
                        start = startAt,
                        contentStart = openEnd,
                        tagName = it.groupValues[1].lowercase(),
                        markerEnd = openEnd,
                    )
                }
                findThink(line)?.let { (kind, range) -> return Start(kind, offset + range.first, offset + range.last + 1) }
            }
            if (genericFence.matches(line)) inFence = !inFence
            offset = afterLine(text, end)
        }
        return null
    }

    /** Finds `>` outside quoted attribute values. Returns the offset after it. */
    private fun findTagClose(text: String, start: Int): Int? {
        var quote: Char? = null
        var i = start
        while (i < text.length) {
            val c = text[i]
            when {
                quote != null && c == quote -> quote = null
                quote == null && (c == '\"' || c == '\'') -> quote = c
                quote == null && c == '>' -> return i + 1
            }
            i++
        }
        return null
    }

    /** Include whitespace/newline after a standalone tag, not following prose. */
    private fun consumeLineRemainder(text: String, from: Int): Int {
        var i = from
        while (i < text.length && (text[i] == ' ' || text[i] == '\t' || text[i] == '\r')) i++
        return if (i < text.length && text[i] == '\n') i + 1 else from
    }

    private fun findThink(line: String): Pair<Kind, IntRange>? {
        val match = Regex("<think(?:ing)?>", RegexOption.IGNORE_CASE).find(line) ?: return null
        return Kind.THINK to match.range
    }

    private fun findEnd(text: String, start: Start): Pair<Int, Int>? {
        return when (start.kind) {
            Kind.THOUGHT -> lineEnd(text, start.contentStart, Regex("^[ \\t]*\\[--- 元思考链结束 ---]\\s*$", RegexOption.IGNORE_CASE))
            Kind.TOOL -> lineEnd(text, start.contentStart, Regex("^[ \\t]*<<<\\[END_TOOL_REQUEST]>>>\\s*$", RegexOption.IGNORE_CASE))
            Kind.TOOL_RESULT -> lineEnd(text, start.contentStart, Regex("^[ \\t]*VCP调用结果结束]]\\s*$", RegexOption.IGNORE_CASE))
            Kind.HTML_FENCE -> lineEnd(text, start.contentStart, Regex("^[ \\t]*```[ \\t]*$"))
            Kind.THINK -> tagEnd(text, start.contentStart, Regex("</think(?:ing)?>", RegexOption.IGNORE_CASE))
            Kind.HTML_DOCUMENT -> tagEnd(text, start.contentStart, Regex("</html>", RegexOption.IGNORE_CASE))
            Kind.HTML_CONTAINER -> findMatchingContainerEnd(text, start)
            Kind.IMAGE -> if (start.markerEnd > start.start) start.start to start.markerEnd else null
        }
    }

    private fun lineEnd(text: String, from: Int, regex: Regex): Pair<Int, Int>? {
        var offset = from
        while (offset <= text.length) {
            val end = text.indexOf('\n', offset).let { if (it < 0) text.length else it }
            if (regex.matches(text.substring(offset, end).trimEnd('\r'))) return offset to afterLine(text, end)
            if (end == text.length) break
            offset = end + 1
        }
        return null
    }

    private fun tagEnd(text: String, from: Int, regex: Regex): Pair<Int, Int>? {
        val m = regex.find(text, from) ?: return null
        return m.range.first to (m.range.last + 1)
    }

    /**
     * Context-aware container matcher. Tags inside raw-text elements and HTML
     * comments never affect the outer div depth. A narrowly-scoped repair
     * recognises the common LLM typo `</div>` in place of `</style>` only when
     * valid-looking CSS is immediately followed by a real HTML element.
     */
    private fun findMatchingContainerEnd(text: String, start: Start): Pair<Int, Int>? {
        var depth = 1
        var i = start.contentStart
        while (i < text.length) {
            val lt = text.indexOf('<', i)
            if (lt < 0) return null
            if (text.startsWith("<!--", lt)) {
                val close = text.indexOf("-->", lt + 4)
                if (close < 0) return null
                i = close + 3
                continue
            }
            val openRaw = RAW_TEXT_OPEN.matchAt(text, lt)
            if (openRaw != null) {
                val tagName = openRaw.groupValues[1].lowercase()
                val openEnd = findTagClose(text, lt) ?: return null
                val close = Regex("</${Regex.escape(tagName)}\\s*>", RegexOption.IGNORE_CASE).find(text, openEnd)
                if (close != null) {
                    i = close.range.last + 1
                    continue
                }
                if (tagName == "style") {
                    val typo = MALFORMED_STYLE_CLOSE.find(text, openEnd)
                    if (typo != null) {
                        // Consume the mistaken close as </style>, not </div>.
                        i = typo.groups[1]!!.range.last + 1
                        continue
                    }
                }
                return null
            }
            val tokenEnd = findTagClose(text, lt) ?: return null
            val token = text.substring(lt, tokenEnd)
            val tag = TAG_NAME.find(token)
            if (tag != null && tag.groupValues[2].equals(start.tagName, true)) {
                val closing = tag.groupValues[1] == "/"
                val selfClosing = token.trimEnd().endsWith("/>")
                if (closing) depth-- else if (!selfClosing) depth++
                if (depth == 0) return lt to tokenEnd
            }
            i = tokenEnd
        }
        return null
    }

    private val RAW_TEXT_OPEN = Regex("<(style|script|textarea|title)\\b", RegexOption.IGNORE_CASE)
    private val TAG_NAME = Regex("^<\\s*(/?)\\s*([a-zA-Z][a-zA-Z0-9:-]*)")
    // Keep this Android ICU compatible: unlike the host JVM regex engine,
    // some Android releases reject a bare `}` and look-ahead in this shape.
    // Capture the following tag instead of asserting it; only group 1 is
    // replaced, so the real HTML element remains byte-identical.
    private val MALFORMED_STYLE_CLOSE = Regex(
        "\\}\\s*(</div>)\\s*(<(?:div|section|article|header|footer|main|aside|figure|figcaption|p|img|button|ul|ol|span)\\b)",
        RegexOption.IGNORE_CASE,
    )

    private fun repairMalformedHtml(raw: String): String {
        val styleOpen = RAW_TEXT_OPEN.find(raw) ?: return raw
        if (!styleOpen.groupValues[1].equals("style", true)) return raw
        val openEnd = findTagClose(raw, styleOpen.range.first) ?: return raw
        if (Regex("</style\\s*>", RegexOption.IGNORE_CASE).find(raw, openEnd) != null) return raw
        val typo = MALFORMED_STYLE_CLOSE.find(raw, openEnd) ?: return raw
        val group = typo.groups[1] ?: return raw
        return raw.replaceRange(group.range, "</style>")
    }

    private fun afterLine(text: String, lineEnd: Int) = if (lineEnd < text.length) lineEnd + 1 else lineEnd

    private fun build(start: Start, inner: String, raw: String, completion: VcpBlockCompletion): VcpContentBlock = when (start.kind) {
        Kind.THOUGHT -> VcpContentBlock.Thought(start.theme, inner.trim(), VcpContentBlock.Thought.Source.VCP_META, raw, completion)
        Kind.THINK -> VcpContentBlock.Thought("思维链", inner.trim(), VcpContentBlock.Thought.Source.THINK_TAG, raw, completion)
        Kind.TOOL -> VcpContentBlock.ToolUse(extractToolName(inner), inner.trim(), raw, completion)
        Kind.TOOL_RESULT -> parseToolResult(inner, raw, completion)
        Kind.HTML_FENCE -> VcpContentBlock.HtmlPreview(inner.trimEnd(), VcpContentBlock.HtmlPreview.Source.FENCE, raw, completion)
        Kind.HTML_DOCUMENT -> VcpContentBlock.HtmlPreview(raw, VcpContentBlock.HtmlPreview.Source.DOCUMENT, raw, completion)
        Kind.HTML_CONTAINER -> VcpContentBlock.HtmlPreview(
            content = if (completion == VcpBlockCompletion.STABLE) repairMalformedHtml(raw.trimEnd()) else raw.trimEnd(),
            source = VcpContentBlock.HtmlPreview.Source.CONTAINER,
            raw = raw,
            completion = completion,
        )
        Kind.IMAGE -> parseImage(raw, completion)
    }

    private fun parseImage(raw: String, completion: VcpBlockCompletion): VcpContentBlock.Image {
        val attrs = htmlAttribute.findAll(raw).associate { match ->
            val value = match.groupValues.drop(2).firstOrNull { it.isNotEmpty() }.orEmpty()
            match.groupValues[1].lowercase() to value
        }
        return VcpContentBlock.Image(
            src = attrs["src"].orEmpty(),
            alt = attrs["alt"].orEmpty(),
            title = attrs["title"].orEmpty(),
            widthPx = attrs["width"]?.filter(Char::isDigit)?.toIntOrNull(),
            raw = raw,
            completion = completion,
        )
    }

    private fun extractToolName(content: String): String =
        toolNameTagged.find(content)?.groupValues?.get(1)?.trim()
            ?: toolNameVcp.find(content)?.groupValues?.get(1)?.trim()?.trimEnd(',')
            ?: "Processing..."

    private fun parseToolResult(content: String, raw: String, completion: VcpBlockCompletion): VcpContentBlock.ToolResult {
        var toolName = "Unknown Tool"
        var status = ""
        val details = mutableListOf<VcpContentBlock.ToolResult.Detail>()
        val footer = mutableListOf<String>()
        var currentKey: String? = null
        var currentValue = StringBuilder()
        fun flush() {
            val key = currentKey ?: return
            val value = currentValue.toString().trimEnd()
            when (key.trim().lowercase()) {
                "工具名称", "tool name", "tool_name" -> toolName = value
                "执行状态", "status" -> status = value
                else -> details += VcpContentBlock.ToolResult.Detail(key.trim(), value)
            }
            currentKey = null; currentValue = StringBuilder()
        }
        content.lines().forEach { line ->
            val m = detailLine.matchEntire(line.trim())
            if (m != null) {
                flush(); currentKey = m.groupValues[1]; currentValue.append(m.groupValues[2])
            } else if (currentKey != null) {
                if (currentValue.isNotEmpty()) currentValue.append('\n')
                currentValue.append(line)
            } else if (line.isNotBlank()) footer += line
        }
        flush()
        return VcpContentBlock.ToolResult(toolName, status, details, footer.joinToString("\n"), raw, completion)
    }
}
