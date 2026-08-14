package com.openminis.app.ui.chat.vcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VcpHtmlSandboxTest {
    @Test fun fragmentIsEmbeddedDirectlyAndLosslessly() {
        val content = """<div style="color:#f57f17">酒狐 🦊</div>
<script>window.answer = "引号'和\\换行";</script>"""
        val document = sandboxDocument(content)
        assertTrue(document.contains(content))
        assertTrue(document.contains("<body>$content</body>"))
        assertFalse(document.contains("srcdoc="))
        assertFalse(document.contains("addJavascriptInterface"))
    }

    @Test fun existingDocumentReceivesPolicyInsideHead() {
        val content = "<!doctype html><html><head><title>x</title></head><body>ok</body></html>"
        val document = sandboxDocument(content)
        assertEquals(1, Regex("<!doctype html>", RegexOption.IGNORE_CASE).findAll(document).count())
        assertTrue(document.indexOf("Content-Security-Policy") < document.indexOf("<title>x</title>"))
        assertTrue(document.contains("<body>ok</body>"))
    }

    @Test fun documentWithoutHeadGetsOne() {
        val document = sandboxDocument("<html><body>ok</body></html>")
        assertTrue(document.contains("<html><head>"))
        assertTrue(document.contains("<body>ok</body>"))
    }

    @Test fun fullscreenDocumentEnablesVerticalScrolling() {
        val inline = sandboxDocument("<div>content</div>")
        val fullscreen = sandboxDocument("<div>content</div>", fullscreen = true)
        assertTrue(inline.contains("overflow:hidden"))
        assertTrue(fullscreen.contains("overflow-y:auto"))
    }

    @Test fun rootBubbleShadowIsAlwaysDisabled() {
        val document = sandboxDocument("<div id=\"vcp-root\">content</div>")
        assertTrue(document.contains("#vcp-root,[data-vcp-root],body>div:first-child{box-shadow:none!important}"))
        assertFalse(document.contains("vcp-streaming"))
    }

    @Test fun documentAllowsExternalResources() {
        val document = sandboxDocument("<img src='https://cdn.example/a.png'>")
        assertTrue(document.contains("img-src http: https:"))
        assertTrue(document.contains("connect-src http: https: ws: wss:"))
    }
}
