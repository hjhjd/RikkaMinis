package com.openminis.app.vcpinfo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VcpInfoTest {
    @Test fun `构造兼容 VCPInfo 地址并编码密钥`() {
        assertEquals("wss://example.com/vcpinfo/VCP_Key=a%2Fb%20c",
            VcpInfoConnectionManager.buildInfoUrl("wss://example.com/old?x=1", "a/b c"))
    }

    @Test fun `识别六类认知消息`() {
        val cases = listOf(
            """{"type":"RAG_RETRIEVAL_DETAILS","dbName":"docs","results":[],"query":"q"}""" to VcpInfoCategory.RAG,
            """{"type":"META_THINKING_CHAIN","chainName":"c"}""" to VcpInfoCategory.CHAIN,
            """{"type":"AGENT_PRIVATE_CHAT_PREVIEW","agentName":"A"}""" to VcpInfoCategory.CHAT,
            """{"type":"AI_MEMO_RETRIEVAL"}""" to VcpInfoCategory.MEMO,
            """{"type":"DailyNote","dbName":"d"}""" to VcpInfoCategory.MEMO,
            """{"type":"AGENT_DREAM_START","agentName":"A"}""" to VcpInfoCategory.DREAM,
        )
        cases.forEachIndexed { index, (raw, category) ->
            assertEquals(category, VcpInfoMessageParser.parse("$index", raw, 0)?.category)
        }
        assertNull(VcpInfoMessageParser.parse("x", """{"type":"noise"}""", 0))
    }

    @Test fun `元思考链保留阶段聚类和召回字段`() {
        val raw = """{"type":"META_THINKING_CHAIN","chainName":"深思","query":"为什么", "totalStages":2,"activatedGroups":["哲学"],"stages":[{"stage":1,"clusterName":"因果","resultCount":1,"k":3,"results":[{"score":0.91,"text":"因果片段"}]}]}"""
        val parsed = requireNotNull(VcpInfoMessageParser.parse("chain", raw, 0))
        assertEquals(VcpInfoCategory.CHAIN, parsed.category)
        val json = org.json.JSONObject(parsed.rawJson)
        assertEquals("因果", json.getJSONArray("stages").getJSONObject(0).getString("clusterName"))
        assertEquals("因果片段", json.getJSONArray("stages").getJSONObject(0).getJSONArray("results").getJSONObject(0).getString("text"))
    }

    @Test fun `仓库限制容量并管理未读`() {
        val store = VcpInfoStore(capacity = 2)
        repeat(3) { store.accept("""{"type":"DailyNote","dbName":"$it","message":"m"}""") }
        assertEquals(2, store.messages.value.size)
        assertEquals(2, store.unreadCount.value)
        store.setObserving(true)
        assertEquals(0, store.unreadCount.value)
        assertNotNull(store.payload(store.messages.value.first().id))
        store.clear()
        assertTrue(store.messages.value.isEmpty())
        assertFalse(store.unreadCount.value > 0)
    }
}
