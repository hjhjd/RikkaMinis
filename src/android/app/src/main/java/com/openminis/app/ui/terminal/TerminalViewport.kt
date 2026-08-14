package com.openminis.app.ui.terminal

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.openminis.app.sandbox.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlin.math.max
import kotlin.math.roundToInt

/** Native Termux viewport and its Android input bridge. */
@Composable
internal fun TerminalViewport(
    terminalSession: TerminalSession,
    sessionState: TerminalSession.State,
    ctrlDown: Boolean,
    altDown: Boolean,
    modifier: Modifier = Modifier,
) {
    // AndroidView.factory runs once; keep modifier-key callbacks pointed at
    // the latest Compose state across later recompositions.
    val currentCtrlDown by rememberUpdatedState(ctrlDown)
    val currentAltDown by rememberUpdatedState(altDown)

    AndroidView(
        factory = { context ->
            val terminal = TerminalView(context, null).apply {
                setTextSize(24)
                setTypeface(jetBrainsMonoTypeface(context))
                val terminalView = this
                setTerminalViewClient(
                    MinisTerminalViewClient(
                        view = terminalView,
                        getControlDown = { currentCtrlDown },
                        getAltDown = { currentAltDown },
                    ),
                )
                isFocusable = true
                isFocusableInTouchMode = true
                terminalSession.attachView(this)
            }
            TerminalViewportLayout(context, terminal)
        },
        update = { layout ->
            val view = layout.terminal
            // The PTY boots asynchronously. State is deliberately read by the
            // caller so this update runs again when the session becomes ready.
            val session = terminalSession.termuxSession
            if (sessionState == TerminalSession.State.RUNNING &&
                session != null && view.mTermSession != session
            ) {
                view.attachSession(session)
            }
            terminalSession.attachView(view)
        },
        onRelease = { layout -> terminalSession.detachView(layout.terminal) },
        modifier = modifier,
    )
}

private class TerminalViewportLayout(
    context: android.content.Context,
    val terminal: TerminalView,
) : FrameLayout(context) {
    private val indicator = TerminalScrollIndicator(context, terminal)

    init {
        addView(terminal, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(indicator, LayoutParams(dp(4), LayoutParams.MATCH_PARENT, android.view.Gravity.END))
        terminal.setOnTouchListener { _, _ ->
            indicator.invalidate()
            false
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
}

private class TerminalScrollIndicator(
    context: android.content.Context,
    private val terminal: TerminalView,
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(196, 167, 245) }
    private val density = resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val emulator = terminal.mEmulator ?: return scheduleNextFrame()
        val totalRows = emulator.screen.activeRows
        val visibleRows = emulator.mRows
        val scrollableRows = totalRows - visibleRows
        if (scrollableRows > 0 && height > 0) {
            val thumbHeight = max(24f * density, height * visibleRows.toFloat() / totalRows)
                .coerceAtMost(height.toFloat())
            val offsetRows = (totalRows + terminal.topRow - visibleRows).coerceIn(0, scrollableRows)
            val top = (height - thumbHeight) * offsetRows.toFloat() / scrollableRows
            canvas.drawRoundRect(0f, top, width.toFloat(), top + thumbHeight, width / 2f, width / 2f, paint)
        }
        scheduleNextFrame()
    }

    private fun scheduleNextFrame() {
        if (isAttachedToWindow) postInvalidateDelayed(120L)
    }
}

private class MinisTerminalViewClient(
    private val view: TerminalView,
    private val getControlDown: () -> Boolean,
    private val getAltDown: () -> Boolean,
) : TerminalViewClient {
    private var textSizePx = DEFAULT_TEXT_SIZE_PX
    override fun onSingleTapUp(e: MotionEvent) {
        view.requestFocus()
        val imm = view.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
            as? android.view.inputmethod.InputMethodManager
        imm?.showSoftInput(view, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }

    override fun onLongPress(event: MotionEvent): Boolean = false

    override fun onScale(scale: Float): Float {
        // `scale` is cumulative because TerminalView retains our return value.
        // Accumulating sub-pixel gesture deltas avoids the sticky one-pixel
        // steps caused by resetting the scale baseline on every callback.
        val nextSize = (DEFAULT_TEXT_SIZE_PX * scale).roundToInt()
            .coerceIn(MIN_TEXT_SIZE_PX, MAX_TEXT_SIZE_PX)
        if (nextSize != textSizePx) {
            textSizePx = nextSize
            view.setTextSize(nextSize)
            view.invalidate()
        }
        return textSizePx.toFloat() / DEFAULT_TEXT_SIZE_PX
    }
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: com.termux.terminal.TerminalSession): Boolean = false
    override fun onKeyDown(keyCode: Int, event: KeyEvent, session: com.termux.terminal.TerminalSession): Boolean = false
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = false
    override fun readControlKey(): Boolean = getControlDown()
    override fun readAltKey(): Boolean = getAltDown()
    override fun onEmulatorSet() {}
    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    override fun shouldEnforceCharBasedInput(): Boolean = true
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
    override fun isTerminalViewSelected(): Boolean = true
    override fun copyModeChanged(copyMode: Boolean) {}
    override fun readShiftKey(): Boolean = false
    override fun readFnKey(): Boolean = false
    override fun logError(tag: String, message: String) {}
    override fun logWarn(tag: String, message: String) {}
    override fun logInfo(tag: String, message: String) {}
    override fun logDebug(tag: String, message: String) {}
    override fun logVerbose(tag: String, message: String) {}
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {}
    override fun logStackTrace(tag: String, e: Exception) {}

    private companion object {
        const val DEFAULT_TEXT_SIZE_PX = 24
        const val MIN_TEXT_SIZE_PX = 12
        const val MAX_TEXT_SIZE_PX = 48
    }
}
