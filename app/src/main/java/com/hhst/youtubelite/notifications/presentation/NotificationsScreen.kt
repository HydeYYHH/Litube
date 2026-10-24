package com.hhst.youtubelite.notifications.presentation

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hhst.youtubelite.home.presentation.PillButton
import com.hhst.youtubelite.library.presentation.LibraryArtwork
import com.hhst.youtubelite.library.presentation.LibraryMessageCard
import com.hhst.youtubelite.library.presentation.LibrarySkeleton
import com.hhst.youtubelite.notifications.domain.NotificationsAction
import com.hhst.youtubelite.notifications.domain.NotificationsContent
import com.hhst.youtubelite.notifications.domain.NotificationItem
import com.hhst.youtubelite.notifications.domain.NotificationsUiState
import com.hhst.youtubelite.ui.design.*

/** The notifications page: anonymous prompt, empty, loading, error and ready states. */
@Composable
fun NotificationsScreen(state: NotificationsUiState, onAction: (NotificationsAction) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().background(DesignTokens.Background).testTag("notifications-screen")) {
        Row(Modifier.fillMaxWidth().heightIn(min = DesignTokens.TopBarHeight).testTag("notifications-bar"), verticalAlignment = Alignment.CenterVertically) {
            AppIconButton(AppIcon.Back, "Back", diameter = 48.dp) { onBack() }
            AppText("Notifications", Modifier.weight(1f).padding(end = 16.dp), size = 20, line = 28, weight = FontWeight.Bold, maxLines = 1)
            Spacer(Modifier.weight(1f))
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (val content = state.content) {
                NotificationsContent.Loading -> LibrarySkeleton("notifications-skeleton")
                NotificationsContent.Failed -> NotificationsError { onAction(NotificationsAction.Retry) }
                is NotificationsContent.SignedOut -> LibraryMessageCard(
                    content.prompt.title, content.prompt.message, "notifications-signed-out",
                ) { PillButton(content.prompt.action) { onAction(NotificationsAction.SignIn) } }
                NotificationsContent.Empty -> LibraryMessageCard(
                    "No notifications", "Updates from your subscriptions will show up here.", "notifications-empty")
                is NotificationsContent.Ready -> NotificationList(content, onAction)
            }
        }
    }
}

@Composable
private fun NotificationList(content: NotificationsContent.Ready, onAction: (NotificationsAction) -> Unit) {
    val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    LazyColumn(Modifier.fillMaxSize().testTag("notifications-list"), state = listState,
        contentPadding = PaddingValues(bottom = 24.dp)) {
        items(content.page.items, key = { it.id }) { item ->
            NotificationRow(item, onClick = {
                val videoId = item.videoId
                if (videoId != null) onAction(NotificationsAction.OpenVideo(videoId))
                else onAction(NotificationsAction.OpenChannel(item.channelId))
            })
        }
    }
}

@Composable
private fun NotificationRow(item: NotificationItem, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
        .testTag("notification-${item.id}").padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top) {
        Box(Modifier.size(40.dp).clip(CircleShape)) { LibraryArtwork(item.avatar, Modifier.fillMaxSize()) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            AppText(item.text, size = 14, line = 20, maxLines = 3)
            AppText(item.age, Modifier.padding(top = 2.dp), size = 12, line = 18, color = DesignTokens.Secondary)
        }
        Box(Modifier.size(width = 100.dp, height = 56.dp).clip(RoundedCornerShape(8.dp))) {
            LibraryArtwork(item.artwork, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun NotificationsError(onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("notifications-error").padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        AppText("Something went wrong", size = 20, line = 28, weight = FontWeight.Bold)
        AppText("Please try again.", Modifier.padding(top = 8.dp, bottom = 20.dp), color = DesignTokens.Secondary)
        PillButton("Retry", onRetry)
    }
}
