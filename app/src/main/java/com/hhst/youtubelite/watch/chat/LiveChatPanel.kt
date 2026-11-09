package com.hhst.youtubelite.watch.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hhst.youtubelite.comments.domain.CommentsLayout
import com.hhst.youtubelite.ui.design.AppIcon
import com.hhst.youtubelite.ui.design.AppIconButton
import com.hhst.youtubelite.ui.design.AppText
import com.hhst.youtubelite.ui.design.Artwork
import com.hhst.youtubelite.ui.design.DesignTokens
import com.hhst.youtubelite.ui.design.PanelTransition
import com.hhst.youtubelite.ui.design.rememberBottomSheetDrag
import com.hhst.youtubelite.ui.theme.AppStyle
import com.hhst.youtubelite.watch.domain.WatchLayout
import kotlin.math.roundToInt
import com.hhst.youtubelite.ui.design.appDescription
import androidx.compose.ui.res.stringResource
import com.hhst.youtubelite.R

/** Live chat on live watch pages: same dock/bottom-sheet geometry as the comments panel. */
@Composable
fun LiveChatPanel(
    state: LiveChatUiState,
    onAction: (LiveChatAction) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val width = maxWidth.value
        val height = maxHeight.value
        PanelTransition(fromBottom = !WatchLayout.sidebar(width, height), onDismiss = onClose) { panelMotion, close ->
        if (WatchLayout.sidebar(width, height)) {
            Row(Modifier.fillMaxSize().testTag("live-chat-rail")) {
                Spacer(Modifier.weight(1f).fillMaxHeight())
                LiveChatSurface(state, onAction, close, compact = false, listState = listState,
                    modifier = Modifier.width(WatchLayout.sidebarWidth(width).dp).fillMaxHeight()
                        .padding(top = WatchLayout.TopBarHeight.dp).then(panelMotion))
            }
        } else {
            val top = CommentsLayout.sheetTop(
                topBar = WatchLayout.TopBarHeight,
                playerHeight = WatchLayout.playerHeight(width, height),
                windowHeight = height,
            ).roundToInt().toFloat()
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier.fillMaxSize()
                        .clickable(remember { MutableInteractionSource() }, indication = null) { close() }
                        .appDescription(R.string.a11y_close_live_chat)
                        .testTag("live-chat-scrim"),
                )
                LiveChatSurface(state, onAction, close, compact = true, listState = listState,
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .height((height - top).coerceAtLeast(0f).dp).then(panelMotion))
            }
        }
        }
    }
}

@Composable
private fun LiveChatSurface(
    state: LiveChatUiState,
    onAction: (LiveChatAction) -> Unit,
    onClose: () -> Unit,
    compact: Boolean,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    val shape = if (compact) RoundedCornerShape(topStart = CommentsLayout.SheetRadius.dp,
        topEnd = CommentsLayout.SheetRadius.dp) else RectangleShape
    val title = stringResource(if (state.replay) R.string.chat_replay else R.string.chat_live)
    val drag = rememberBottomSheetDrag(enabled = compact, onDismiss = onClose)
    Column(
        modifier.fillMaxSize().then(drag.panelModifier).background(DesignTokens.Panel, shape)
            .semantics { paneTitle = title }
            .testTag("live-chat"),
    ) {
        Column(Modifier.fillMaxWidth().then(drag.handleModifier).testTag("live-chat-drag-header")) {
        if (compact) {
            Box(Modifier.fillMaxWidth().height(CommentsLayout.HandleStripHeight.dp), contentAlignment = Alignment.BottomCenter) {
                Box(Modifier.width(CommentsLayout.HandleWidth.dp).height(CommentsLayout.HandleHeight.dp)
                    .background(Color(0xFFDBDBDB), RoundedCornerShape(2.dp)))
            }
        }
        Row(Modifier.fillMaxWidth().height(48.dp).padding(start = 16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            AppIconButton(AppIcon.Close, stringResource(R.string.a11y_close_live_chat), Modifier.testTag("live-chat-close"), onClick = onClose)
        }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (val content = state.content) {
                LiveChatContent.Loading -> Box(Modifier.fillMaxSize().testTag("live-chat-loading"),
                    contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp), color = AppStyle.Action, strokeWidth = 3.dp)
                }
                LiveChatContent.Failed -> Column(Modifier.fillMaxSize().testTag("live-chat-failed").padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
                    AppText(stringResource(R.string.ui_couldn_t_load_the_chat), size = 14, line = 20, color = DesignTokens.Secondary)
                    Spacer(Modifier.height(8.dp))
                    AppText(stringResource(R.string.retry), Modifier.clickable { onAction(LiveChatAction.Retry) }.testTag("live-chat-retry").padding(8.dp),
                        size = 14, line = 20, weight = FontWeight.Medium, color = AppStyle.Action)
                }
                is LiveChatContent.Ready -> {
                    if (content.messages.isEmpty()) {
                        Box(Modifier.fillMaxSize().testTag("live-chat-empty"), contentAlignment = Alignment.Center) {
                            AppText(
                                if (state.replay) stringResource(R.string.chat_replay_empty) else stringResource(R.string.ui_say_hello_in_the_chat),
                                size = 14, line = 20, color = DesignTokens.Secondary,
                            )
                        }
                    }
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag("live-chat-list")) {
                        items(content.messages, key = { it.id }) { message ->
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
                                .testTag("live-chat-message")) {
                                Artwork(message.photo, Modifier.size(24.dp).clip(CircleShape))
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    AppText(message.author, size = 12, line = 16, weight = FontWeight.Medium,
                                        color = DesignTokens.Secondary, maxLines = 1)
                                    AppText(message.text, size = 14, line = 20)
                                }
                            }
                        }
                    }
                    // Stick to the bottom while the reader is already near it.
                    var previousLastIndex by remember(listState) { mutableIntStateOf(-1) }
                    LaunchedEffect(content.messages.lastOrNull()?.id) {
                        val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                        val follow = previousLastIndex < 0 || last >= previousLastIndex - 2
                        previousLastIndex = content.messages.lastIndex
                        if (content.messages.isNotEmpty() && follow) {
                            listState.scrollToItem(content.messages.size - 1)
                        }
                    }
                }
            }
        }
        when {
            state.ended || state.replay -> AppText(stringResource(R.string.chat_ended),
                Modifier.fillMaxWidth().padding(16.dp).testTag("live-chat-ended"),
                size = 14, line = 20, color = DesignTokens.Secondary)
            state.content !is LiveChatContent.Ready -> Unit
            state.signedIn && !state.canSend -> AppText(stringResource(R.string.ui_posting_is_unavailable_for_this_chat),
                Modifier.fillMaxWidth().padding(16.dp).testTag("live-chat-readonly"),
                size = 14, line = 20, color = DesignTokens.Secondary)
            state.signedIn -> {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).heightIn(min = 36.dp)
                        .background(DesignTokens.Surface, RoundedCornerShape(18.dp))
                        .padding(horizontal = 14.dp, vertical = 8.dp)) {
                        if (state.draft.isEmpty()) {
                            AppText(stringResource(R.string.ui_chat_publicly), size = 14, line = 20, color = DesignTokens.Secondary)
                        }
                        BasicTextField(state.draft, { onAction(LiveChatAction.DraftChanged(it)) },
                            Modifier.fillMaxWidth().testTag("live-chat-input"),
                            textStyle = DesignTokens.text(14, 20, FontWeight.Normal, DesignTokens.Ink))
                    }
                    Spacer(Modifier.width(8.dp))
                    val canSend = state.draft.isNotBlank() && !state.sending
                    Box(Modifier.size(48.dp).testTag("live-chat-send")
                        .clickable(enabled = canSend, role = androidx.compose.ui.semantics.Role.Button) { onAction(LiveChatAction.Send) }
                        .appDescription(R.string.a11y_send_message), contentAlignment = Alignment.Center) {
                        if (state.sending) com.hhst.youtubelite.ui.design.ActionProgress(color = AppStyle.Action)
                        else AppIcon(AppIcon.Send, color = if (canSend) AppStyle.Action else DesignTokens.Secondary.copy(alpha = .5f))
                    }
                }
                state.postingRestriction?.let { reason ->
                    AppText(reason, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                        .testTag("live-chat-posting-restriction"), size = 14, line = 20, color = DesignTokens.Secondary)
                }
            }
            else -> AppText(stringResource(R.string.ui_sign_in_to_join_the_chat),
                Modifier.fillMaxWidth().clickable { onAction(LiveChatAction.SignIn) }
                    .testTag("live-chat-signin").padding(16.dp),
                size = 14, line = 20, weight = FontWeight.Medium, color = AppStyle.Action)
        }
    }
}
