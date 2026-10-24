package com.hhst.youtubelite.home.presentation

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.hhst.youtubelite.ui.design.*
import com.hhst.youtubelite.home.domain.*

@Composable
internal fun SearchOverlay(state: HomeUiState, onAction: (HomeAction) -> Unit,
    rows: com.hhst.youtubelite.ui.components.SearchRows? = null) {
    SearchPanel(
        query = state.query,
        suggestions = rows?.suggestions ?: state.suggestions,
        history = rows?.history.orEmpty(),
        onRemoveHistory = rows?.onRemove,
        onQueryChanged = { onAction(HomeAction.QueryChanged(it)) },
        onSubmit = { onAction(HomeAction.SubmitSearch(it)); rows?.onSubmit?.invoke(it) },
        onClose = { onAction(HomeAction.CloseOverlay) },
        onVoiceSearch = { onAction(HomeAction.Navigate(HomeNavigationRequest.VoiceSearch)) },
    )
}

@Composable
internal fun VideoMenu(onAction: (HomeAction) -> Unit, kind: FeedMenuKind = FeedMenuKind.Video, live: Boolean = false) {
    val rows = kind.visibleActions(live)
    MenuBottomSheet(
        items = rows.map { MenuSheetItem(it.icon(), it.label) },
        onDismiss = { onAction(HomeAction.CloseOverlay) },
        onSelect = { index -> onAction(HomeAction.MenuSelected(rows[index])) },
        tag = "video-menu",
        paneTitle = "Video actions",
    )
}

private fun FeedMenuAction.icon(): AppIcon = when (this) {
    FeedMenuAction.WatchLater -> AppIcon.Clock
    FeedMenuAction.SaveToPlaylist -> AppIcon.Bookmark
    FeedMenuAction.Share -> AppIcon.Share
    FeedMenuAction.NotInterested -> AppIcon.Block
    FeedMenuAction.DontRecommend -> AppIcon.Close
    FeedMenuAction.ShowFewerShorts -> AppIcon.Dismiss
    FeedMenuAction.SendFeedback -> AppIcon.Feedback
    FeedMenuAction.Report -> AppIcon.Report
    FeedMenuAction.SaveToLibrary -> AppIcon.Bookmark
}

@Composable
internal fun MixMenu(onAction: (HomeAction) -> Unit, live: Boolean = false) {
    Box(Modifier.fillMaxSize().background(DesignTokens.Scrim).clickable { onAction(HomeAction.CloseOverlay) }
        .semantics { contentDescription = "Close menu" })
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val margin = if (maxWidth >= 330.dp) 40.dp else 0.dp
        val widthLimit = minOf(362.dp, (maxWidth - margin * 2).coerceAtLeast(0.dp))
        Column(Modifier.widthIn(min = if (maxWidth >= 330.dp) minOf(256.dp, widthLimit) else 0.dp, max = widthLimit).width(IntrinsicSize.Max)
            .heightIn(max = (maxHeight - margin * 2).coerceAtLeast(0.dp)).clip(RoundedCornerShape(8.dp)).background(DesignTokens.Background)
            .testTag("mix-menu").semantics { paneTitle = "Mix actions" }
            .verticalScroll(rememberScrollState()).padding(3.dp)) {
            FeedMenuKind.Mix.visibleActions(live).forEach { action ->
                Box(Modifier.padding(vertical = 3.dp).fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) {
                    onAction(HomeAction.MenuSelected(action))
                }.padding(horizontal = 16.dp, vertical = 8.dp), contentAlignment = Alignment.CenterStart) {
                    AppText(action.label, size = 16, line = 22)
                }
            }
            Box(Modifier.padding(vertical = 3.dp).fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) {
                onAction(HomeAction.CloseOverlay)
            }.padding(horizontal = 16.dp, vertical = 8.dp), contentAlignment = Alignment.CenterStart) {
                AppText("Cancel", size = 16, line = 22)
            }
        }
    }
}
