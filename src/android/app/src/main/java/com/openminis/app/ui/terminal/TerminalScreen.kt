package com.openminis.app.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openminis.app.sandbox.TerminalSession
import com.openminis.app.terminal.MinisOpenUrlBroker

/**
 * Full-screen terminal — now backed by [Termux TerminalView] instead of the
 * hand-rolled emulator. Ctrl and Alt are persistent toggle states that inject
 * into the [TerminalViewClient] so pressing e.g. Ctrl then 'c' sends 0x03
 * exactly like a physical keyboard.
 */
@Composable
fun TerminalScreen(
    terminalSession: TerminalSession,
    onBack: () -> Unit,
    initCommand: String? = null,
    sessionId: String? = null,
) {
    // ── Ctrl / Alt persistent toggles ──────────────────────────────────────
    var ctrlDown by remember { mutableStateOf(false) }
    var altDown by remember { mutableStateOf(false) }

    // Track terminal session state so Compose re-executes the
    // AndroidView.update block when the Termux PTY finishes booting.
    val sessionState by terminalSession.state.collectAsStateEffect()

    // ── Lifecycle ──────────────────────────────────────────────────────────
    LaunchedEffect(Unit) {
        if (!terminalSession.isRunning) terminalSession.start(sessionId = sessionId)
        if (!initCommand.isNullOrBlank()) {
            kotlinx.coroutines.delay(500)
            terminalSession.sendText(initCommand)
        }
    }

    DisposableEffect(Unit) {
        onDispose { terminalSession.stop() }
    }

    // Claim the broker while the fullscreen terminal is up so ChatScreen
    // (still composed underneath this destination's stack) doesn't try to
    // present its own preview sheet on top — mirrors iOS ISHTerminalView.
    DisposableEffect(Unit) {
        MinisOpenUrlBroker.setTerminalVisible(true)
        onDispose { MinisOpenUrlBroker.setTerminalVisible(false) }
    }

    // ── OSC 1337 MinisOpenURL ──────────────────────────────────────────────
    var previewUrl by remember { mutableStateOf<String?>(null) }
    val pendingUrl by MinisOpenUrlBroker.pendingUrl.collectAsStateEffect()
    LaunchedEffect(pendingUrl) {
        val uri = pendingUrl ?: return@LaunchedEffect
        if (MinisOpenUrlBroker.isWebScheme(uri.scheme)) {
            previewUrl = uri.toString()
        }
        MinisOpenUrlBroker.consume()
    }

    val accessoryBarHeightDp = 40.dp

    Box(modifier = Modifier.fillMaxSize().background(TerminalColors.background)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .imePadding()
                .padding(bottom = accessoryBarHeightDp),
        ) {
            Spacer(modifier = Modifier.height(52.dp))
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                TerminalViewport(
                    terminalSession = terminalSession,
                    sessionState = sessionState,
                    ctrlDown = ctrlDown,
                    altDown = altDown,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // ── Top bar ────────────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopStart)
                .windowInsetsPadding(WindowInsets.systemBars)
                .height(52.dp)
                .background(TerminalColors.background),
        ) {
            TerminalTopBar(
                onClose = {
                    terminalSession.stop()
                    onBack()
                },
                onClear = {
                    // Kill any half-typed line + full terminal reset.
                    terminalSession.sendRawBytes(byteArrayOf(0x15)) // Ctrl+U
                    terminalSession.clearOutput()
                },
            )
        }

        // ── Keyboard accessory bar ─────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .imePadding()
                .windowInsetsPadding(WindowInsets.navigationBars),
        ) {
            KeyboardAccessoryBar(
                ctrlDown = ctrlDown,
                altDown = altDown,
                onCtrlToggle = { ctrlDown = !ctrlDown },
                onAltToggle = { altDown = !altDown },
                onSendRaw = { bytes ->
                    terminalSession.sendRawBytes(bytes)
                    // Auto-release toggles after use so the next key isn't
                    // accidentally modified.
                    ctrlDown = false
                    altDown = false
                },
            )
        }

        previewUrl?.let { url ->
            com.openminis.app.ui.components.UrlPreviewSheet(
                url = url,
                onDismiss = { previewUrl = null },
            )
        }
    }
}


@Composable
private fun <T> kotlinx.coroutines.flow.StateFlow<T>.collectAsStateEffect(): androidx.compose.runtime.State<T> {
    val state = remember { androidx.compose.runtime.mutableStateOf(value) }
    LaunchedEffect(this) { collect { state.value = it } }
    return state
}
