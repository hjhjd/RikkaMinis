package com.openminis.app.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal object TerminalColors {
    val background = Color.Black
    val foreground = Color(0xFFE6E2EA)
    val muted = Color(0xFFD9D3DE)
    val accent = Color(0xFFD7C2FF)
    val divider = Color(0xFF353238)
}

internal val TerminalHeaderHeight = 64.dp
internal val TerminalTabHeight = 52.dp
internal val TerminalAccessoryHeight = 112.dp

@Composable
internal fun TerminalHeader(onBack: () -> Unit, onAdd: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(TerminalHeaderHeight)
            .background(TerminalColors.background).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "返回",
            tint = TerminalColors.foreground,
            modifier = Modifier.clickable(onClick = onBack).padding(4.dp).size(32.dp),
        )
        Text(
            text = "VCPMinis-Debian",
            color = TerminalColors.foreground,
            style = TextStyle(fontSize = 23.sp, fontWeight = FontWeight.Medium),
            modifier = Modifier.weight(1f).padding(start = 18.dp),
        )
        Icon(
            Icons.Default.Add,
            contentDescription = "新建终端",
            tint = TerminalColors.foreground,
            modifier = Modifier.clickable(onClick = onAdd).padding(4.dp).size(32.dp),
        )
    }
}

@Composable
internal fun TerminalTab(onClose: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().height(TerminalTabHeight).background(TerminalColors.background)) {
        Row(
            modifier = Modifier.weight(1f).padding(start = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "nova@vcpminis",
                color = TerminalColors.accent,
                style = TextStyle(fontFamily = JetBrainsMonoFontFamily, fontSize = 17.sp, fontWeight = FontWeight.Bold),
            )
            Icon(
                Icons.Default.Close,
                contentDescription = "关闭终端",
                tint = TerminalColors.accent,
                modifier = Modifier.clickable(onClick = onClose).padding(10.dp).size(20.dp),
            )
        }
        Row(modifier = Modifier.fillMaxWidth().height(2.dp)) {
            Box(modifier = Modifier.weight(0.35f).fillMaxSize().background(TerminalColors.accent))
            Box(modifier = Modifier.weight(0.65f).fillMaxSize().background(TerminalColors.divider))
        }
    }
}

private data class TerminalKey(val label: String, val bytes: ByteArray? = null, val modifier: ModifierKey? = null)
private enum class ModifierKey { CTRL, ALT }

@Composable
internal fun KeyboardAccessoryBar(
    ctrlDown: Boolean,
    altDown: Boolean,
    onCtrlToggle: () -> Unit,
    onAltToggle: () -> Unit,
    onSendRaw: (ByteArray) -> Unit,
) {
    val rows = listOf(
        listOf(
            TerminalKey("ESC", byteArrayOf(0x1B)), TerminalKey("/", byteArrayOf('/'.code.toByte())),
            TerminalKey("−", byteArrayOf('-'.code.toByte())), TerminalKey("HOME", byteArrayOf(0x1B, 0x5B, 0x48)),
            TerminalKey("↑", byteArrayOf(0x1B, 0x5B, 0x41)), TerminalKey("END", byteArrayOf(0x1B, 0x5B, 0x46)),
            TerminalKey("PGUP", byteArrayOf(0x1B, 0x5B, 0x35, 0x7E)),
        ),
        listOf(
            TerminalKey("↹", byteArrayOf(0x09)), TerminalKey("CTRL", modifier = ModifierKey.CTRL),
            TerminalKey("ALT", modifier = ModifierKey.ALT), TerminalKey("←", byteArrayOf(0x1B, 0x5B, 0x44)),
            TerminalKey("↓", byteArrayOf(0x1B, 0x5B, 0x42)), TerminalKey("→", byteArrayOf(0x1B, 0x5B, 0x43)),
            TerminalKey("PGDN", byteArrayOf(0x1B, 0x5B, 0x36, 0x7E)),
        ),
    )
    Column(
        modifier = Modifier.fillMaxWidth().height(TerminalAccessoryHeight).background(TerminalColors.background)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {
        rows.forEach { keys ->
            Row(modifier = Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
                keys.forEach { key ->
                    val active = key.modifier == ModifierKey.CTRL && ctrlDown || key.modifier == ModifierKey.ALT && altDown
                    Box(
                        modifier = Modifier.weight(1f).fillMaxSize().clickable {
                            when (key.modifier) {
                                ModifierKey.CTRL -> onCtrlToggle()
                                ModifierKey.ALT -> onAltToggle()
                                null -> key.bytes?.let(onSendRaw)
                            }
                        },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            key.label,
                            color = if (active) TerminalColors.accent else TerminalColors.muted,
                            style = TextStyle(fontSize = if (key.label in setOf("↑", "↓", "←", "→", "↹")) 24.sp else 16.sp),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}
