package com.openminis.app.agent

import android.content.Context

/**
 * 系统提示词配置的唯一入口。
 *
 * 内置默认值放在 assets/prompts 中，避免继续散落在 ViewModel；用户配置只保存覆盖值。
 * 覆盖值为空（包括全空白）时始终读取内置中文默认值，因此升级默认提示词不会被旧副本遮蔽。
 */
object SystemPromptPreferences {
    private const val PREFS = "system_prompt_preferences"
    // Keep the existing preference key so users' saved overrides survive the
    // main-prompt → tool-prompt terminology change.
    private const val KEY_TOOL = "main_template"
    private const val LEGACY_KEY_IDENTITY = "identity_template"

    const val MEMORY_TOOL_BULLETS = "{{memory_tool_bullets}}"
    const val MEMORY_SYSTEM_SECTION = "{{memory_system_section}}"
    const val RUNTIME_CONTEXT = "{{runtime_context}}"
    const val SANDBOX_RUNTIME_CONTEXT = "{{sandbox_runtime_context}}"

    private const val TOOL_ASSET = "prompts/default_tool_zh.md"

    fun toolOverride(context: Context): String = prefs(context).getString(KEY_TOOL, "").orEmpty()

    fun defaultToolTemplate(context: Context): String = readAsset(context, TOOL_ASSET)

    fun toolTemplate(context: Context): String =
        toolOverride(context).takeIf { it.isNotBlank() } ?: defaultToolTemplate(context)

    fun save(context: Context, toolTemplate: String) {
        prefs(context).edit()
            .remove(LEGACY_KEY_IDENTITY)
            .putString(KEY_TOOL, toolTemplate.trim())
            .apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(LEGACY_KEY_IDENTITY).remove(KEY_TOOL).apply()
    }

    fun renderToolTemplate(
        template: String,
        memoryToolBullets: String,
        memorySystemSection: String,
        runtimeContext: String,
    ): String = template
        .replace(MEMORY_TOOL_BULLETS, memoryToolBullets)
        .replace(MEMORY_SYSTEM_SECTION, memorySystemSection)
        .replace(RUNTIME_CONTEXT, runtimeContext)
        .trimEnd()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun readAsset(context: Context, path: String): String =
        context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }.trim()
}
