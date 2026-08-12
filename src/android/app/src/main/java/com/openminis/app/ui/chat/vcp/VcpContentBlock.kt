package com.openminis.app.ui.chat.vcp

import java.security.MessageDigest

internal enum class VcpBlockCompletion { STABLE, STREAMING, INCOMPLETE }

internal sealed interface VcpContentBlock {
    val raw: String
    val completion: VcpBlockCompletion
    val hash: String
        get() = MessageDigest.getInstance("SHA-256")
            .digest((this::class.simpleName + "\u0000" + raw).toByteArray())
            .take(12).joinToString("") { "%02x".format(it) }

    data class Markdown(
        val content: String,
        override val raw: String = content,
        override val completion: VcpBlockCompletion = VcpBlockCompletion.STABLE,
    ) : VcpContentBlock

    data class Thought(
        val theme: String,
        val content: String,
        val source: Source,
        override val raw: String,
        override val completion: VcpBlockCompletion,
    ) : VcpContentBlock {
        enum class Source { VCP_META, THINK_TAG }
    }

    data class ToolUse(
        val toolName: String,
        val content: String,
        override val raw: String,
        override val completion: VcpBlockCompletion,
    ) : VcpContentBlock

    data class ToolResult(
        val toolName: String,
        val status: String,
        val details: List<Detail>,
        val footer: String,
        override val raw: String,
        override val completion: VcpBlockCompletion,
    ) : VcpContentBlock {
        data class Detail(val key: String, val value: String)
    }

    data class HtmlPreview(
        val content: String,
        val source: Source,
        override val raw: String,
        override val completion: VcpBlockCompletion,
    ) : VcpContentBlock {
        enum class Source { FENCE, DOCUMENT, CONTAINER }
    }

    data class Image(
        val src: String,
        val alt: String,
        val title: String,
        val widthPx: Int?,
        override val raw: String,
        override val completion: VcpBlockCompletion = VcpBlockCompletion.STABLE,
    ) : VcpContentBlock
}

internal data class VcpStreamParseResult(
    val stableBlocks: List<VcpContentBlock>,
    val tailBlock: VcpContentBlock?,
) {
    val blocks: List<VcpContentBlock> get() = stableBlocks + listOfNotNull(tailBlock)
}
