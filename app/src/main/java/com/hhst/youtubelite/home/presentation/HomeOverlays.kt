package com.hhst.youtubelite.home.presentation

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.hhst.youtubelite.ui.design.*
import com.hhst.youtubelite.home.domain.*

@Composable
internal fun SearchOverlay(state: HomeUiState, onAction: (HomeAction) -> Unit) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focus.requestFocus(); keyboard?.show() }
    Box(Modifier.fillMaxSize().padding(top = 48.dp).background(DesignTokens.Scrim)
        .clickable { onAction(HomeAction.CloseOverlay) }.semantics { contentDescription = "Close search" })
    Column(Modifier.fillMaxWidth().background(Color.White).testTag("search-overlay").semantics { paneTitle = "Search YouTube" }) {
        Row(Modifier.fillMaxWidth().height(48.dp).background(Color(0xFFF1F1F1)).padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIconButton(AppIcon.Back, "Back") { keyboard?.hide(); onAction(HomeAction.CloseOverlay) }
            Row(Modifier.weight(1f).height(32.dp).clip(CircleShape).background(Color(0xFFE5E5E5)).padding(start = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(state.query, { onAction(HomeAction.QueryChanged(it)) },
                    Modifier.weight(1f).focusRequester(focus).testTag("search-input").semantics { contentDescription = "Search YouTube" },
                    textStyle = DesignTokens.text(15, 22), singleLine = true, cursorBrush = SolidColor(DesignTokens.Ink),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); onAction(HomeAction.SubmitSearch(state.query)) }),
                    decorationBox = { inner -> Box { if (state.query.isEmpty()) AppText("Search YouTube", size = 15, line = 22, color = DesignTokens.Secondary); inner() } })
                if (state.query.isNotEmpty()) AppIconButton(AppIcon.Close, "Clear search", diameter = 32.dp) { onAction(HomeAction.QueryChanged("")) }
                AppIconButton(AppIcon.Search, "Search", diameter = 32.dp) { keyboard?.hide(); onAction(HomeAction.SubmitSearch(state.query)) }
            }
            Spacer(Modifier.width(12.dp))
            AppIconButton(AppIcon.Voice, "Search with your voice", diameter = 32.dp, background = Color(0xFFE5E5E5)) { onAction(HomeAction.Navigate(HomeNavigationRequest.VoiceSearch)) }
        }
        state.suggestions.forEach { suggestion ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) {
                keyboard?.hide(); onAction(HomeAction.SubmitSearch(suggestion))
            }.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                AppIcon(AppIcon.Search)
                AppText(suggestion, Modifier.weight(1f).padding(horizontal = 12.dp), size = 16, line = 22, maxLines = 2)
                AppIconButton(AppIcon.ArrowUpLeft, "Use suggestion $suggestion") { onAction(HomeAction.QueryChanged(suggestion)) }
            }
        }
    }
}

@Composable
internal fun VideoMenu(onAction: (HomeAction) -> Unit) {
    var drag by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current.density
    Box(Modifier.fillMaxSize().background(DesignTokens.MenuScrim).clickable { onAction(HomeAction.CloseOverlay) }
        .semantics { contentDescription = "Close menu" })
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Column(Modifier.padding(horizontal = 8.dp).padding(bottom = 24.dp).widthIn(max = 400.dp).fillMaxWidth()
            .offset(y = (drag / density).dp).clip(RoundedCornerShape(12.dp)).background(Color.White)
            .testTag("video-menu").semantics { paneTitle = "Video actions" }
            .pointerInput(Unit) { detectVerticalDragGestures(
                onVerticalDrag = { change, amount -> change.consume(); drag = (drag + amount).coerceAtLeast(0f) },
                onDragCancel = { drag = 0f }, onDragEnd = { if (drag > 48 * density) onAction(HomeAction.CloseOverlay); drag = 0f },
            ) }.verticalScroll(rememberScrollState())) {
            Box(Modifier.fillMaxWidth().height(13.dp), contentAlignment = Alignment.BottomCenter) {
                Box(Modifier.padding(bottom = 1.dp).size(40.dp, 4.dp).background(Color(0xFFD9D9D9), CircleShape))
            }
            FeedMenuAction.entries.forEach { action ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { onAction(HomeAction.MenuSelected(action)) }
                    .padding(horizontal = 22.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(when (action) {
                        FeedMenuAction.WatchLater -> AppIcon.Clock; FeedMenuAction.SaveToPlaylist -> AppIcon.Bookmark
                        FeedMenuAction.Share -> AppIcon.Share; FeedMenuAction.NotInterested -> AppIcon.Block; FeedMenuAction.DontRecommend -> AppIcon.Close
                    })
                    AppText(action.label, Modifier.padding(start = 18.dp), size = 16, line = 22)
                }
            }
        }
    }
}
