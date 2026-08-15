package com.openminis.app.data

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonReadableTextTest {
    @Test
    fun `消息 JSON 展开为可读字段而非源码`() {
        val value = JSONObject("""{"source":"messages[1]","value":{"role":"user","content":[{"type":"text","text":"你好"}]}}""")
        val text = JsonReadableText.render(value)
        assertTrue(text.contains("来源：messages[1]"))
        assertTrue(text.contains("文本：你好"))
        assertFalse(text.contains("{\""))
        assertFalse(text.contains("role"))
    }

    @Test
    fun `工具结构显示标签和列表`() {
        val value = JSONObject("""{"tools":[{"name":"file_read","description":"读取文件","required":["path"]}]}""")
        val text = JsonReadableText.render(value)
        assertTrue(text.contains("工具定义"))
        assertTrue(text.contains("名称：file_read"))
        assertTrue(text.contains("• path"))
    }
}
