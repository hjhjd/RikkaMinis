package com.openminis.app.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.KeyboardTab
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Eject
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R

/** Centralized terminal palette, ready for the upcoming visual redesign. */
internal object TerminalColors {
    val background = Color(0xFF000000)
    val foreground = Color(0xFFD4D4D4)
    val accent = Color(0xFF34C759)
    val accessoryBackground = Color(0xFF1F1F1F)
    val buttonBackground = Color(0xFF404040)
    val buttonActive = Color(0xFF007AFF)
    val topButtonBackground = Color(0xFF2C2C2E)
}

@Composable
internal fun TerminalTopBar(onClose: () -> Unit, onClear: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().background(TerminalColors.background)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularIconButton(Icons.Default.Close, stringResource(R.string.common_close), TerminalColors.foreground, onClose)
        Spacer(modifier = Modifier.weight(1f))
        Text(
            stringResource(R.string.terminal_title),
            color = TerminalColors.foreground,
            style = TextStyle(fontFamily = JetBrainsMonoFontFamily, fontSize = 16.sp),
        )
        Spacer(modifier = Modifier.weight(1f))
        CircularIconButton(Icons.Default.Brush, stringResource(R.string.terminal_clear), TerminalColors.accent, onClear)
    }
}

@Composable
private fun CircularIconButton(icon: ImageVector, contentDescription: String, tint: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(36.dp).clip(CircleShape)
            .background(TerminalColors.topButtonBackground).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(18.dp))
    }
}

@Composable
internal fun KeyboardAccessoryBar(
    ctrlDown: Boolean,
    altDown: Boolean,
    onCtrlToggle: () -> Unit,
    onAltToggle: () -> Unit,
    onSendRaw: (ByteArray) -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp)).background(TerminalColors.accessoryBackground),
    ) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            QuickCommandButton("Esc", iconText = "⎋") { onSendRaw(byteArrayOf(0x1B)) }
            QuickCommandButton("Tab", icon = Icons.AutoMirrored.Filled.KeyboardTab) { onSendRaw(byteArrayOf(0x09)) }
            QuickCommandButton("⏎", iconText = "⏎") { onSendRaw(byteArrayOf(0x0D)) }
            QuickCommandButton("Ctrl", iconText = "^", isActive = ctrlDown, onClick = onCtrlToggle)
            QuickCommandButton("Alt", iconText = "⌥", isActive = altDown, onClick = onAltToggle)
            QuickCommandButton("↑", icon = Icons.Default.KeyboardArrowUp) { onSendRaw(byteArrayOf(0x1B, 0x5B, 0x41)) }
            QuickCommandButton("↓", icon = Icons.Default.KeyboardArrowDown) { onSendRaw(byteArrayOf(0x1B, 0x5B, 0x42)) }
            QuickCommandButton("←", icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft) { onSendRaw(byteArrayOf(0x1B, 0x5B, 0x44)) }
            QuickCommandButton("→", icon = Icons.AutoMirrored.Filled.KeyboardArrowRight) { onSendRaw(byteArrayOf(0x1B, 0x5B, 0x43)) }
            QuickCommandButton("C-c", icon = Icons.Outlined.Cancel) { onSendRaw(byteArrayOf(0x03)) }
            QuickCommandButton("C-d", icon = Icons.Default.Eject) { onSendRaw(byteArrayOf(0x04)) }
            QuickCommandButton("C-z", icon = Icons.Outlined.PauseCircle) { onSendRaw(byteArrayOf(0x1A)) }
        }
    }
}

@Composable
private fun QuickCommandButton(
    label: String,
    icon: ImageVector? = null,
    iconText: String? = null,
    isActive: Boolean = false,
    onClick: () -> Unit,
) {
    val background = if (isActive) TerminalColors.buttonActive else TerminalColors.buttonBackground
    val foreground = if (isActive) Color.White else TerminalColors.accent
    Row(
        modifier = Modifier.height(28.dp).clip(RoundedCornerShape(6.dp))
            .background(background).clickable(onClick = onClick).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        when {
            icon != null -> Icon(icon, null, tint = foreground, modifier = Modifier.size(12.dp))
            iconText != null -> Text(iconText, color = foreground, style = terminalButtonTextStyle())
        }
        Text(label, color = foreground, style = terminalButtonTextStyle(), maxLines = 1)
    }
}

private fun terminalButtonTextStyle() = TextStyle(fontFamily = JetBrainsMonoFontFamily, fontSize = 11.sp)
