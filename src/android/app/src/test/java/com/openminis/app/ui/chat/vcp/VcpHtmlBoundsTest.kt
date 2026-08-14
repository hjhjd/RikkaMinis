package com.openminis.app.ui.chat.vcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VcpHtmlBoundsTest {
    @Test fun parsesRootBoundsProtocol() {
        assertEquals(VcpHtmlBounds(5, 8, 320, 144), parseVcpHtmlBounds("5,8,320,144"))
        assertEquals(VcpHtmlBounds(5, 8, 320, 144), parseVcpHtmlBounds("\"5,8,320,144\""))
        assertNull(parseVcpHtmlBounds("bad"))
        assertNull(parseVcpHtmlBounds("0,0,NaN,20"))
        assertEquals(
            VcpHtmlBounds(-MAX_VCP_HTML_OFFSET_CSS, MAX_VCP_HTML_OFFSET_CSS, MAX_VCP_HTML_WIDTH_CSS, MAX_VCP_HTML_HEIGHT_CSS),
            parseVcpHtmlBounds("-999999,999999,999999,999999999"),
        )
    }

    @Test fun observerTargetsVcpRootFirst() {
        assertTrue(VCP_HTML_BOUNDS_SCRIPT.contains("querySelector('#vcp-root')"))
        assertTrue(VCP_HTML_BOUNDS_SCRIPT.contains("ResizeObserver"))
        assertTrue(VCP_HTML_BOUNDS_SCRIPT.contains("MutationObserver"))
        assertTrue(VCP_HTML_BOUNDS_SCRIPT.contains("document.fonts.ready"))
        assertTrue(VCP_HTML_BOUNDS_SCRIPT.contains("window.input=function"))
        assertTrue(VCP_HTML_BOUNDS_SCRIPT.contains("vcp-action://button"))
        assertTrue(VCP_HTML_BOUNDS_SCRIPT.contains("getAttribute('data-send')"))
    }
}
