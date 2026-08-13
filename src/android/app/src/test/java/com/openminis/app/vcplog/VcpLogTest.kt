package com.openminis.app.vcplog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VcpLogTest {
    @Test fun `构造 VCPLog 地址并编码密钥和设备名`() {
        assertEquals(
            "wss://example.com/VCPlog/VCP_Key=a%2Fb%20c?deviceName=Rikka%20Phone",
            VcpLogConnectionManager.buildLogUrl("wss://example.com/old?x=1", "a/b c", "Rikka Phone"),
        )
        assertEquals(
            "ws://localhost:5800/VCPlog/VCP_Key=k",
            VcpLogConnectionManager.buildLogUrl("ws://localhost:5800", "k", "RikkaMinis", false),
        )
    }

    @Test fun `脱敏密钥并正确生成 Origin`() {
        assertEquals("ws://host/VCPlog/VCP_Key=***?deviceName=x",
            VcpLogConnectionManager.redact("ws://host/VCPlog/VCP_Key=secret?deviceName=x"))
        assertEquals("https://example.com:8443",
            VcpLogConnectionManager.originFor("wss://example.com:8443/VCPlog/VCP_Key=x"))
    }

    @Test fun `解析 JSON 与纯文本事件`() {
        val event = VcpLogEventParser.parse("1", """{"type":"vcp_log","data":{"status":"error","tool_name":"Demo","content":"failed","source":"VCPLog"}}""", 1)
        assertEquals("vcp_log", event.type)
        assertEquals("error", event.status)
        assertEquals("Demo", event.toolName)
        assertEquals("failed", event.content)
        assertEquals("raw_text", VcpLogEventParser.parse("2", "plain", 1).type)
    }

    @Test fun `统一分类并解析对象内容和服务端时间`() {
        val event = VcpLogEventParser.parse(
            "3",
            """{"type":"vcp-log-status","status":"error","data":{"content":{"reason":"bad"}},"timestamp":1700000000}""",
            2,
        )
        assertEquals(VcpLogEventCategory.ERROR, event.category)
        assertTrue(event.content.contains("reason"))
        assertEquals(1_700_000_000_000L, event.eventAtMs)
        assertEquals(VcpLogEventCategory.INFO,
            VcpLogEventParser.parse("4", """{"message":"No errors found"}""", 2).category)
    }

    @Test fun `纯文本错误识别不误伤普通说明`() {
        assertEquals(VcpLogEventCategory.ERROR,
            VcpLogEventParser.parse("5", "[ERROR] failed", 1).category)
        assertEquals(VcpLogEventCategory.RAW,
            VcpLogEventParser.parse("6", "No errors found", 1).category)
    }

    @Test fun `仓库按 UTF8 字节限制载荷`() {
        val store = VcpLogStore(capacity = 2, maxPayloadBytes = 4)
        assertTrue(store.accept("你") != null)
        assertNull(store.accept("你好"))
    }

    @Test fun `仓库限制容量大小并管理未读`() {
        val store = VcpLogStore(capacity = 2, maxPayloadBytes = 32)
        repeat(3) { store.accept("event-$it") }
        assertEquals(2, store.events.value.size)
        assertEquals(2, store.unreadCount.value)
        assertNull(store.accept("x".repeat(33)))
        store.beginObserving()
        store.beginObserving()
        assertEquals(0, store.unreadCount.value)
        store.endObserving()
        store.accept("observed")
        assertEquals(0, store.unreadCount.value)
        store.endObserving()
        store.accept("unread")
        assertEquals(1, store.unreadCount.value)
        assertFalse(store.payload(store.events.value.first().id).isNullOrBlank())
        store.clear()
        assertTrue(store.events.value.isEmpty())
    }
}
