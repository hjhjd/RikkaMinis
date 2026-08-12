package com.openminis.app.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.openminis.app.R
import com.openminis.app.agent.AgentAvatarStore
import com.openminis.app.data.db.AgentEntity
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.service.SessionActivityTracker
import com.openminis.app.ui.sessions.DatePeriod
import com.openminis.app.ui.sessions.categoryStyle

@Composable
internal fun DrawerSectionHeader(period: DatePeriod) {
    val title = when (period) {
        DatePeriod.PINNED -> stringResource(R.string.sessionlist_section_pinned)
        DatePeriod.TODAY -> stringResource(R.string.sessionlist_section_today)
        DatePeriod.YESTERDAY -> stringResource(R.string.sessionlist_section_yesterday)
        DatePeriod.THIS_WEEK -> stringResource(R.string.sessionlist_section_this_week)
        DatePeriod.THIS_MONTH -> stringResource(R.string.sessionlist_section_this_month)
        DatePeriod.EARLIER -> stringResource(R.string.sessionlist_section_earlier)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .padding(top = 4.dp),
    ) {
        if (period == DatePeriod.PINNED) {
            Icon(
                imageVector = Icons.Default.PushPin,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(13.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
        }
        Text(
            text = title,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DrawerSessionRow(
    session: ChatSessionEntity,
    agent: AgentEntity?,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    isPinned: Boolean,
    onTogglePin: () -> Unit,
) {
    val style = remember(session.category) { categoryStyle(session.category) }
    val context = LocalContext.current
    val avatarStore = remember { AgentAvatarStore(context.applicationContext) }
    val agentAvatar = remember(agent?.avatarPath) { avatarStore.resolve(agent?.avatarPath) }
    val activeSessions by SessionActivityTracker.activeSessions.collectAsState()
    val isActive = session.id in activeSessions

    Row(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 3.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.surfaceContainerHigh
                else MaterialTheme.colorScheme.surface,
            )
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .background(style.color.copy(alpha = 0.18f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (agentAvatar != null) {
                AsyncImage(
                    agentAvatar,
                    contentDescription = agent?.name,
                    modifier = Modifier.size(34.dp).clip(CircleShape),
                )
            } else {
                Icon(
                    imageVector = style.icon,
                    contentDescription = null,
                    tint = style.color,
                    modifier = Modifier.size(17.dp),
                )
            }
            if (isActive) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .background(style.color, CircleShape)
                        .align(Alignment.BottomEnd),
                )
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = session.title ?: stringResource(R.string.chat_menu_new_chat),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            session.lastMessage?.takeIf { it.isNotBlank() }?.let { preview ->
                Text(
                    text = preview,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        IconButton(onClick = onTogglePin, modifier = Modifier.size(28.dp)) {
            Icon(
                imageVector = if (isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                contentDescription = stringResource(
                    if (isPinned) R.string.sessionlist_unpin else R.string.sessionlist_pin,
                ),
                tint = if (isPinned) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
internal fun DrawerAgentRow(
    agent: AgentEntity,
    selected: Boolean,
    onClick: () -> Unit,
    onSettings: () -> Unit,
) {
    val context = LocalContext.current
    val avatar = remember(agent.avatarPath) { AgentAvatarStore(context).resolve(agent.avatarPath) }
    Row(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.surfaceContainerHigh
                else MaterialTheme.colorScheme.surface,
            )
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (avatar != null) {
                AsyncImage(avatar, contentDescription = agent.name, modifier = Modifier.size(48.dp))
            } else {
                Icon(Icons.Outlined.Person, contentDescription = null, modifier = Modifier.size(28.dp))
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                agent.name,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(
                    if (agent.defaultModelBinding.isNullOrBlank()) R.string.chat_drawer_global_default_model
                    else R.string.chat_drawer_custom_default_model,
                ),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onSettings) {
            Icon(
                Icons.Outlined.Settings,
                contentDescription = stringResource(R.string.chat_drawer_agent_settings, agent.name),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
