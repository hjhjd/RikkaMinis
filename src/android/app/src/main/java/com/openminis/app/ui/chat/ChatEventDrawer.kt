package com.openminis.app.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.openminis.app.R
import com.openminis.app.vcplog.VcpLogConnectionState
import com.openminis.app.vcplog.VcpLogEvent
import com.openminis.app.vcplog.VcpLogEventCategory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 右侧紧凑事件工作台：VCPLog 为主流，VCPInfo 作为关联入口。 */
@Composable
internal fun ChatEventDrawer(
    visible: Boolean,
    onDismiss: () -> Unit,
    onOpenVcpInfo: () -> Unit,
) {
    val duration = 220
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(duration)),
        exit = fadeOut(tween(duration)),
        modifier = Modifier.fillMaxSize().zIndex(20f),
    ) {
        Box(
            Modifier.fillMaxSize()
                .background(Color.Black.copy(alpha = 0.34f))
                .clickable(onClick = onDismiss),
        )
    }
    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(tween(duration)) { it },
        exit = slideOutHorizontally(tween(duration)) { it },
        modifier = Modifier.fillMaxHeight().zIndex(21f),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
            val width = LocalConfiguration.current.screenWidthDp.dp * 0.88f
            Surface(
                modifier = Modifier.requiredWidth(width).fillMaxHeight(),
                shape = RoundedCornerShape(topStart = 18.dp, bottomStart = 18.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shadowElevation = 18.dp,
            ) {
                EventDrawerContent(onOpenVcpInfo)
            }
        }
    }
}

@Composable
private fun EventDrawerContent(onOpenVcpInfo: () -> Unit) {
    val app = LocalContext.current.applicationContext as com.openminis.app.MinisApp
    val manager = app.vcpLogConnectionManager
    val status by manager.status.collectAsState()
    val events by manager.store.events.collectAsState()
    var filter by remember { mutableStateOf(EventFilter.ALL) }
    var confirmClear by remember { mutableStateOf(false) }

    DisposableEffect(manager.store) {
        manager.store.beginObserving()
        onDispose { manager.store.endObserving() }
    }

    val config by app.distributedSettingsRepository.config.collectAsState()
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val shown = remember(events, filter) {
        when (filter) {
            EventFilter.ALL -> events
            EventFilter.TOOL -> events.filter { it.category == VcpLogEventCategory.TOOL }
            EventFilter.ERROR -> events.filter { it.category == VcpLogEventCategory.ERROR }
        }
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
    ) {
        EventHeader(
            state = status.state,
            reconnectDelaySeconds = status.reconnectDelaySeconds,
            eventCount = events.size,
            onReconnect = manager::reconnectNow,
            onClear = { confirmClear = true },
            onOpenVcpInfo = onOpenVcpInfo,
        )
        EventFilterBar(
            selected = filter,
            errorCount = events.count { it.category == VcpLogEventCategory.ERROR },
            onSelect = { filter = it },
        )
        if (shown.isEmpty()) {
            EmptyEventState(
                state = status.state,
                configured = config.enabled && config.vcpKey.isNotBlank(),
                filtered = events.isNotEmpty(),
                onReconnect = manager::reconnectNow,
            )
        } else {
            Box(Modifier.fillMaxSize()) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 10.dp,
                        end = 10.dp,
                        top = 7.dp,
                        bottom = 12.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(shown, key = { it.id }) { event ->
                        EventCard(event = event)
                    }
                }
                if (listState.firstVisibleItemIndex > 0) {
                    FilledTonalButton(
                        onClick = { coroutineScope.launch { listState.animateScrollToItem(0) } },
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
                    ) { Text(stringResource(R.string.vcp_log_jump_latest)) }
                }
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.vcp_log_clear_confirm_title)) },
            text = { Text(stringResource(R.string.vcp_log_clear_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    manager.store.clear()
                    confirmClear = false
                }) { Text(stringResource(R.string.vcp_log_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun EventHeader(
    state: VcpLogConnectionState,
    reconnectDelaySeconds: Long?,
    eventCount: Int,
    onReconnect: () -> Unit,
    onClear: () -> Unit,
    onOpenVcpInfo: () -> Unit,
) {
    val statusText = when (state) {
        VcpLogConnectionState.CLOSED -> stringResource(R.string.vcp_log_state_closed)
        VcpLogConnectionState.CONNECTING -> stringResource(R.string.vcp_log_state_connecting)
        VcpLogConnectionState.CONNECTED -> stringResource(R.string.vcp_log_state_connected)
        VcpLogConnectionState.ERROR -> reconnectDelaySeconds?.let {
            stringResource(R.string.vcp_log_state_retrying, it)
        } ?: stringResource(R.string.vcp_log_state_error)
    }
    var menuExpanded by remember { mutableStateOf(false) }
    val statusColor = when (state) {
        VcpLogConnectionState.CONNECTED -> SuccessGreen
        VcpLogConnectionState.CONNECTING -> ToolBlue
        VcpLogConnectionState.ERROR -> ErrorRed
        VcpLogConnectionState.CLOSED -> MaterialTheme.colorScheme.outline
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.chat_event_drawer_title),
                    fontSize = 17.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
                Surface(
                    modifier = Modifier.padding(start = 7.dp),
                    shape = RoundedCornerShape(5.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                ) {
                    Text(
                        eventCount.toString(),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 9.sp,
                        lineHeight = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Row(
                modifier = Modifier.padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(6.dp).background(
                        statusColor,
                        CircleShape,
                    ),
                )
                Text(
                    statusText,
                    modifier = Modifier.padding(start = 5.dp).clickable(
                        enabled = state == VcpLogConnectionState.ERROR || state == VcpLogConnectionState.CLOSED,
                        onClick = onReconnect,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                    lineHeight = 12.sp,
                )
            }
        }
        Box {
            IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.vcp_log_more_actions))
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.vcp_log_reconnect)) },
                    onClick = { menuExpanded = false; onReconnect() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.vcp_log_clear)) },
                    enabled = eventCount > 0,
                    onClick = { menuExpanded = false; onClear() },
                )
            }
        }
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp,
            modifier = Modifier.clickable(onClick = onOpenVcpInfo),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.Visibility,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    stringResource(R.string.chat_event_drawer_vcpinfo),
                    modifier = Modifier.padding(start = 5.dp),
                    fontSize = 10.sp,
                    lineHeight = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun EventFilterBar(
    selected: EventFilter,
    errorCount: Int,
    onSelect: (EventFilter) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        EventFilter.entries.forEach { item ->
            val active = selected == item
            val label = when (item) {
                EventFilter.ALL -> stringResource(R.string.vcp_log_filter_all)
                EventFilter.TOOL -> stringResource(R.string.vcp_log_filter_tools)
                EventFilter.ERROR -> stringResource(R.string.vcp_log_filter_errors) + if (errorCount > 0) " $errorCount" else ""
            }
            Surface(
                modifier = Modifier.clickable { onSelect(item) },
                shape = RoundedCornerShape(7.dp),
                color = if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                else Color.Transparent,
            ) {
                Text(
                    label,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                    color = if (active) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                    lineHeight = 12.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun EventCard(event: VcpLogEvent) {
    val visual = event.visualStyle()
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var copied by remember(event.id) { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Box(
            Modifier.fillMaxWidth().drawBehind {
                val width = 5.dp.toPx()
                drawLine(
                    color = visual.color,
                    start = Offset(width / 2f, 0f),
                    end = Offset(width / 2f, size.height),
                    strokeWidth = width,
                )
            },
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(5.dp),
                        color = visual.color.copy(alpha = 0.11f),
                    ) {
                        Text(
                            visual.label,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                            color = visual.color,
                            fontSize = 8.sp,
                            lineHeight = 10.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Text(
                        event.displayTitle,
                        modifier = Modifier.weight(1f).padding(start = 6.dp),
                        fontSize = 11.sp,
                        lineHeight = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        formatEventTime(event.eventAtMs ?: event.receivedAtMs),
                        color = MaterialTheme.colorScheme.outline,
                        fontSize = 8.sp,
                        lineHeight = 10.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(top = 7.dp),
                    shape = RoundedCornerShape(7.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Box(Modifier.fillMaxWidth()) {
                        Text(
                            event.content,
                            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 36.dp, top = 7.dp, bottom = 7.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.5.sp,
                            lineHeight = 14.sp,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                        IconButton(
                            onClick = {
                                clipboard.setText(AnnotatedString(event.rawJson))
                                copied = true
                                scope.launch { delay(1_500); copied = false }
                            },
                            modifier = Modifier.align(Alignment.TopEnd).size(32.dp),
                        ) {
                            Icon(
                                imageVector = if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                                contentDescription = stringResource(R.string.vcp_log_copy_json),
                                modifier = Modifier.size(14.dp),
                                tint = if (copied) SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyEventState(
    state: VcpLogConnectionState,
    configured: Boolean,
    filtered: Boolean,
    onReconnect: () -> Unit,
) {
    val message = when {
        filtered -> stringResource(R.string.vcp_log_empty_filtered)
        !configured -> stringResource(R.string.vcp_log_empty_not_configured)
        state == VcpLogConnectionState.CONNECTING -> stringResource(R.string.vcp_log_empty_connecting)
        state == VcpLogConnectionState.ERROR -> stringResource(R.string.vcp_log_empty_error)
        state == VcpLogConnectionState.CONNECTED -> stringResource(R.string.vcp_log_empty_waiting)
        else -> stringResource(R.string.vcp_log_empty)
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Icon(
                if (state == VcpLogConnectionState.CONNECTED) Icons.Outlined.NotificationsNone else Icons.Outlined.Info,
                contentDescription = null,
                modifier = Modifier.padding(12.dp).size(24.dp),
                tint = MaterialTheme.colorScheme.outline,
            )
        }
        Text(
            message,
            modifier = Modifier.padding(top = 10.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
        )
        if (configured && (state == VcpLogConnectionState.ERROR || state == VcpLogConnectionState.CLOSED)) {
            TextButton(onClick = onReconnect) { Text(stringResource(R.string.vcp_log_reconnect)) }
        }
    }
}

private enum class EventFilter { ALL, TOOL, ERROR }
private data class EventVisual(val label: String, val color: Color)

@Composable
private fun VcpLogEvent.visualStyle(): EventVisual = when (category) {
    VcpLogEventCategory.ERROR -> EventVisual(stringResource(R.string.vcp_log_category_error), ErrorRed)
    VcpLogEventCategory.SUCCESS -> EventVisual(stringResource(R.string.vcp_log_category_success), SuccessGreen)
    VcpLogEventCategory.TOOL -> EventVisual(stringResource(R.string.vcp_log_category_tool), ToolBlue)
    VcpLogEventCategory.STATUS -> EventVisual(stringResource(R.string.vcp_log_category_status), InfoPurple)
    VcpLogEventCategory.RAW -> EventVisual(stringResource(R.string.vcp_log_category_raw), RawGray)
    VcpLogEventCategory.INFO -> EventVisual(stringResource(R.string.vcp_log_category_info), InfoPurple)
}

private fun formatEventTime(timeMs: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timeMs))

private val SuccessGreen = Color(0xFF2E9B62)
private val ErrorRed = Color(0xFFD9504F)
private val ToolBlue = Color(0xFF4678C8)
private val InfoPurple = Color(0xFF8064B4)
private val RawGray = Color(0xFF77818C)
