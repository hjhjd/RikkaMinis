package com.openminis.app.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.config.ChatActionSpec
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.db.AgentEntity
import com.openminis.app.data.repository.AgentRepository
import com.openminis.app.agent.AgentAvatarStore
import coil.compose.AsyncImage
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.service.SessionActivityTracker
import com.openminis.app.ui.components.MinisAlertDialog
import com.openminis.app.ui.sessions.DatePeriod
import com.openminis.app.ui.sessions.categoryStyle
import com.openminis.app.ui.sessions.groupSessionsByDate

/**
 * RikkaHub-style chat-history drawer that slides out from the left of the
 * chat screen. Mirrors [com.openminis.app.ui.sessions.SessionListScreen] but
 * in a slimmer, always-available form so the user can switch conversations
 * without leaving the current chat. This drawer is the primary conversation-
 * history entry from the chat screen.
 *
 * Data comes straight off [ChatRepository.observeSessions] — the same Room
 * flow the full list uses — so pins, deletions, titles and last-message
 * previews stay live and consistent with the standalone list. Section
 * grouping, category icons and relative timestamps reuse the (now `internal`)
 * helpers exported by SessionListScreen so there is a single source of truth.
 *
 * @param currentSessionId the chat currently displayed, highlighted in the list.
 * @param draft the persisted unsent-draft snapshot (id + text) to surface as a
 *        "Draft" row; null hides the row. The row is hidden while the user is
 *        already inside that draft.
 * @param onOpenDraft resume the draft session (caller closes the drawer).
 * @param onDiscardDraft drop the persisted draft (user confirms in the dialog).
 * @param onSessionClick open another conversation (caller closes the drawer).
 * @param onDeleteSession permanently delete a conversation and release its
 *        process-level resources. The caller owns the asynchronous work.
 * @param onNewChat start a fresh draft chat — used only as the fallback when
 *        the user deletes the chat they are currently viewing from the drawer
 *        (no visible button: creating a chat lives in the "..." menu and the
 *        session list).
 * @param footerActions the resolved, availability-filtered list of actions to
 *        render in the bottom bar. Empty list hides the footer entirely
 *        (divider + bar). Each spec carries the key, icon and title.
 * @param onAction single dispatcher for footer action taps — the caller
 *        resolves the key into the actual side effect (open sheet, navigate,
 *        toggle state).
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun ChatHistoryDrawer(
    chatRepository: ChatRepository,
    agentRepository: AgentRepository,
    currentSessionId: String,
    currentAgentId: String,
    onAgentClick: (String) -> Unit = {},
    onAgentSettings: (String) -> Unit = {},
    onCreateAgent: () -> Unit = {},
    draft: com.openminis.app.data.ComposerDraftStore.DraftSnapshot? = null,
    onOpenDraft: () -> Unit = {},
    onDiscardDraft: () -> Unit = {},
    onSessionClick: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onNewChat: () -> Unit,
    footerActions: List<ChatActionSpec> = emptyList(),
    onAction: (String) -> Unit = {},
    onPinSession: (String) -> Unit = {},
) {
    val sessions by chatRepository.observeSessions()
        .collectAsState(initial = emptyList())
    val agents by agentRepository.observeAll().collectAsState(initial = emptyList())
    var drawerTab by rememberSaveable { mutableStateOf(0) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val filteredAgents = remember(agents, searchQuery) {
        val q = searchQuery.trim()
        if (q.isEmpty()) agents else agents.filter { it.name.contains(q, ignoreCase = true) }
    }

    // [P0-1-drawer-title-visibility] Visibility must not depend on the
    // auto-generated title: a session that has real messages has to be
    // findable even while its title is still pending (or after title
    // generation failed), and message-less draft rows (the current unsent
    // chat, ghost rows from /memory or /thinking toggles) stay hidden.
    val messageCounts by chatRepository.observeMessageCountsPerSession()
        .collectAsState(initial = emptyMap())
    val agentById = remember(agents) { agents.associateBy { it.id } }
    val visibleSessions = remember(sessions, messageCounts, searchQuery, drawerTab, currentAgentId) {
        // The topic tab is scoped to the selected Agent. Agent identity is no
        // longer part of topic search because every visible row has one owner.
        val base = sessions.filter { session ->
            session.agentId == currentAgentId &&
                (session.pinnedAt != null || (messageCounts[session.id] ?: 0) > 0)
        }
        val q = if (drawerTab == 1) searchQuery.trim() else ""
        if (q.isEmpty()) base else base.filter { session ->
            session.title.orEmpty().contains(q, ignoreCase = true) ||
                session.lastMessage.orEmpty().contains(q, ignoreCase = true)
        }
    }
    val grouped = remember(visibleSessions) { groupSessionsByDate(visibleSessions) }

    var deleteTarget by remember { mutableStateOf<ChatSessionEntity?>(null) }
    val drawerWidth = LocalConfiguration.current.screenWidthDp.dp * 0.8f

    ModalDrawerSheet(
        // ModalDrawerSheet is measured in the drawer content slot, where a
        // fractional fill can resolve against the sheet's own default maximum
        // instead of the window. Derive the width explicitly from the screen
        // so the drawer consistently occupies 80% on every phone.
        modifier = Modifier.requiredWidth(drawerWidth),
        drawerContainerColor = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header: bare app title — all actions live at the bottom.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.app_name),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }

            // Compact pill tabs and a filled search field mirror the reference
            // drawer: controls sit on the neutral canvas instead of looking like
            // two primary call-to-action buttons.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                listOf(
                    stringResource(R.string.chat_drawer_tab_agents),
                    stringResource(R.string.chat_drawer_tab_topics),
                ).forEachIndexed { index, label ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(9.dp))
                            .background(if (drawerTab == index) MaterialTheme.colorScheme.surface else androidx.compose.ui.graphics.Color.Transparent)
                            .clickable { drawerTab = index; searchQuery = "" }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label, fontSize = 14.sp, fontWeight = if (drawerTab == index) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (drawerTab == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = {
                    Text(
                        stringResource(
                            if (drawerTab == 0) R.string.chat_drawer_search_agents
                            else R.string.chat_drawer_search_topics,
                        ),
                    )
                },
                leadingIcon = { Icon(Icons.Outlined.Search, null, Modifier.size(19.dp)) },
                singleLine = true,
                shape = RoundedCornerShape(13.dp),
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )

            if (drawerTab == 1) {
            // [composer-draft-v1] Persistent unsent-draft entry. Click resumes
            // the draft session; long-press discards it. Shown above the
            // session list, even when there are no sessions yet.
            var discardDraft by remember { mutableStateOf(false) }
            draft?.let { d ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = onOpenDraft,
                            onLongClick = { discardDraft = true },
                        )
                        .padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = d.text.trim(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = stringResource(R.string.draft_label),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 20.dp),
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
            if (discardDraft) {
                MinisAlertDialog(
                    onDismissRequest = { discardDraft = false },
                    title = stringResource(R.string.draft_label),
                    confirmText = stringResource(R.string.delete),
                    onConfirm = {
                        onDiscardDraft()
                        discardDraft = false
                    },
                    text = stringResource(R.string.draft_discard_confirm),
                    isDestructive = true,
                )
            }

            if (visibleSessions.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.no_sessions),
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                // weight(1f) is required: a bare LazyColumn inside the Column
                // would measure against the sheet's full maxHeight and push
                // the footer off-screen.
                LazyColumn(modifier = Modifier.weight(1f)) {
                    grouped.forEach { (period, group) ->
                        item(key = "header-${period.name}") {
                            DrawerSectionHeader(period)
                        }
                        items(group, key = { it.id }) { session ->
                            DrawerSessionRow(
                                session = session,
                                agent = agentById[session.agentId],
                                selected = session.id == currentSessionId,
                                onClick = { onSessionClick(session.id) },
                                onLongClick = { deleteTarget = session },
                                isPinned = session.pinnedAt != null,
                                onTogglePin = { onPinSession(session.id) },
                            )
                        }
                    }
                    item { Spacer(modifier = Modifier.height(8.dp)) }
                }
            }
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(filteredAgents, key = { it.id }) { agent ->
                        DrawerAgentRow(
                            agent = agent,
                            selected = agent.id == currentAgentId,
                            onClick = { onAgentClick(agent.id) },
                            onSettings = { onAgentSettings(agent.id) },
                        )
                    }
                }
                OutlinedButton(
                    onClick = onCreateAgent,
                    shape = RoundedCornerShape(14.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)),
                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).height(50.dp),
                ) {
                    Icon(Icons.Outlined.Add, null, Modifier.size(19.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.chat_drawer_create_agent), fontWeight = FontWeight.SemiBold)
                }
            }

            // Footer: a configurable action bar rendered from the resolved pin
            // order, filtered by availability (Skills / MCPs / Memory only show
            // when their backing repository is present). FlowRow right-aligned
            // lets the icons wrap naturally when the user pins many actions,
            // while keeping the standard IconButton touch target. Empty list
            // (nothing pinned) hides the footer entirely — divider + bar both
            // gone — so the history list fills the drawer.
            if (footerActions.isNotEmpty()) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalArrangement = Arrangement.Center,
                ) {
                    footerActions.forEach { spec ->
                        IconButton(onClick = { onAction(spec.key) }) {
                            Icon(
                                imageVector = spec.icon,
                                contentDescription = stringResource(spec.titleRes),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    deleteTarget?.let { target ->
        MinisAlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = stringResource(R.string.sessionlist_delete_one_title),
            text = stringResource(R.string.sessionlist_delete_message),
            confirmText = stringResource(R.string.delete),
            isDestructive = true,
            onConfirm = {
                val id = target.id
                deleteTarget = null
                // The caller launches this in the application scope so deletion
                // survives the current-chat navigation that may immediately follow.
                onDeleteSession(id)
                // If the user just deleted the chat they're viewing, drop back
                // to a fresh draft so the screen isn't showing a dead session.
                if (id == currentSessionId) onNewChat()
            },
        )
    }
}

@Composable
private fun DrawerSectionHeader(period: DatePeriod) {
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
                // [T-dark-pin-visibility] onSurfaceVariant at 13dp reads as
                // near-invisible on the near-black ModalDrawerSheet surface
                // (#0E1514 dark background vs #BEC9C6 variant grey). Use the
                // theme primary (teal in both palettes) so the pinned-section
                // indicator keeps clear contrast in dark mode while staying
                // legible in light mode.
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
private fun DrawerSessionRow(
    session: ChatSessionEntity,
    agent: AgentEntity?,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    isPinned: Boolean,
    onTogglePin: () -> Unit,
) {
    val style = remember(session.category) { categoryStyle(session.category) }
    val ctx = LocalContext.current
    val avatarStore = remember { AgentAvatarStore(ctx.applicationContext) }
    val agentAvatar = remember(agent?.avatarPath) { avatarStore.resolve(agent?.avatarPath) }
    val activeSessions by SessionActivityTracker.activeSessions.collectAsState()
    val isActive = session.id in activeSessions

    Row(
        modifier = Modifier
            // Outer padding comes before sizing so the visible card bounds are
            // guaranteed to align with the search field at 16dp on both sides.
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
                .background(color = style.color.copy(alpha = 0.18f), shape = CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (agentAvatar != null) {
                AsyncImage(agentAvatar, contentDescription = agent?.name, modifier = Modifier.size(34.dp).clip(CircleShape))
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

        // Pin toggle — inline icon, same style as the provider's favorite
        // star. Placed at the right edge of the row, after the timestamp.
        IconButton(
            onClick = onTogglePin,
            modifier = Modifier.size(28.dp),
        ) {
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
private fun DrawerAgentRow(
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
