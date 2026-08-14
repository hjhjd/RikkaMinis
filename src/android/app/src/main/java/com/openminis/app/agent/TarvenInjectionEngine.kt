package com.openminis.app.agent

import com.openminis.app.data.db.TarvenRuleEntity
import com.openminis.app.data.db.TarvenRuleType
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage

data class TarvenInjectionResult(
    val systemPrompt: String?,
    val messages: List<LLMMessage>,
)

object TarvenInjectionEngine {
    fun apply(
        systemPrompt: String?,
        messages: List<LLMMessage>,
        rules: List<TarvenRuleEntity>,
        placeholders: Map<String, String> = emptyMap(),
    ): TarvenInjectionResult {
        val active = rules.filter { it.isEnabled != 0 }.sortedBy { it.sortOrder }
        var system = systemPrompt.orEmpty()
        val systemRules = active.filter { it.ruleType == TarvenRuleType.SYSTEM_SUFFIX }
        val prepends = systemRules.filter { it.position == "prepend" }.map { render(it, placeholders) }
        val appends = systemRules.filter { it.position != "prepend" }.map { render(it, placeholders) }
        system = joinSections(prepends + listOf(system) + appends)

        val output = messages.map { it.copy() }.toMutableList()
        applyUserRules(output, active.filter { it.ruleType == TarvenRuleType.USER_SUFFIX }, placeholders)
        applyContextRules(output, active.filter { it.ruleType == TarvenRuleType.CONTEXT_INJECT }, placeholders)
        return TarvenInjectionResult(system.ifBlank { null }, output)
    }

    private fun applyUserRules(
        messages: MutableList<LLMMessage>,
        rules: List<TarvenRuleEntity>,
        placeholders: Map<String, String>,
    ) {
        if (rules.isEmpty()) return
        val index = messages.indexOfLast(::isRealUserMessage)
        if (index < 0) return
        val original = messages[index]
        val prepends = rules.filter { it.position == "prepend" }.map { render(it, placeholders) }
        val appends = rules.filter { it.position != "prepend" }.map { render(it, placeholders) }
        val injectedContent = joinSections(prepends + listOf(original.content) + appends)
        val injectedParts = if (original.contentParts.isEmpty()) {
            original.contentParts
        } else {
            buildList {
                if (prepends.isNotEmpty()) add(AgentContentPart.Text(joinSections(prepends)))
                addAll(original.contentParts)
                if (appends.isNotEmpty()) add(AgentContentPart.Text(joinSections(appends)))
            }
        }
        // Providers prefer structured contentParts whenever present. Updating
        // only the legacy flat content made user rules invisible on normal
        // persisted messages, which carry AgentContentPart.Text. Keep both
        // representations in sync while mutating only this request copy.
        messages[index] = original.copy(content = injectedContent, contentParts = injectedParts)
    }

    private fun applyContextRules(
        messages: MutableList<LLMMessage>,
        rules: List<TarvenRuleEntity>,
        placeholders: Map<String, String>,
    ) {
        rules.sortedWith(compareByDescending<TarvenRuleEntity> { it.depth ?: 0 }.thenBy { it.sortOrder })
            .forEach { rule ->
                val depth = (rule.depth ?: 0).coerceAtLeast(0)
                val desired = (messages.size - depth).coerceIn(0, messages.size)
                val safe = safeInsertionIndex(messages, desired)
                val role = if (rule.role == "assistant") LLMMessage.Role.ASSISTANT else LLMMessage.Role.USER
                messages.add(safe, LLMMessage(role = role, content = render(rule, placeholders)))
            }
    }

    private fun safeInsertionIndex(messages: List<LLMMessage>, desired: Int): Int {
        var index = desired.coerceIn(0, messages.size)
        if (index in 1 until messages.size) {
            val previousHasToolUse = messages[index - 1].contentParts.any { it is AgentContentPart.ToolUse }
            val nextOnlyToolResults = messages[index].contentParts.isNotEmpty() &&
                messages[index].contentParts.all { it is AgentContentPart.ToolResult }
            if (previousHasToolUse && nextOnlyToolResults) index++
        }
        return index.coerceAtMost(messages.size)
    }

    private fun isRealUserMessage(message: LLMMessage): Boolean {
        if (message.role != LLMMessage.Role.USER) return false
        val onlyToolResults = message.contentParts.isNotEmpty() &&
            message.contentParts.all { it is AgentContentPart.ToolResult } && message.content.isBlank()
        return !onlyToolResults && (message.content.isNotBlank() || message.imageParts.isNotEmpty() || message.audioParts.isNotEmpty())
    }

    private fun render(rule: TarvenRuleEntity, placeholders: Map<String, String>): String {
        var content = rule.content
        placeholders.forEach { (key, value) -> content = content.replace("{{$key}}", value) }
        return if (rule.wrap != 0) {
            "<minis_injection description=\"由 VCPMinis 规则系统注入\">\n$content\n</minis_injection>"
        } else content
    }

    private fun joinSections(parts: List<String>): String =
        parts.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n\n")
}
