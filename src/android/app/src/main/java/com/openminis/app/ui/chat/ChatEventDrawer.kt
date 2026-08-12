package com.openminis.app.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.openminis.app.R

/**
 * Right-side event drawer shell. It deliberately owns no VCP connection or
 * message state yet: VCPInfo and VCPLog will plug into the two content slots
 * without changing ChatScreen's overlay, animation, or dismissal behaviour.
 */
@Composable
internal fun ChatEventDrawer(
    visible: Boolean,
    onDismiss: () -> Unit,
) {
    val duration = 240
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(duration)),
        exit = fadeOut(tween(duration)),
        modifier = Modifier.fillMaxSize().zIndex(20f),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.42f))
                .clickable(onClick = onDismiss),
        )
    }
    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(tween(duration)) { it },
        exit = slideOutHorizontally(tween(duration)) { it },
        modifier = Modifier
            .fillMaxHeight()
            .zIndex(21f),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
            val width = LocalConfiguration.current.screenWidthDp.dp * 0.8f
            Surface(
                modifier = Modifier.requiredWidth(width).fillMaxHeight(),
                color = MaterialTheme.colorScheme.background,
                shadowElevation = 16.dp,
            ) {
                EventDrawerContent()
            }
        }
    }
}

@Composable
private fun EventDrawerContent() {
    var selectedTab by remember { mutableIntStateOf(0) }
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.chat_event_drawer_title),
                modifier = Modifier.weight(1f),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
            Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.outline, CircleShape))
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp))
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            listOf(
                R.string.chat_event_drawer_vcpinfo to Icons.Outlined.Info,
                R.string.chat_event_drawer_vcplog to Icons.AutoMirrored.Outlined.Article,
            ).forEachIndexed { index, (label, icon) ->
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .background(
                            if (selectedTab == index) MaterialTheme.colorScheme.surface else Color.Transparent,
                            RoundedCornerShape(9.dp),
                        )
                        .clickable { selectedTab = index }
                        .padding(horizontal = 8.dp, vertical = 9.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(17.dp))
                    Text(
                        stringResource(label),
                        modifier = Modifier.padding(start = 6.dp),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
        HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
        Column(
            modifier = Modifier.fillMaxSize().padding(28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Outlined.NotificationsNone,
                contentDescription = null,
                modifier = Modifier.size(44.dp),
                tint = MaterialTheme.colorScheme.outline,
            )
            Text(
                stringResource(
                    if (selectedTab == 0) R.string.chat_event_drawer_vcpinfo_placeholder
                    else R.string.chat_event_drawer_vcplog_placeholder,
                ),
                modifier = Modifier.padding(top = 12.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp,
            )
        }
    }
}
