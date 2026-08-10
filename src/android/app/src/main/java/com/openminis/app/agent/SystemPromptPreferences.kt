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
    private const val KEY_IDENTITY = "identity_template"
    private const val KEY_MAIN = "main_template"

    const val NAME_PLACEHOLDER = "{name}"
    const val MEMORY_TOOL_BULLETS = "{{memory_tool_bullets}}"
    const val MEMORY_SYSTEM_SECTION = "{{memory_system_section}}"

    private const val IDENTITY_ASSET = "prompts/default_identity_zh.md"
    private const val MAIN_ASSET = "prompts/default_system_zh.md"

    fun identityOverride(context: Context): String = prefs(context).getString(KEY_IDENTITY, "").orEmpty()
    fun mainOverride(context: Context): String = prefs(context).getString(KEY_MAIN, "").orEmpty()

    fun identityTemplate(context: Context): String =
        identityOverride(context).takeIf { it.isNotBlank() } ?: readAsset(context, IDENTITY_ASSET)

    fun mainTemplate(context: Context): String =
        mainOverride(context).takeIf { it.isNotBlank() } ?: readAsset(context, MAIN_ASSET)

    fun save(context: Context, identityTemplate: String, mainTemplate: String) {
        prefs(context).edit()
            .putString(KEY_IDENTITY, identityTemplate.trim())
            .putString(KEY_MAIN, mainTemplate.trim())
            .apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_IDENTITY).remove(KEY_MAIN).apply()
    }

    fun renderMainTemplate(context: Context, memoryToolBullets: String, memorySystemSection: String): String =
        mainTemplate(context)
            .replace(MEMORY_TOOL_BULLETS, memoryToolBullets)
            .replace(MEMORY_SYSTEM_SECTION, memorySystemSection)
            .trimEnd()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun readAsset(context: Context, path: String): String =
        context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }.trim()
}
