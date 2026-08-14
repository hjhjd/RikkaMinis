package com.openminis.app.ui.terminal

import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.openminis.app.sandbox.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

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
            TerminalView(context, null).apply {
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
        },
        update = { view ->
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
        onRelease = { view -> terminalSession.detachView(view) },
        modifier = modifier,
    )
}

private class MinisTerminalViewClient(
    private val view: TerminalView,
    private val getControlDown: () -> Boolean,
    private val getAltDown: () -> Boolean,
) : TerminalViewClient {
    override fun onSingleTapUp(e: MotionEvent) {
        view.requestFocus()
        val imm = view.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
            as? android.view.inputmethod.InputMethodManager
        imm?.showSoftInput(view, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }

    override fun onLongPress(event: MotionEvent): Boolean = false
    override fun onScale(scale: Float): Float = scale
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
}
