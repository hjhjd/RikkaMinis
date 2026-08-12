package com.openminis.app.ui.chat.vcp

import org.junit.Assert.*
import org.junit.Test

class VcpContentParserTest {
    @Test fun parsesThoughtAndMarkdownInOrder() {
        val input = "前言\n\n[--- VCP元思考链: 分析 ---]\n**推理**\n[--- 元思考链结束 ---]\n\n结论"
        val blocks = VcpContentParser.parse(input).blocks
        assertEquals(3, blocks.size)
        assertTrue(blocks[0] is VcpContentBlock.Markdown)
        val thought = blocks[1] as VcpContentBlock.Thought
        assertEquals("分析", thought.theme)
        assertEquals("**推理**", thought.content)
        assertEquals(VcpBlockCompletion.STABLE, thought.completion)
        assertTrue(blocks[2] is VcpContentBlock.Markdown)
    }

    @Test fun incompleteThoughtBecomesStreamingTail() {
        val result = VcpContentParser.parse("hello\n[--- VCP元思考链 ---]\nworking", streaming = true)
        assertEquals(1, result.stableBlocks.size)
        val tail = result.tailBlock as VcpContentBlock.Thought
        assertEquals("working", tail.content)
        assertEquals(VcpBlockCompletion.STREAMING, tail.completion)
    }

    @Test fun markersInsideFenceStayMarkdown() {
        val input = "```text\n<<<[TOOL_REQUEST]>>>\ntool_name: fake\n<<<[END_TOOL_REQUEST]>>>\n```"
        val blocks = VcpContentParser.parse(input).blocks
        assertEquals(1, blocks.size)
        assertTrue(blocks.single() is VcpContentBlock.Markdown)
    }

    @Test fun parsesVcpToolRequest() {
        val input = "<<<[TOOL_REQUEST]>>>\ntool_name:「始」MobileClipboard「末」\naction:「始」read「末」\n<<<[END_TOOL_REQUEST]>>>"
        val block = VcpContentParser.parse(input).blocks.single() as VcpContentBlock.ToolUse
        assertEquals("MobileClipboard", block.toolName)
        assertEquals(VcpBlockCompletion.STABLE, block.completion)
    }

    @Test fun parsesMultilineToolResult() {
        val input = "[[VCP调用结果信息汇总:\n- 工具名称: Search\n- 执行状态: success\n- 返回内容: first\n  second\nVCP调用结果结束]]"
        val block = VcpContentParser.parse(input).blocks.single() as VcpContentBlock.ToolResult
        assertEquals("Search", block.toolName)
        assertEquals("success", block.status)
        assertEquals("first\n  second", block.details.single().value)
    }

    @Test fun parsesHtmlFenceAndDocument() {
        val fenced = VcpContentParser.parse("```html\n<div>ok</div>\n```").blocks.single() as VcpContentBlock.HtmlPreview
        assertEquals("<div>ok</div>", fenced.content)
        val doc = VcpContentParser.parse("<!DOCTYPE html><html><body>x</body></html>").blocks.single() as VcpContentBlock.HtmlPreview
        assertEquals(VcpContentBlock.HtmlPreview.Source.DOCUMENT, doc.source)
    }

    @Test fun finalIncompleteBlockIsPreserved() {
        val block = VcpContentParser.parse("<think>unfinished").blocks.single() as VcpContentBlock.Thought
        assertEquals(VcpBlockCompletion.INCOMPLETE, block.completion)
        assertEquals("unfinished", block.content)
    }

    @Test fun inlineMarkerMentionDoesNotTrigger() {
        val input = "请输出 `<<<[TOOL_REQUEST]>>>` 作为示例。"
        assertTrue(VcpContentParser.parse(input).blocks.single() is VcpContentBlock.Markdown)
    }

    @Test fun supportsCrLfMarkers() {
        val input = "[--- VCP元思考链: CRLF ---]\r\n内容\r\n[--- 元思考链结束 ---]\r\n"
        val thought = VcpContentParser.parse(input).blocks.first() as VcpContentBlock.Thought
        assertEquals("CRLF", thought.theme)
        assertEquals("内容", thought.content)
    }

    @Test fun incompleteHtmlNeverBecomesStablePreview() {
        val block = VcpContentParser.parse("```html\n<script>work()", streaming = true).tailBlock as VcpContentBlock.HtmlPreview
        assertEquals(VcpBlockCompletion.STREAMING, block.completion)
    }

    @Test fun parsesNestedHtmlContainerAsOnePreview() {
        val input = "前文\n<div id=\"vcp-root\"><div>inner</div><style>.x{color:red}</style></div>\n后文"
        val blocks = VcpContentParser.parse(input).blocks
        assertEquals(3, blocks.size)
        val html = blocks[1] as VcpContentBlock.HtmlPreview
        assertEquals(VcpContentBlock.HtmlPreview.Source.CONTAINER, html.source)
        assertTrue(html.content.contains("<div>inner</div>"))
        assertTrue((blocks[2] as VcpContentBlock.Markdown).content.contains("后文"))
    }

    @Test fun streamingNestedContainerStaysIncompleteUntilOuterClose() {
        val partial = VcpContentParser.parse("<div><div>inner</div>", streaming = true).tailBlock as VcpContentBlock.HtmlPreview
        assertEquals(VcpBlockCompletion.STREAMING, partial.completion)
        val complete = VcpContentParser.parse("<div><div>inner</div></div>", streaming = true).stableBlocks.single() as VcpContentBlock.HtmlPreview
        assertEquals(VcpBlockCompletion.STABLE, complete.completion)
    }

    @Test fun parsesStandaloneRawHtmlImage() {
        val input = "before\n<img src=\"http://localhost:6005/images/酒狐.jpg\" width=\"160\" alt='酒狐'>\nafter"
        val blocks = VcpContentParser.parse(input).blocks
        val image = blocks[1] as VcpContentBlock.Image
        assertEquals("http://localhost:6005/images/酒狐.jpg", image.src)
        assertEquals("酒狐", image.alt)
        assertEquals(160, image.widthPx)
    }

    @Test fun imgInsideContainerIsNotExtractedSeparately() {
        val blocks = VcpContentParser.parse("<div><img src=\"https://example.com/a.png\"></div>").blocks
        assertEquals(1, blocks.size)
        assertTrue(blocks.single() is VcpContentBlock.HtmlPreview)
    }

    @Test fun multilineOuterOpeningTagOwnsAllNestedDivs() {
        val input = """before
<div id="vcp-root" style="
  background: linear-gradient(135deg, #fff 0%, #eee 100%);
  padding: 25px;
">
  <div style="display:flex"><div>deep</div></div>
  <style>@keyframes x { from { opacity: 0 } to { opacity: 1 } }</style>
</div>
after"""
        val blocks = VcpContentParser.parse(input).blocks
        assertEquals(3, blocks.size)
        val html = blocks[1] as VcpContentBlock.HtmlPreview
        assertEquals(VcpContentBlock.HtmlPreview.Source.CONTAINER, html.source)
        assertTrue(html.content.startsWith("<div id=\"vcp-root\""))
        assertTrue(html.content.contains("<div style=\"display:flex\"><div>deep</div></div>"))
        assertTrue((blocks[2] as VcpContentBlock.Markdown).content.contains("after"))
    }

    @Test fun multilineStandaloneImageIsOneNativeImageBlock() {
        val input = """before
<img
 src="https://cdn.example.com/a.jpg"
 width="180"
 alt="remote">
after"""
        val blocks = VcpContentParser.parse(input).blocks
        assertEquals(3, blocks.size)
        val image = blocks[1] as VcpContentBlock.Image
        assertEquals("https://cdn.example.com/a.jpg", image.src)
        assertEquals(180, image.widthPx)
    }

    @Test fun repairsHighConfidenceStyleClosedAsDivWithoutTruncatingRoot() {
        val input = """<div id="vcp-root">
<style>.x { color:red; }
</div>
<div class="x">real content</div>
</div>
<<<[TOOL_REQUEST]>>>
tool_name:「始」Search「末」
<<<[END_TOOL_REQUEST]>>>"""
        val blocks = VcpContentParser.parse(input).blocks
        val html = blocks[0] as VcpContentBlock.HtmlPreview
        assertTrue(html.content.contains("</style>"))
        assertTrue(html.content.contains("<div class=\"x\">real content</div>"))
        assertFalse(html.content.substringBefore("real content").contains("</div>\n<div class"))
        assertTrue(blocks.any { it is VcpContentBlock.ToolUse })
        assertTrue(html.raw.contains("</div>")) // original remains available
    }

    @Test fun divTextInsideScriptAndCommentDoesNotCloseRoot() {
        val input = """<div id="vcp-root">
<script>const sample = '</div>';</script>
<!-- example </div> -->
<div>visible</div>
</div>"""
        val html = VcpContentParser.parse(input).blocks.single() as VcpContentBlock.HtmlPreview
        assertTrue(html.content.contains("<div>visible</div>"))
        assertEquals(VcpBlockCompletion.STABLE, html.completion)
    }

    @Test fun shortUnclosedFragmentDoesNotBecomeStablePreview() {
        val html = VcpContentParser.parse("<div id=\"vcp-root\"><style>.x{color:red}").blocks.single() as VcpContentBlock.HtmlPreview
        assertEquals(VcpBlockCompletion.INCOMPLETE, html.completion)
    }
}
