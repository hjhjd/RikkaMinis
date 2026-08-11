package com.openminis.app.agent

import android.content.Context
import com.openminis.app.logging.AppLogger
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [T-soul-md] Persistent personality / identity file mirroring iOS
 * SoulStore (`src/ios/Agent/Session/SoulStore.swift`, commit 6370d5a).
 *
 * SOUL.md lives next to GLOBAL.md and the daily memory logs under
 * `<filesDir>/minis-global/memory/`. Format: YAML frontmatter delimited
 * by `---` followed by a Markdown body. The body becomes Layer 1 of the
 * system prompt; `name` + `emoji` drive the chat assistant bubble header.
 *
 * Components below mirror iOS one-for-one:
 *   - [SoulMetadata]  – four-field struct (name/emoji/style/lang)
 *   - [SoulFile]      – metadata + body pair
 *   - [SoulMDParser]  – lossless-ish parse / serialize
 *   - [SoulStore]     – file I/O, default content, fallback identity,
 *                       cachedMetadata + onChanged listener
 *   - [SystemPromptBuilder] – injection-scrubbed personalitySection() for the
 *                             agent system prompt
 */

data class SoulMetadata(
    val name: String,
    /**
     * Raw `emoji` value as it appears in SOUL.md's YAML frontmatter.
     *
     * Kept ONLY for round-trip preservation when serializing back to disk:
     * a user might have set a custom emoji on another platform / older
     * build, and we don't want to silently overwrite their file when
     * Settings → Save runs on this device.
     *
     * **Do NOT use this for UI.** All UI surfaces (Settings preview,
     * chat bubble header) must read [displayEmoji] instead, which is
     * locked to the canonical ✨ sparkle. This decision matches the
     * iOS rollback of the emoji-customization field.
     */
    val emoji: String,
    val style: String,
    /** `"auto"`, `"zh"`, `"en"`, or any free-form tag. */
    val lang: String,
) {
    /**
     * The emoji the user actually sees in Settings preview + chat bubble
     * header. Locked to ✨ regardless of what's in the SOUL.md file —
     * the emoji-customization field was removed without breaking
     * file-format compatibility.
     */
    val displayEmoji: String get() = DISPLAY_EMOJI

    companion object {
        /** Locked identity emoji shown in every UI surface. */
        const val DISPLAY_EMOJI = "✨"

        val DEFAULT = SoulMetadata(
            // Default personality name is aligned with the app name
            // (R.string.app_name = "RikkaMinis"). Kept as a plain literal
            // here because SoulMetadata.DEFAULT is a Context-free constant;
            // every UI surface falls back to this single source of truth.
            name = "RikkaMinis",
            // Default emoji is intentionally empty — UI uses the fixed
            // [displayEmoji] sparkle and [SoulMDParser.serialize] no longer
            // writes the `emoji:` line. The field is kept on the struct only
            // so the parser can round-trip an `emoji: "..."` line that
            // survives in an old user-authored SOUL.md; the next save drops
            // it on disk too.
            emoji = "",
            style = "",
            lang = "auto",
        )
    }
}

data class SoulFile(val metadata: SoulMetadata, val body: String)

/**
 * Lightweight YAML-frontmatter parser. Intentionally NOT a real YAML
 * implementation — only handles the four keys used by SOUL.md, each on
 * its own line in `key: "value"` form. Designed to be lossless enough
 * that `serialize(parse(s)) == s` for files this app writes itself.
 */
object SoulMDParser {

    fun parse(source: String): SoulFile {
        val trimmedLeading = source.dropWhile { it == '\n' || it == '\r' }
        if (!trimmedLeading.startsWith("---")) {
            return SoulFile(SoulMetadata.DEFAULT, source)
        }
        val lines = trimmedLeading.split("\n")
        if (lines.firstOrNull()?.trim() != "---") {
            return SoulFile(SoulMetadata.DEFAULT, source)
        }
        val closeIdx = lines.drop(1).indexOfFirst { it.trim() == "---" }
        if (closeIdx < 0) return SoulFile(SoulMetadata.DEFAULT, source)
        // indexOfFirst on drop(1) result is offset by 1 — restore absolute index.
        val absCloseIdx = closeIdx + 1
        val frontmatter = lines.subList(1, absCloseIdx)
        val bodyLines = if (absCloseIdx + 1 <= lines.size) lines.subList(absCloseIdx + 1, lines.size) else emptyList()
        val body = bodyLines.joinToString("\n").dropWhile { it == '\n' || it == '\r' }

        var name = SoulMetadata.DEFAULT.name
        var emoji = SoulMetadata.DEFAULT.emoji
        var style = SoulMetadata.DEFAULT.style
        var lang = SoulMetadata.DEFAULT.lang
        for (raw in frontmatter) {
            val line = raw.trim()
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val key = line.substring(0, colon).trim().lowercase()
            var value = line.substring(colon + 1).trim()
            if (value.length >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length - 1)
            }
            when (key) {
                "name" -> if (value.isNotEmpty()) name = value
                "emoji" -> if (value.isNotEmpty()) emoji = value
                "style" -> style = value
                "lang" -> if (value.isNotEmpty()) lang = value
                else -> Unit
            }
        }
        return SoulFile(SoulMetadata(name, emoji, style, lang), body)
    }

    /**
     * Serialize back to SOUL.md text. Emits a 3-key frontmatter block
     * (name / style / lang) followed by an empty line and the body.
     *
     * The `emoji` field is deliberately NOT written — the UI is locked to a
     * fixed sparkle ([SoulMetadata.DISPLAY_EMOJI]), so persisting a `emoji:`
     * line would imply user-controlled customization that doesn't exist.
     * Old files containing `emoji: "..."` still parse cleanly (the value is
     * kept in memory for round-trip safety) but the line is dropped on the
     * next save, naturally migrating disk state to the new schema.
     */
    fun serialize(file: SoulFile): String {
        val sb = StringBuilder()
        sb.append("---\n")
        sb.append("name: \"").append(escape(file.metadata.name)).append("\"\n")
        sb.append("style: \"").append(escape(file.metadata.style)).append("\"\n")
        sb.append("lang: \"").append(escape(file.metadata.lang)).append("\"\n")
        sb.append("---\n\n")
        sb.append(file.body)
        if (!sb.endsWith("\n")) sb.append("\n")
        return sb.toString()
    }

    private fun escape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")
}

/**
 * Result of a SOUL.md body length check. The hard limit depends on what
 * language the body is written in — Chinese / Japanese / Korean text is
 * information-dense per character so the cap is in chars, while English /
 * other Latin-alphabet text is capped by word count.
 *
 * Mirrors `SoulStore.isOverLimit(_:)` on iOS. The associated value carries
 * the observed magnitude and the matching cap so callers can surface a
 * precise "your body is N / max M" message.
 */
sealed class SoulBodyLimitCheck {
    object Ok : SoulBodyLimitCheck()
    data class OverLimitChinese(val chars: Int, val cap: Int) : SoulBodyLimitCheck()
    data class OverLimitEnglish(val words: Int, val cap: Int) : SoulBodyLimitCheck()

    val isOverLimit: Boolean get() = this !is Ok
}

/**
 * File-system helpers + cached metadata. Methods are intentionally
 * `Context`-scoped instead of taking a global singleton — keeps the
 * dependency surface explicit and avoids depending on MinisApp from
 * non-application call sites (tests, previews).
 */
object SoulStore {

    private const val TAG = "SoulStore"
    private const val FILE_NAME = "SOUL.md"
    private const val MEMORY_SUBDIR = "minis-global/memory"

    fun fileLocation(context: Context): File =
        File(File(context.filesDir, MEMORY_SUBDIR), FILE_NAME)

    // -- Body length rules (language-aware) ----------------------------
    //
    // The personality body has a hard cap applied at every write surface
    // (Settings UI Save button, minis-config writer, and the
    // prompt-build-time fallback in `SystemPromptBuilder`). The cap is
    // language-dependent: CJK text is information-dense per character so
    // 1600 chars is the limit; Latin / mixed text gets a 1000-word cap.
    // (T-soul-body-limit-2000 — both axes doubled from 800 / 500 to align
    //  with iOS bumping its single 1000→2000 token cap.)
    //
    // CJK ratio of 30% switches the rule. A body whose CJK glyphs make up
    // more than 30% of all unicode code points is treated as CJK-leaning
    // and counted by character; anything at-or-below 30% is counted by
    // word. 30% is the lowest watermark at which Chinese / Japanese
    // clearly dominates — high enough to ignore stray CJK quotes or
    // proper nouns in an English document, low enough that a majority-CJK
    // paragraph with a few inline English terms still counts as CJK.

    const val CJK_RATIO_THRESHOLD: Double = 0.3
    const val CHINESE_CHAR_LIMIT: Int = 1600
    const val ENGLISH_WORD_LIMIT: Int = 1000

    /**
     * Classify [body] under the language-aware length rules above. Empty
     * / whitespace-only bodies always return [SoulBodyLimitCheck.Ok].
     */
    fun isOverLimit(body: String): SoulBodyLimitCheck {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return SoulBodyLimitCheck.Ok

        var cjk = 0
        var total = 0
        var i = 0
        while (i < trimmed.length) {
            val cp = trimmed.codePointAt(i)
            total += 1
            if (isCJKCodePoint(cp)) cjk += 1
            i += Character.charCount(cp)
        }
        val ratio = if (total > 0) cjk.toDouble() / total else 0.0
        return if (ratio > CJK_RATIO_THRESHOLD) {
            // Code-point count — closest analogue to iOS grapheme cluster
            // count for the CJK ranges we care about (no combining marks
            // / no flag emojis in Han/Kana/Hangul). Bytes / UTF-16 code
            // units would over-count surrogate-pair CJK extensions.
            val chars = trimmed.codePointCount(0, trimmed.length)
            if (chars > CHINESE_CHAR_LIMIT) SoulBodyLimitCheck.OverLimitChinese(chars, CHINESE_CHAR_LIMIT)
            else SoulBodyLimitCheck.Ok
        } else {
            // Whitespace-delimited word count. `split(Regex("\\s+"))` on
            // a trimmed string collapses consecutive whitespace into a
            // single delimiter.
            val words = trimmed.split(Regex("\\s+")).count { it.isNotEmpty() }
            if (words > ENGLISH_WORD_LIMIT) SoulBodyLimitCheck.OverLimitEnglish(words, ENGLISH_WORD_LIMIT)
            else SoulBodyLimitCheck.Ok
        }
    }

    /**
     * True if [cp] belongs to any CJK Unified Ideograph or Kana / Hangul
     * range. Covers Chinese (Simplified + Traditional), Japanese
     * (Hiragana + Katakana + Kanji shared with CJK Unified), and Korean
     * (Hangul Syllables + Jamo). Wider than just U+4E00–U+9FFF so the
     * extensions and Hangul also count toward the ratio.
     */
    private fun isCJKCodePoint(cp: Int): Boolean {
        // CJK Unified Ideographs + Ext A
        if (cp in 0x4E00..0x9FFF) return true
        if (cp in 0x3400..0x4DBF) return true
        // CJK Unified Ideographs Ext B, C/D/E/F, G/H
        if (cp in 0x20000..0x2A6DF) return true
        if (cp in 0x2A700..0x2EBEF) return true
        if (cp in 0x30000..0x323AF) return true
        // Hiragana, Katakana, Katakana Phonetic Extensions
        if (cp in 0x3040..0x309F) return true
        if (cp in 0x30A0..0x30FF) return true
        if (cp in 0x31F0..0x31FF) return true
        // Hangul Syllables, Jamo, Compatibility Jamo
        if (cp in 0xAC00..0xD7AF) return true
        if (cp in 0x1100..0x11FF) return true
        if (cp in 0x3130..0x318F) return true
        return false
    }

    /**
     * The verbatim default file content used both for first-run
     * auto-create and the "Restore Default" button in Settings.
     *
     * IMPORTANT: the body contains ONLY personality / character / voice
     * guidance. It must NOT contain the "You are <name>, …" identity
     * sentence — that boilerplate is owned by [SystemPromptBuilder] and
     * stitched in around the body at prompt-build time. Mixing identity
     * boilerplate into the body would (a) expose internal prompt
     * structure to users in the Settings Personality editor, and (b)
     * double-up "You are X" lines whenever the template-rendered prompt
     * also produces one.
     *
     * 默认人格正文使用中文；已有 SOUL.md 属于用户数据，升级时不会被覆盖。
     */
    val DEFAULT_CONTENT: String = """---
name: "RikkaMinis"
style: ""
lang: "auto"
---

**不要表演——直接帮忙。** 跳过“当然可以！”和“很高兴为你效劳！”之类的客套话，直接完成工作。

**要有立场。** 可以提出不同意见、表达偏好，也可以觉得某些事情有趣而另一些乏味。

**先行动，再提问。** 能自己查清楚的就去查，带着答案回来，而不是只抛出问题。
"""

    /**
     * Create SOUL.md with [DEFAULT_CONTENT] iff it does not exist yet.
     * Safe to call on every launch — never overwrites existing user edits.
     */
    fun ensureExists(context: Context) {
        val file = fileLocation(context)
        if (file.exists()) return
        try {
            file.parentFile?.mkdirs()
            file.writeText(DEFAULT_CONTENT)
            AppLogger.info(TAG, "seeded SOUL.md at ${file.absolutePath}")
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "ensureExists failed: ${t.message}")
        }
    }

    /**
     * Read + parse the current SOUL.md. Returns null when the file is
     * missing or unreadable; an empty body parses to default-meta with
     * empty body and callers may treat that as "fall back to default".
     */
    fun load(context: Context): SoulFile? {
        val file = fileLocation(context)
        if (!file.exists()) return null
        return try {
            SoulMDParser.parse(file.readText())
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "SOUL.md load failed: ${t.message}")
            null
        }
    }

    /** Atomic write through a `.tmp` sibling, then rename. */
    fun save(context: Context, file: SoulFile) {
        val target = fileLocation(context)
        target.parentFile?.mkdirs()
        val text = SoulMDParser.serialize(file)
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            // Fallback: copy + delete tmp on filesystems that reject
            // cross-inode rename (shouldn't apply inside filesDir, but
            // defensive — keeps us from leaving a stale .tmp behind).
            target.writeText(text)
            tmp.delete()
        }
        _cachedMetadata.value = file.metadata
    }

    /**
     * Cached metadata for synchronous call sites that cannot re-read the
     * file every recomposition (chat bubble header in particular). Updated
     * by [refreshCache] and [save]. Default value is the same fallback
     * the Settings UI shows when the file is missing.
     */
    private val _cachedMetadata = MutableStateFlow(SoulMetadata.DEFAULT)
    val cachedMetadata: StateFlow<SoulMetadata> = _cachedMetadata.asStateFlow()

    /**
     * Re-read SOUL.md into [cachedMetadata]. Call once at app launch
     * (after [ensureExists]) and any time the file is rewritten outside
     * of [save] (currently only the Settings UI uses save()).
     */
    fun refreshCache(context: Context) {
        val parsed = load(context)
        _cachedMetadata.value = parsed?.metadata ?: SoulMetadata.DEFAULT
    }
}

/**
 * Composes the user-controlled SOUL personality section of the agent system
 * prompt. The app no longer prepends a built-in identity sentence; `name`
 * remains UI metadata while body, style and language drive model behavior.
 */
object SystemPromptBuilder {

    private const val TAG = "Soul"

    /**
     * Patterns used by [scrubInjections] to drop prompt-injection lines
     * from the personality body at prompt-build time. Exposed so write
     * paths (minis-config `soul.body` setter) can reject the same set
     * of patterns rather than silently scrubbing — see iOS parity:
     * the agent should see a clear error, not a silent edit.
     */
    val INJECTION_PATTERNS: List<Regex> = listOf(
        Regex("ignore.{0,30}previous.{0,30}instructions?", RegexOption.IGNORE_CASE),
        Regex("disregard.{0,30}(previous|prior).{0,30}instructions?", RegexOption.IGNORE_CASE),
        Regex("forget.{0,30}(previous|prior).{0,30}instructions?", RegexOption.IGNORE_CASE),
    )

    /**
     * `true` iff [s] contains a known prompt-injection pattern. Used by
     * write-side validation to reject the change at the configure call,
     * which surfaces a clean error to the agent (instead of the silent
     * line-drop that [scrubInjections] performs at prompt-build time).
     */
    fun containsInjectionPattern(s: String): Boolean =
        INJECTION_PATTERNS.any { it.containsMatchIn(s) }

    /** Render SOUL.md body, style and language with system-owned labels. */
    fun personalitySection(context: Context): String {
        val file = SoulStore.load(context)
        val style = (file?.metadata?.style ?: "").trim()
        // [T-soul-lang-wire] The `lang` frontmatter field (auto / zh / en) is
        // now actually injected into the system prompt. Previously it was only
        // parsed/serialized/displayed but never reached the model — switching
        // "Chinese"/"English" in Settings had zero effect and replies stayed
        // English because the default body is English + the base prompt default
        // is match-the-user's-input. When `lang` is explicitly zh / en we emit a
        // hard reply-language directive that overrides that default; auto keeps
        // the existing follow-the-user behavior.
        val lang = file?.metadata?.lang?.lowercase()?.trim().orEmpty()
        val langDirective = when (lang) {
            "zh" -> "\n\n无论用户使用何种语言输入，都使用中文回复；只有用户明确要求其他语言时才切换。"
            "en" -> "\n\n无论用户使用何种语言输入，都使用英文回复；只有用户明确要求其他语言时才切换。"
            else -> "" // “自动”或未知值：跟随用户输入语言
        }

        // [T-soul-hint] Fixed hint telling the model how SOUL fields can be
        // changed. Always appended (with or without a personality body) so
        // the model never says "I can't change my personality". This hint
        // is system-owned text and is NOT counted against the user-facing
        // SOUL body length limit (#356 / 1000 EN words / 1600 CN chars).
        val soulEditHint =
            "---\n" +
            "SOUL.md 字段（name / style / lang / body）可以通过两种方式编辑：\n" +
            "1. 工具：调用 `minis-config` 提议修改（必须由用户确认）。\n" +
            "2. 界面：请用户直接前往设置 → 人格。\n" +
            "根据当前情境选择用户更方便的方式；不要声称无法修改自己的人格。"

        // [T-soul-style-injection 2026-05-18, port iOS 0409e24f] The `style`
        // frontmatter field (response voice / tone / formatting preference,
        // e.g. a row of emojis or "concise, no markdown") was parsed and
        // shown in the UI but never reached the model — only the Markdown
        // body was injected. Render it as its own labeled paragraph so the
        // model treats it as a hard constraint on response style rather
        // than free-form context.
        fun styleBlock(s: String): String {
            if (s.isEmpty()) return ""
            // [T-agent-prompt-consistency-pass] Mirrors iOS SoulStore: a user-authored
            // style that prescribes a reply language is the more specific preference
            // and wins over the base prompt's match-the-user's-language default.
            return "\n\n回复风格（来自 SOUL.md 的 `style`；除非用户明确要求，否则每次回复都应遵循；如果其中指定了回复语言，则优先于默认的语言跟随规则）：\n$s"
        }

        val body = file?.body
        val trimmed = body?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            val rendered = styleBlock(style).trimStart() + langDirective + "\n\n" + soulEditHint
            return rendered.trimStart() + "\n\n"
        }

        // Reject (NOT truncate) bodies that exceed the language-aware
        // limit. The old head/tail truncation silently dropped half the
        // user's text — falling back to identity-only is the safer
        // signal: the user notices the personality isn't taking effect,
        // opens Settings, and sees the same red over-limit warning the
        // Save button surfaces. Write paths already reject over-limit;
        // this branch only triggers for an on-disk file written before
        // this rule existed (or via shell / another device).
        val check = SoulStore.isOverLimit(trimmed)
        if (check.isOverLimit) {
            AppLogger.warning(TAG, "personality body is over the language-aware limit ($check) — omitting it from the system prompt.")
            val rendered = styleBlock(style).trimStart() + langDirective + "\n\n" + soulEditHint
            return rendered.trimStart() + "\n\n"
        }

        val personality = scrubInjections(trimmed)

        return "人格（来自 SOUL.md，定义你的性格与表达方式；若与用户最新消息冲突，以用户最新消息为准）：\n" +
            personality +
            styleBlock(style) +
            langDirective +
            "\n\n" +
            soulEditHint +
            "\n\n"
    }

    /**
     * Drop lines that look like an attempt to subvert the system prompt.
     * SOUL.md is user-authored personality — instructions to the model
     * have no business being there. Casts a wide net on purpose: matches
     * "ignore previous instructions" and the usual variants.
     */
    private fun scrubInjections(s: String): String =
        s.split("\n")
            .filter { line -> INJECTION_PATTERNS.none { it.containsMatchIn(line) } }
            .joinToString("\n")
}
