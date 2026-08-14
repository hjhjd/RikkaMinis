package com.openminis.app.ui.chat.vcp

import org.junit.Assert.*
import org.junit.Test

class VcpHtmlStreamSnapshotTest {
    @Test fun startsAfterCompleteRootOpeningTag() {
        assertNull(parseVcpHtmlStreamSnapshot("<div class='card'"))
        val snapshot = requireNotNull(parseVcpHtmlStreamSnapshot("<div class='card'>"))
        assertEquals("<div class='card'>", snapshot.openingTag)
        assertEquals("", snapshot.committedInner)
        assertFalse(snapshot.complete)
    }

    @Test fun commitsOnlyCompleteDirectChildren() {
        val snapshot = requireNotNull(parseVcpHtmlStreamSnapshot("<div><h3>标题</h3><p>尚未完成"))
        assertEquals("<h3>标题</h3>", snapshot.committedInner)
        assertFalse(snapshot.complete)
    }

    @Test fun handlesNestedRawTextCommentsAndVoidTags() {
        val html = "<div><!--x--><section><div>n</div></section><script>const x='</div>';</script><img src='x'>"
        val snapshot = requireNotNull(parseVcpHtmlStreamSnapshot(html))
        assertTrue(snapshot.committedInner.contains("<section>"))
        assertTrue(snapshot.committedInner.contains("const x='</div>'"))
        assertTrue(snapshot.committedInner.endsWith("<img src='x'>"))
    }

    @Test fun finalRootCloseMarksComplete() {
        val snapshot = requireNotNull(parseVcpHtmlStreamSnapshot("<div><button>继续</button></div>"))
        assertEquals("<button>继续</button>", snapshot.committedInner)
        assertTrue(snapshot.complete)
    }
}
