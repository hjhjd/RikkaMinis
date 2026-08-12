package com.openminis.app.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.MinisApp
import com.openminis.app.vcpinfo.VcpInfoCategory
import com.openminis.app.vcpinfo.VcpInfoConnectionState
import com.openminis.app.vcpinfo.VcpInfoMessage
import org.json.JSONObject

private enum class InfoFilter(val label: String) { ALL("全部"), RAG("RAG知识库"), CHAIN("元思考链"), CHAT("Agent会话"), MEMO("记忆检索"), DREAM("Agent梦境") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VcpInfoCenterScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as MinisApp
    val manager = app.vcpInfoConnectionManager
    val messages by manager.store.messages.collectAsState()
    val status by manager.status.collectAsState()
    var filter by remember { mutableStateOf(InfoFilter.ALL) }
    var expanded by remember { mutableStateOf<Set<String>>(emptySet()) }

    DisposableEffect(Unit) {
        manager.store.setObserving(true)
        onDispose { manager.store.setObserving(false) }
    }

    val visible = remember(messages, filter) {
        if (filter == InfoFilter.ALL) messages else messages.filter { it.category.name == filter.name }
    }
    val statusText = when (status.state) {
        VcpInfoConnectionState.CLOSED -> "未连接"
        VcpInfoConnectionState.CONNECTING -> "连接中"
        VcpInfoConnectionState.CONNECTED -> "已连接"
        VcpInfoConnectionState.ERROR -> status.reconnectDelaySeconds?.let { "异常 · ${it}秒后重试" } ?: "连接异常"
    }
    val statusColor = when (status.state) {
        VcpInfoConnectionState.CONNECTED -> Color(0xFF34C759)
        VcpInfoConnectionState.CONNECTING -> Color(0xFFFF9500)
        VcpInfoConnectionState.ERROR -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.outline
    }

    Scaffold(topBar = {
        TopAppBar(
            title = {
                Column {
                    Text("VCPInfo 消息中心", fontWeight = FontWeight.SemiBold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).background(statusColor, CircleShape))
                        Text("  $statusText · ${messages.size} 条", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            actions = {
                IconButton(onClick = manager::reconnectNow) { Icon(Icons.Outlined.Refresh, "重新连接") }
                IconButton(onClick = manager.store::clear) { Icon(Icons.Outlined.DeleteOutline, "清空") }
            },
        )
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // 灵视中心使用紧凑标签条，避免 Material AssistChip 的 48dp 触控壳
            // 把首屏纵向空间吃掉。整行仍可横向滚动，标签自身保持 28dp 高。
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                InfoFilter.entries.forEach { item ->
                    val selected = item == filter
                    Text(
                        item.label,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                                RoundedCornerShape(10.dp),
                            )
                            .clickable { filter = item }
                            .padding(horizontal = 9.dp, vertical = 5.dp),
                    )
                }
            }
            status.lastError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
            if (visible.isEmpty()) {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.Visibility, null, Modifier.size(42.dp), tint = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(12.dp)); Text("暂无认知广播消息", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("等待 VCPToolBox 推送检索、思考或记忆事件", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                }
            } else LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(visible, key = { it.id }) { message ->
                    VcpInfoCard(message, message.id in expanded, onToggle = {
                        expanded = if (message.id in expanded) expanded - message.id else expanded + message.id
                    }, onCopy = {
                        context.getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText("VCPInfo", prettyJson(message.rawJson)))
                    })
                }
                item { Spacer(Modifier.height(20.dp)) }
            }
        }
    }
}

@Composable
private fun VcpInfoCard(message: VcpInfoMessage, expanded: Boolean, onToggle: () -> Unit, onCopy: () -> Unit) {
    val accent = when (message.category) {
        VcpInfoCategory.RAG -> Color(0xFF007AFF); VcpInfoCategory.CHAIN -> Color(0xFFAF52DE)
        VcpInfoCategory.CHAT -> Color(0xFFFF9500); VcpInfoCategory.MEMO -> Color(0xFF34C759)
        VcpInfoCategory.DREAM -> Color(0xFFFF2D8A)
    }
    Row(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(7.dp))
            .clickable(onClick = onToggle),
    ) {
        Box(Modifier.size(width = 2.dp, height = if (expanded) 112.dp else 74.dp).background(accent))
        Column(Modifier.weight(1f).padding(horizontal = 9.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(message.title, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = accent,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(formatTime(message.timestamp), fontSize = 8.sp, fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(start = 6.dp))
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, Modifier.size(16.dp))
            }
            message.subtitle?.let {
                Text(it, fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = accent.copy(alpha = .85f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
            }
            Text(message.summary.ifBlank { "（无摘要）" }, fontSize = 10.sp, lineHeight = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = if (expanded) 4 else 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
            if (expanded && message.hasDetails) {
                Row(Modifier.fillMaxWidth().padding(top = 5.dp), horizontalArrangement = Arrangement.End) {
                    Text("复制", fontSize = 9.sp, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable(onClick = onCopy).padding(horizontal = 7.dp, vertical = 3.dp))
                }
                VcpInfoDetailContent(message)
            }
        }
    }
}

private fun prettyJson(raw: String) = runCatching { JSONObject(raw).toString(2) }.getOrDefault(raw)
private fun formatTime(raw: String): String = runCatching {
    java.time.OffsetDateTime.parse(raw).toLocalTime().withNano(0).toString()
}.getOrElse { raw.takeLast(8) }
