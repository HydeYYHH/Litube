package com.hhst.youtubelite.home.presentation

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.BasicText
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hhst.youtubelite.ui.design.*
import com.hhst.youtubelite.home.domain.*

@Composable
internal fun SearchOverlay(state: HomeUiState, onAction: (HomeAction) -> Unit) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val fieldHeight = maxOf(32.dp, with(LocalDensity.current) { 22.sp.toDp() })
    val toolbarHeight = maxOf(48.dp, fieldHeight)
    val close = { keyboard?.hide(); focusManager.clearFocus(); onAction(HomeAction.CloseOverlay) }
    LaunchedEffect(Unit) { focus.requestFocus(); keyboard?.show() }
    DisposableEffect(Unit) { onDispose { keyboard?.hide() } }
    Box(Modifier.fillMaxSize().padding(top = toolbarHeight).background(DesignTokens.Scrim)
        .clickable(onClick = close).semantics { contentDescription = "Close search" })
    Column(Modifier.fillMaxWidth().background(Color.White).testTag("search-overlay").semantics { paneTitle = "Search YouTube" }) {
        Row(Modifier.fillMaxWidth().height(toolbarHeight).background(Color(0xFFF1F1F1)), verticalAlignment = Alignment.CenterVertically) {
            AppIconButton(AppIcon.Back, "Back", onClick = close)
            Row(Modifier.weight(1f).height(fieldHeight).clip(RoundedCornerShape(topStart = 32.dp, bottomStart = 32.dp))
                .background(Color(0xFFE5E5E5)).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(state.query, { onAction(HomeAction.QueryChanged(it)) },
                    Modifier.weight(1f).focusRequester(focus).testTag("search-input").semantics { contentDescription = "Search YouTube" },
                    textStyle = DesignTokens.text(15, 22), singleLine = true, cursorBrush = SolidColor(DesignTokens.Ink),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); onAction(HomeAction.SubmitSearch(state.query)) }),
                    decorationBox = { inner -> Box { if (state.query.isEmpty()) AppText("Search YouTube", size = 15, line = 22, color = DesignTokens.Secondary); inner() } })
                if (state.query.isNotEmpty()) Box(Modifier.size(50.dp, 16.dp).clickable(role = Role.Button) {
                    onAction(HomeAction.QueryChanged("")); focus.requestFocus(); keyboard?.show()
                }.semantics { contentDescription = "Clear search" }.padding(start = 8.dp, end = 26.dp)) { AppIcon(AppIcon.Close, Modifier.size(16.dp)) }
            }
            Box(Modifier.size(36.dp, fieldHeight).clip(RoundedCornerShape(topEnd = 32.dp, bottomEnd = 32.dp)).background(Color(0xFFE5E5E5))
                .clickable(role = Role.Button) { keyboard?.hide(); onAction(HomeAction.SubmitSearch(state.query)) }
                .semantics { contentDescription = "Search" }, contentAlignment = Alignment.CenterStart) { AppIcon(AppIcon.Search) }
            Spacer(Modifier.width(8.dp))
            if (state.query.isEmpty()) Box(Modifier.padding(start = 4.dp, end = 12.dp).size(32.dp).background(Color(0xFFE5E5E5), CircleShape), contentAlignment = Alignment.Center) {
                AppIconButton(AppIcon.Voice, "Search with your voice", modifier = Modifier.wrapContentSize(unbounded = true), diameter = 40.dp) { onAction(HomeAction.Navigate(HomeNavigationRequest.VoiceSearch)) }
            }
        }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag("search-suggestions")) {
            state.suggestions.forEachIndexed { index, suggestion ->
                Row(Modifier.fillMaxWidth().heightIn(min = 37.dp).clickable(role = Role.Button) {
                    keyboard?.hide(); onAction(HomeAction.SubmitSearch(suggestion))
                }.testTag("suggestion-$index"), verticalAlignment = Alignment.CenterVertically) {
                    val query = state.query.trim()
                    val text = buildAnnotatedString {
                        val prefix = if (suggestion.startsWith(query, ignoreCase = true)) query.length else suggestion.length
                        append(suggestion.take(prefix))
                        withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append(suggestion.drop(prefix)) }
                    }
                    BasicText(text, Modifier.weight(1f).padding(horizontal = 8.dp), style = DesignTokens.text(14, 17), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Box(Modifier.size(37.dp).background(Color.Black.copy(alpha = 0.05f)).clickable(role = Role.Button) {
                        onAction(HomeAction.QueryChanged(suggestion)); focus.requestFocus(); keyboard?.show()
                    }.semantics { contentDescription = "Use suggestion $suggestion" }.padding(end = 4.dp), contentAlignment = Alignment.CenterEnd) {
                        AppIcon(AppIcon.ArrowUpLeft, Modifier.offset(y = 2.dp))
                    }
                }
                if (index < state.suggestions.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(Color.Black.copy(alpha = 0.05f)))
            }
        }
    }
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
