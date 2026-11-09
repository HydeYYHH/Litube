package com.hhst.youtubelite.channel.presentation

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hhst.youtubelite.R
import com.hhst.youtubelite.channel.domain.*
import com.hhst.youtubelite.ui.design.*
import androidx.compose.ui.res.stringResource
import com.hhst.youtubelite.dearrow.rememberDeArrowTitle
import com.hhst.youtubelite.dearrow.VideoArtwork

@Composable
internal fun ChannelSearchTab(page: ChannelPage, width: Float, height: Float, listState: LazyListState,
    query: String, submitted: String, onAction: (ChannelAction) -> Unit,
    results: ChannelSection<List<ChannelVideo>>? = null,
    canPage: Boolean = false,
    append: com.hhst.youtubelite.home.domain.AppendState = com.hhst.youtubelite.home.domain.AppendState.Idle,
    subscribePending: Boolean = false,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val submit = { keyboard?.hide(); onAction(ChannelAction.SubmitSearch) }
    val videos = remember(page.videos, submitted, results) {
        when (results) {
            is ChannelSection.Ready -> results.value
            null -> page.videos.filter { submitted.isBlank() || it.title.contains(submitted, ignoreCase = true) }
            else -> emptyList()
        }
    }
    val playlists = remember(page.playlists, submitted) {
        page.playlists.filter { submitted.isBlank() || it.title.contains(submitted, ignoreCase = true) }
    }
    ObserveChannelPagination(listState, canPage, append) { onAction(ChannelAction.LoadSearchNext) }
    var appliedQuery by remember { mutableStateOf(submitted) }
    LaunchedEffect(submitted) {
        if (submitted != appliedQuery) { appliedQuery = submitted; listState.scrollToItem(1) }
    }
    LazyColumn(Modifier.fillMaxSize().testTag("channel-search-list"), state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
        item { ChannelHeader(page, width, onAction, subscribePending) }
        stickyHeader("channel-tabbar") { ChannelTabBar(ChannelTab.Search, onAction) }
        item {
            Column(Modifier.fillMaxWidth().testTag("channel-search-form")) {
                Row(Modifier.fillMaxWidth().padding(horizontal = if (width < 300) 8.dp else 12.dp, vertical = 12.dp)
                    .heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(query, { onAction(ChannelAction.QueryChanged(it)) },
                        Modifier.weight(1f).testTag("channel-search-input"),
                        textStyle = DesignTokens.text(15, 20), singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { submit() }),
                        decorationBox = { field ->
                            Column {
                                Box(Modifier.fillMaxWidth().padding(start = 2.dp, top = 1.dp, bottom = 4.dp)) {
                                    if (query.isEmpty()) AppText(stringResource(R.string.ui_search_channel), size = 15, line = 20, color = DesignTokens.Secondary)
                                    field()
                                }
                                Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF737373)))
                            }
                        })
                    if (query.isNotEmpty()) AppIconButton(AppIcon.Close, stringResource(R.string.a11y_clear_channel_query), diameter = 40.dp) { onAction(ChannelAction.QueryChanged("")) }
                    AppIconButton(AppIcon.Search, stringResource(R.string.a11y_submit_channel_search), diameter = 40.dp, onClick = submit)
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(DesignTokens.NavigationDivider))
            }
        }
        items(playlists, key = { "playlist-${it.id}" }, contentType = { "playlist" }) { playlist ->
            com.hhst.youtubelite.playlist.presentation.ChannelPlaylistRow(playlist,
                { onAction(ChannelAction.OpenPlaylist(playlist.id)) }, { onAction(ChannelAction.OpenPlaylistMenu(playlist.id)) },
                if (width >= 931 || width >= 550 && width <= height) 40f else 48f)
        }
        when (results) {
            is ChannelSection.Loading -> item {
                AppText(stringResource(R.string.cast_searching), Modifier.fillMaxWidth().padding(24.dp).testTag("channel-search-loading"), color = DesignTokens.Secondary)
            }
            is ChannelSection.Failed -> item {
                AppText(stringResource(R.string.ui_search_failed_tap_to_retry),
                    Modifier.fillMaxWidth().padding(24.dp).clip(RoundedCornerShape(8.dp))
                        .clickable { onAction(ChannelAction.RetrySearch) }.padding(4.dp).testTag("channel-search-failed"),
                    color = DesignTokens.Secondary)
            }
            else -> {
                if (videos.isEmpty() && playlists.isEmpty()) item {
                    AppText(stringResource(R.string.ui_no_results_found), Modifier.fillMaxWidth().padding(24.dp).testTag("channel-search-empty"), color = DesignTokens.Secondary)
                }
                items(videos, key = { it.id }, contentType = { "video" }) { video -> ChannelSearchVideoRow(video, page.profile.name, width, height, onAction) }
            }
        }
        if (canPage || append != com.hhst.youtubelite.home.domain.AppendState.Idle) item(key = "channel-search-append") {
            Box(Modifier.fillMaxWidth().padding(16.dp).testTag("channel-search-append"), contentAlignment = Alignment.Center) {
                when (append) {
                    com.hhst.youtubelite.home.domain.AppendState.Loading ->
                        AppText(stringResource(R.string.ui_loading), color = DesignTokens.Secondary)
                    com.hhst.youtubelite.home.domain.AppendState.Failed ->
                        AppText(stringResource(R.string.ui_couldn_t_load_more_results_tap_to_retry),
                            Modifier.clip(RoundedCornerShape(8.dp)).clickable { onAction(ChannelAction.LoadSearchNext) }.padding(8.dp),
                            color = DesignTokens.Secondary)
                    else -> Unit
                }
            }
        }
    }
}

@Composable
private fun ChannelSearchVideoRow(video: ChannelVideo, channel: String, width: Float, height: Float, onAction: (ChannelAction) -> Unit) {
    val imageWidth = if (width < 300 || LocalDensity.current.fontScale > 1.3f) minOf(160f, (width - 24f) * .4f) else 160f
    Row(Modifier.fillMaxWidth().heightIn(min = 115.dp).padding(start = 12.dp, top = 12.dp, bottom = 12.dp)
        .testTag("channel-search-video-${video.id}")) {
        Box(Modifier.size(imageWidth.dp, (imageWidth * .5625f).dp).clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button) { onAction(ChannelAction.OpenVideo(video.id)) }.testTag("channel-search-thumb-${video.id}")) {
            VideoArtwork(video.id, video.artwork, Modifier.fillMaxSize())
            Box(Modifier.align(Alignment.BottomEnd).padding(5.dp).background(Color.Black.copy(alpha = .6f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp)) {
                AppText(video.duration, size = 12, line = 18, color = Color.White, maxLines = 1)
            }
        }
        Column(Modifier.weight(1f).padding(start = 8.dp, top = 2.dp).clickable(role = Role.Button) { onAction(ChannelAction.OpenVideo(video.id)) }) {
            AppText(rememberDeArrowTitle(video.id, video.title), size = 14, line = 18, exactLineHeight = 17.5.sp, maxLines = 2)
            AppText(channel, size = 12, line = 15, color = Color(0xFF888888), maxLines = 1)
            AppText(listOf(video.views, video.age).filter(String::isNotBlank).joinToString(" · "), size = 12, line = 15, color = Color(0xFF888888), maxLines = 2)
        }
        AppIconButton(AppIcon.More, stringResource(R.string.a11y_more_search_actions_for, video.title), diameter = if (width >= 931 || width >= 550 && width <= height) 40.dp else 48.dp) {
            onAction(ChannelAction.OpenItemMenu(video.id))
        }
    }
}
