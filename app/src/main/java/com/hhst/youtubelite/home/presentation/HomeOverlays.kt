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
internal fun SearchOverlay(state: HomeUiState, onAction: (HomeAction) -> Unit) {
    SearchPanel(
        query = state.query,
        suggestions = state.suggestions,
        onQueryChanged = { onAction(HomeAction.QueryChanged(it)) },
        onSubmit = { onAction(HomeAction.SubmitSearch(it)) },
        onClose = { onAction(HomeAction.CloseOverlay) },
        onVoiceSearch = { onAction(HomeAction.Navigate(HomeNavigationRequest.VoiceSearch)) },
    )
}

@Composable
internal fun VideoMenu(onAction: (HomeAction) -> Unit, kind: FeedMenuKind = FeedMenuKind.Video) {
    var drag by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current.density
    Box(Modifier.fillMaxSize().background(DesignTokens.MenuScrim).clickable { onAction(HomeAction.CloseOverlay) }
        .semantics { contentDescription = "Close menu" })
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Column(Modifier.padding(bottom = 24.dp).width(HomeLayout.menuWidth(maxWidth.value, maxHeight.value).dp)
            .offset(y = (drag / density).dp).clip(RoundedCornerShape(12.dp)).background(Color.White)
            .testTag("video-menu").semantics { paneTitle = "Video actions"
                customActions = listOf(CustomAccessibilityAction("Close menu") { onAction(HomeAction.CloseOverlay); true })
            }) {
            Box(Modifier.fillMaxWidth().height(13.dp).testTag("menu-handle").pointerInput(Unit) { detectVerticalDragGestures(
                onVerticalDrag = { change, amount -> change.consume(); drag = (drag + amount).coerceAtLeast(0f) },
                onDragCancel = { drag = 0f }, onDragEnd = { if (drag > 48 * density) onAction(HomeAction.CloseOverlay); drag = 0f },
            ) }, contentAlignment = Alignment.BottomCenter) {
                Box(Modifier.padding(bottom = 1.dp).size(40.dp, 4.dp).background(DesignTokens.Ink.copy(alpha = 0.15f), CircleShape))
            }
            Column(Modifier.verticalScroll(rememberScrollState()).testTag("menu-actions")) {
            kind.actions.forEach { action ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { onAction(HomeAction.MenuSelected(action)) }
                    .padding(horizontal = 22.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(when (action) {
                        FeedMenuAction.WatchLater -> AppIcon.Clock; FeedMenuAction.SaveToPlaylist -> AppIcon.Bookmark
                        FeedMenuAction.Share -> AppIcon.Share; FeedMenuAction.NotInterested -> AppIcon.Block
                        FeedMenuAction.DontRecommend -> AppIcon.Close; FeedMenuAction.ShowFewerShorts -> AppIcon.Dismiss
                        FeedMenuAction.SendFeedback -> AppIcon.Feedback; FeedMenuAction.Report -> AppIcon.Report
                        FeedMenuAction.SaveToLibrary -> AppIcon.Bookmark
                    })
                    Spacer(Modifier.width(18.dp))
                    AppText(action.label, size = 16, line = 22)
                }
            }
            }
        }
    }
}

@Composable
internal fun MixMenu(onAction: (HomeAction) -> Unit) {
    Box(Modifier.fillMaxSize().background(DesignTokens.Scrim).clickable { onAction(HomeAction.CloseOverlay) }
        .semantics { contentDescription = "Close menu" })
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val margin = if (maxWidth >= 330.dp) 40.dp else 0.dp
        val widthLimit = minOf(362.dp, (maxWidth - margin * 2).coerceAtLeast(0.dp))
        Column(Modifier.widthIn(min = if (maxWidth >= 330.dp) minOf(256.dp, widthLimit) else 0.dp, max = widthLimit).width(IntrinsicSize.Max)
            .heightIn(max = (maxHeight - margin * 2).coerceAtLeast(0.dp)).clip(RoundedCornerShape(8.dp)).background(Color.White)
            .testTag("mix-menu").semantics { paneTitle = "Mix actions" }
            .verticalScroll(rememberScrollState()).padding(3.dp)) {
            FeedMenuKind.Mix.actions.forEach { action ->
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
