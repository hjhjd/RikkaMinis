package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingSmoothnessTest {
    @Test
    fun `文本发布周期按长度平滑降频`() {
        assertEquals(80L, streamTextPublishIntervalMs(0))
        assertEquals(80L, streamTextPublishIntervalMs(499))
        assertEquals(120L, streamTextPublishIntervalMs(500))
        assertEquals(200L, streamTextPublishIntervalMs(2_000))
        assertEquals(400L, streamTextPublishIntervalMs(32_000))
        assertEquals(750L, streamTextPublishIntervalMs(64_000))
        assertEquals(1_200L, streamTextPublishIntervalMs(128_000))
    }

    @Test
    fun `一次追加只创建一个整批淡入范围`() {
        var now = 1_000_000_000L
        val controller = FadeController { now }

        controller.ingest("一批中文 text lands together")

        assertEquals(1, controller.activeRangeCount)
        assertTrue(controller.hasActiveRanges)
    }

    @Test
    fun `淡入在120毫秒后结束`() {
        var now = 1_000_000_000L
        val controller = FadeController { now }
        controller.ingest("hello")

        now += 60_000_000L
        assertTrue(controller.tick(now))
        now += 60_000_000L
        assertFalse(controller.tick(now))
        assertEquals(0, controller.activeRangeCount)
    }

    @Test
    fun `超大追加直接显示而不排队动画`() {
        val controller = FadeController { 1_000_000_000L }

        controller.ingest("x".repeat(MAX_ANIMATED_APPEND_CHARS + 1))

        assertFalse(controller.hasActiveRanges)
    }

    @Test
    fun `文本硬重置会清空旧动画`() {
        val controller = FadeController { 1_000_000_000L }
        controller.ingest("old text")
        assertTrue(controller.hasActiveRanges)

        controller.ingest("new")

        assertFalse(controller.hasActiveRanges)
        assertEquals("new", controller.lastPlainText)
    }
}
