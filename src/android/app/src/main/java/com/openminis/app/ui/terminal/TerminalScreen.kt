package com.openminis.app.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.openminis.app.sandbox.TerminalSession
import com.openminis.app.terminal.MinisOpenUrlBroker

private data class ManagedTerminalSession(
    val id: Long,
    val session: TerminalSession,
    val title: String = "vcpminis",
)

@Composable
fun TerminalScreen(
    terminalSession: TerminalSession,
    onBack: () -> Unit,
    initCommand: String? = null,
    sessionId: String? = null,
    createSession: () -> TerminalSession,
) {
    val sessions = remember {
        mutableStateListOf(ManagedTerminalSession(1L, terminalSession))
    }
    var nextSessionId by remember { mutableLongStateOf(2L) }
    var activeSessionId by remember { mutableLongStateOf(1L) }
    val active = sessions.firstOrNull { it.id == activeSessionId } ?: sessions.first()
    val activeState by active.session.state.collectAsStateEffect()
    var ctrlDown by remember { mutableStateOf(false) }
    var altDown by remember { mutableStateOf(false) }
    var initialCommandSent by remember { mutableStateOf(false) }

    LaunchedEffect(active.id) {
        if (!active.session.isRunning) active.session.start(sessionId = sessionId)
        if (active.id == 1L && !initialCommandSent && !initCommand.isNullOrBlank()) {
            initialCommandSent = true
            kotlinx.coroutines.delay(500)
            active.session.sendText(initCommand)
        }
    }

    DisposableEffect(Unit) {
        onDispose { sessions.forEach { it.session.stop() } }
    }

    DisposableEffect(Unit) {
        MinisOpenUrlBroker.setTerminalVisible(true)
        onDispose { MinisOpenUrlBroker.setTerminalVisible(false) }
    }

    var previewUrl by remember { mutableStateOf<String?>(null) }
    val pendingUrl by MinisOpenUrlBroker.pendingUrl.collectAsStateEffect()
    LaunchedEffect(pendingUrl) {
        val uri = pendingUrl ?: return@LaunchedEffect
        if (MinisOpenUrlBroker.isWebScheme(uri.scheme)) previewUrl = uri.toString()
        MinisOpenUrlBroker.consume()
    }

    fun closeSession(id: Long) {
        val index = sessions.indexOfFirst { it.id == id }
        if (index < 0) return
        if (sessions.size == 1) {
            sessions[index].session.stop()
            onBack()
            return
        }
        sessions[index].session.stop()
        sessions.removeAt(index)
        if (activeSessionId == id) {
            activeSessionId = sessions[index.coerceAtMost(sessions.lastIndex)].id
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(TerminalColors.background)) {
        Column(
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).imePadding(),
        ) {
            TerminalHeader(
                onBack = { onBack() },
                onAdd = {
                    val id = nextSessionId++
                    sessions.add(ManagedTerminalSession(id, createSession()))
                    activeSessionId = id
                    ctrlDown = false
                    altDown = false
                },
            )
            TerminalTabStrip(
                tabs = sessions.map { TerminalTabUi(it.id, it.title) },
                activeTabId = activeSessionId,
                onSelect = {
                    activeSessionId = it
                    ctrlDown = false
                    altDown = false
                },
                onClose = ::closeSession,
            )
            key(active.id) {
                TerminalViewport(
                    terminalSession = active.session,
                    sessionState = activeState,
                    ctrlDown = ctrlDown,
                    altDown = altDown,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            }
            KeyboardAccessoryBar(
                ctrlDown = ctrlDown,
                altDown = altDown,
                onCtrlToggle = { ctrlDown = !ctrlDown },
                onAltToggle = { altDown = !altDown },
                onSendRaw = { bytes ->
                    active.session.sendRawBytes(bytes)
                    ctrlDown = false
                    altDown = false
                },
            )
        }

        previewUrl?.let { url ->
            com.openminis.app.ui.components.UrlPreviewSheet(url = url, onDismiss = { previewUrl = null })
        }
    }
}

@Composable
private fun <T> kotlinx.coroutines.flow.StateFlow<T>.collectAsStateEffect(): androidx.compose.runtime.State<T> {
    val state = remember(this) { androidx.compose.runtime.mutableStateOf(value) }
    LaunchedEffect(this) { collect { state.value = it } }
    return state
}
