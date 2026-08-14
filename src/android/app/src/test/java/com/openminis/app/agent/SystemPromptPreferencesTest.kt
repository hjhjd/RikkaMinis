package com.openminis.app.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class SystemPromptPreferencesTest {
    @Test
    fun `沙箱占位符可自由排布并重复解析`() {
        val values = mapOf(
            SystemPromptPreferences.SANDBOX_RUNTIME_CONTEXT to "完整上下文",
            SystemPromptPreferences.SANDBOX_DEFAULT_ID to "box-1",
            SystemPromptPreferences.SANDBOX_ONLINE_IDS to "box-1,box-2",
        )
        val rendered = SystemPromptPreferences.renderPlaceholders(
            "id={{sandbox_default_id}}\n{{sandbox_runtime_context}}\nids={{sandbox_online_ids}}\nid={{sandbox_default_id}}",
            values,
        )
        assertEquals("id=box-1\n完整上下文\nids=box-1,box-2\nid=box-1", rendered)
    }
}
