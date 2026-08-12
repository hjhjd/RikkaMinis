package com.openminis.app.distributed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DistributedConnectionUrlTest {
    @Test
    fun `构造 VCPToolBox 兼容连接地址并编码密钥`() {
        val url = DistributedConnectionManager.buildConnectionUrl(
            "ws://192.168.1.2:5800/",
            "key/with space",
        )
        requireNotNull(url)
        assertEquals(
            "ws://192.168.1.2:5800/vcp-distributed-server/VCP_Key=key%2Fwith%20space",
            url,
        )
    }

    @Test
    fun `只接受 WebSocket 地址`() {
        assertTrue(DistributedSettingsRepository.isValidWsUrl("ws://localhost:5800"))
        assertTrue(DistributedSettingsRepository.isValidWsUrl("wss://example.com"))
        assertFalse(DistributedSettingsRepository.isValidWsUrl("https://example.com"))
        assertFalse(DistributedSettingsRepository.isValidWsUrl("not-a-url"))
    }
}
