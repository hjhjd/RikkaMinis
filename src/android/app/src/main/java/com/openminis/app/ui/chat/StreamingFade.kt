package com.openminis.app.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.toMutableStateList
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString

/**
 * [T-android-stream-fade] Batch fade-in for streamed markdown text.
 *
 * Newly appended characters are treated as one tail range and ease from α=0
 * to α=1 over [STREAM_FADE_DURATION_MS]. There is deliberately no per-word
 * stagger: stagger made coalesced stream batches look like a waterfall. Only
 * the streaming last block opts in via [LocalAppendOnlyFade]; everything else (history,
 * cold-loaded sessions, completed messages) renders fully opaque.
 *
 * Implementation:
 *  - Each MdText tracks its previous plainText prefix. When text extends that
 *    prefix, the complete suffix becomes one short-lived fade range.
 *  - A single `withFrameNanos` loop in the composable advances animation
 *    progress and writes the current alpha to a snapshot-state map. The
 *    MdText reads that map when composing its AnnotatedString overlay, so
 *    only this one MdText recomposes per frame — sibling blocks are inert.
 *  - When all ranges reach α=1 the loop suspends until a new append
 *    arrives, keeping idle cost at zero.
 *  - Large appends and excessive in-flight ranges render opaque immediately,
 *    preventing animation backlog during restore or parser reflow.
 */

internal val LocalAppendOnlyFade = compositionLocalOf { false }

// [T-android-streaming-incremental-inline] True only for the LIVE streaming tail
// block. When set, RenderBlock's Paragraph branch routes inline/math through
// the incremental cache (frozen closed prefix + fresh unclosed suffix) instead
// of re-scanning the whole growing paragraph every throttle tick. Off (false)
// for every frozen/history block, which keeps the plain per-block cache.
internal val LocalLiveIncremental = compositionLocalOf { false }

// 新增尾部整批淡入，不再逐词错峰。120ms 足够柔化跳变，又不会追赶下一批文本。
internal const val STREAM_FADE_DURATION_MS = 120L
internal const val MAX_ANIMATED_APPEND_CHARS = 512
private const val MAX_ACTIVE_FADE_RANGES = 4

private data class FadeRange(
    val start: Int,
    val end: Int,
)

internal class FadeController(
    private val nanoTime: () -> Long = System::nanoTime,
) {
    /** plainText prefix already seen — anything beyond this is fresh. */
    var lastPlainText: String = ""
        private set

    /** Active animating ranges. Frozen at α=1 ranges are removed each tick. */
    private val rangesState: SnapshotStateList<FadeRange> = mutableListOf<FadeRange>().toMutableStateList()

    /** start-time nanos per range (parallel to rangesState; same indexing). */
    private val rangeStartNanos = ArrayDeque<Long>()

    /** Per-range current alpha, updated each frame; read in [overlay]. */
    val alphas: SnapshotStateMap<Int, Float> = SnapshotStateMap()

    /** True when at least one range is still under α=1. Drives the frame loop. */
    val hasActiveRanges: Boolean get() = rangesState.isNotEmpty()

    fun ingest(newPlainText: String) {
        if (newPlainText == lastPlainText) return
        // 文本缩短或前缀变化代表 composable 被复用到新块：清空旧动画。
        if (!newPlainText.startsWith(lastPlainText)) {
            rangesState.clear()
            rangeStartNanos.clear()
            alphas.clear()
            lastPlainText = newPlainText
            return
        }
        val base = lastPlainText.length
        val appendLength = newPlainText.length - base
        lastPlainText = newPlainText
        if (appendLength <= 0) return

        // 大批恢复/重排直接显示，避免动画排队追赶已经到达的内容。
        if (appendLength > MAX_ANIMATED_APPEND_CHARS || rangesState.size >= MAX_ACTIVE_FADE_RANGES) {
            return
        }
        rangesState.add(FadeRange(base, newPlainText.length))
        rangeStartNanos.addLast(nanoTime())
    }

    internal val activeRangeCount: Int get() = rangesState.size

    /**
     * Advance every range to its current alpha based on [nowNanos]. Returns
     * false when no ranges remain animating (caller can suspend the loop).
     */
    fun tick(nowNanos: Long): Boolean {
        if (rangesState.isEmpty()) return false
        val finished = mutableListOf<Int>()
        for (i in rangesState.indices) {
            val r = rangesState[i]
            val startNs = rangeStartNanos.elementAt(i)
            val elapsedMs = (nowNanos - startNs) / 1_000_000L
            val alpha = if (elapsedMs <= 0) 0f
            else if (elapsedMs >= STREAM_FADE_DURATION_MS) 1f
            else {
                val t = elapsedMs.toFloat() / STREAM_FADE_DURATION_MS
                // Ease-out cubic 1 - (1-t)^3 (matches iOS animator curve).
                val inv = 1f - t
                1f - inv * inv * inv
            }
            alphas[r.start] = alpha
            if (alpha >= 1f) finished.add(i)
        }
        // Pop finished ranges from the end so indices shift predictably.
        for (i in finished.asReversed()) {
            val r = rangesState.removeAt(i)
            rangeStartNanos.removeAt(i)
            alphas.remove(r.start)
        }
        return rangesState.isNotEmpty()
    }

    /**
     * Build an AnnotatedString that re-colours each active range to apply
     * its current alpha. Inactive (α=1) ranges drop out automatically as
     * [tick] removes them; the surrounding text and original spans are
     * preserved.
     */
    fun overlay(base: AnnotatedString, baseColor: Color): AnnotatedString {
        if (rangesState.isEmpty()) return base
        return buildAnnotatedString {
            append(base)
            for (r in rangesState) {
                val a = alphas[r.start] ?: 0f
                if (r.end > base.length) continue
                addStyle(
                    SpanStyle(color = baseColor.copy(alpha = a)),
                    r.start,
                    r.end,
                )
            }
        }
    }
}

@Composable
internal fun rememberFadeController(): FadeController =
    remember { FadeController() }

/**
 * Drives the per-frame tick for [controller]. Suspends when nothing is
 * animating; resumes whenever [controller.hasActiveRanges] flips back to
 * true. Single instance per MdText so each animating block runs independently.
 */
@Composable
internal fun FadeFrameDriver(controller: FadeController) {
    // ticker is read inside withFrameNanos so the body re-suspends when no
    // ranges are active; a state read on hasActiveRanges restarts it.
    val active = controller.hasActiveRanges
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        while (true) {
            val anyActive = withFrameNanos { now -> controller.tick(now) }
            if (!anyActive) break
        }
    }
}

/**
 * Hold a stable mutable holder for the most recent base color so the
 * overlay() call doesn't need MdText to pass it through composition every
 * time. Currently unused externally but kept as a hook for future fade
 * extensions (color-shift, ramp-up speed) that depend on the surrounding
 * theme color.
 */
internal data class FadeColorHolder(var color: Color = Color.Unspecified) {
    val state = mutableStateOf(color)
}
