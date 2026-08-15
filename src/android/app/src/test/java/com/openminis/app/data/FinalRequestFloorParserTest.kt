package com.openminis.app.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FinalRequestFloorParserTest {
    @Test
    fun `顶层工具定义不进入快照且默认零层为系统提示词`() {
        val request = JSONObject(
            """{
              "model":"test",
              "messages":[
                {"role":"system","content":"人格\n\n工具规则"},
                {"role":"user","content":"你好"}
              ],
              "tools":[{"type":"function","function":{"name":"file_read"}}]
            }""",
        )

        val floors = FinalRequestFloorParser.parse("openai", request)
        val roles = (0 until floors.length()).map { floors.getJSONObject(it).getString("role") }

        assertEquals(listOf("SYSTEM", "USER", "REQUEST"), roles)
        assertTrue(floors.getJSONObject(0).getString("content").contains("人格\n\n工具规则"))
        assertTrue((0 until floors.length()).none {
            floors.getJSONObject(it).getString("content").contains("file_read")
        })
    }

    @Test
    fun `工具请求与工具结果不进入上下文快照`() {
        val request = JSONObject(
            """{
              "input":[
                {"role":"user","content":"执行任务"},
                {"type":"function_call","call_id":"call-1","name":"file_read","arguments":"{}"},
                {"type":"function_call_output","call_id":"call-1","output":"完成"},
                {"role":"assistant","content":"任务完成"}
              ]
            }""",
        )
        val floors = FinalRequestFloorParser.parse("openai", request)
        val roles = (0 until floors.length()).map { floors.getJSONObject(it).getString("role") }
        assertEquals(listOf("USER", "AI", "REQUEST"), roles)
    }

    @Test
    fun `Chat Completions 的 tool 结果消息不进入快照`() {
        val request = JSONObject(
            """{"messages":[{"role":"tool","tool_call_id":"call-1","content":"完成"}]}""",
        )
        val floors = FinalRequestFloorParser.parse("openai", request)
        assertEquals(listOf("REQUEST"), (0 until floors.length()).map {
            floors.getJSONObject(it).getString("role")
        })
    }
}
