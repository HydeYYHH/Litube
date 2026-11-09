package com.hhst.youtubelite.playlist.presentation

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hhst.youtubelite.R
import com.hhst.youtubelite.playlist.domain.PlaylistSummary
import com.hhst.youtubelite.search.domain.SearchResultsLayout
import com.hhst.youtubelite.ui.design.*
import androidx.compose.ui.res.stringResource

@Composable
internal fun PlaylistArtwork(asset: String, modifier: Modifier = Modifier) {
    com.hhst.youtubelite.ui.design.Artwork(asset, modifier)
}

@Composable
internal fun PlaylistCover(playlist: PlaylistSummary, modifier: Modifier, onOpen: () -> Unit, compact: Boolean = false) {
    Box(modifier.testTag("playlist-cover-${playlist.id}").clickable(role = Role.Button, onClick = onOpen)
        .semantics { contentDescription = listOfNotNull(playlist.title, playlist.countLabel).joinToString(", ") }) {
        Box(Modifier.padding(horizontal = 8.dp).fillMaxWidth().height(12.dp)
            .background(Color(playlist.accent).copy(alpha = 0.3f), RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)))
        Box(Modifier.padding(top = 4.dp, start = 4.dp, end = 4.dp).fillMaxWidth().height(12.dp)
            .background(Color(playlist.accent).copy(alpha = 0.6f), RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)))
        Box(Modifier.padding(top = 8.dp).fillMaxSize().clip(RoundedCornerShape(12.dp))) {
            PlaylistArtwork(playlist.artwork, Modifier.fillMaxSize())
            Row(Modifier.align(Alignment.BottomEnd).padding(8.dp)
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                .padding(horizontal = 4.dp, vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                AppIcon(AppIcon.Playlist, Modifier.size(12.dp), color = Color.White)
                Spacer(Modifier.width(4.dp))
                AppText(if (compact && playlist.videoCount != null) "${playlist.videoCount}" else playlist.countLabel.orEmpty(),
                    size = 12, line = 18, color = Color.White, maxLines = 1)
            }
        }
    }
}

@Composable
fun PlaylistSearchList(
    playlists: List<PlaylistSummary>, width: Float, height: Float, listState: LazyListState,
    onOpen: (String) -> Unit, onOwner: (String) -> Unit, onMenu: (String) -> Unit,
    footer: (@Composable () -> Unit)? = null,
) {
    val viewport = rememberUpdatedState(width to height)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max = 840.dp).fillMaxSize().testTag("playlist-search-list"), state = listState,
            contentPadding = PaddingValues(top = SearchResultsLayout.listTopInset(width).dp, bottom = 24.dp)) {
            items(playlists, key = { it.id }) { playlist ->
                val (w, h) = viewport.value
                val row = SearchResultsLayout.listLockup(w, h)
                val menuSize = SearchResultsLayout.menuSize(w, h).dp
                val open = { onOpen(playlist.id) }
                if (row) {
                    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 24.dp, bottom = 12.dp)
                        .heightIn(min = 118.dp).testTag("playlist-search-${playlist.id}")) {
                        PlaylistCover(playlist, Modifier.size(200.dp, 118.dp), open)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f).padding(top = 5.dp)) {
                            PlaylistTitle(playlist, Modifier.fillMaxWidth().clickable(onClick = open))
                            Spacer(Modifier.height(3.dp))
                            AppText(stringResource(R.string.ui_playlist_owner, playlist.owner), size = 12, line = 14, color = DesignTokens.Secondary,
                                modifier = Modifier.clickable { onOwner(playlist.channelId) }, maxLines = 1)
                        }
                        AppIconButton(AppIcon.More, stringResource(R.string.a11y_more_actions_for, playlist.title), diameter = menuSize,
                            modifier = Modifier.padding(top = 5.dp)) { onMenu(playlist.id) }
                    }
                } else {
                    val inset = if (w >= 550f) 24f else 12f
                    val coverWidth = minOf(w, 840f) - inset * 2
                    Column(Modifier.fillMaxWidth().padding(bottom = 24.dp).testTag("playlist-search-${playlist.id}")) {
                        PlaylistCover(playlist, Modifier.padding(horizontal = inset.dp, vertical = 4.dp)
                            .fillMaxWidth().height((coverWidth * 9f / 16f + 8f).dp), open)
                        val menuInset = if (w >= 550f) 12.dp else 0.dp
                        Box(Modifier.fillMaxWidth().padding(start = 12.dp, top = 8.dp)) {
                            Row(Modifier.fillMaxWidth().padding(end = menuSize + menuInset)) {
                                Box(Modifier.size(40.dp).clip(CircleShape).clickable { onOwner(playlist.channelId) }) {
                                    PlaylistArtwork(playlist.artwork, Modifier.fillMaxSize())
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f).heightIn(min = 43.dp).clickable(onClick = open)) {
                                    PlaylistTitle(playlist, Modifier.fillMaxWidth())
                                    Spacer(Modifier.height(3.dp))
                                    AppText(stringResource(R.string.ui_playlist_owner, playlist.owner), size = 12, line = 14, color = DesignTokens.Secondary, maxLines = 1)
                                }
                            }
                            Box(Modifier.matchParentSize().wrapContentSize(Alignment.TopEnd, unbounded = true).padding(end = menuInset)) {
                                AppIconButton(AppIcon.More, stringResource(R.string.a11y_more_actions_for, playlist.title), diameter = menuSize,
                                    modifier = Modifier.offset(y = (-8).dp)) { onMenu(playlist.id) }
                            }
                        }
                    }
                }
            }
            if (footer != null) item(key = "pagination") { footer() }
        }
    }
}

@Composable
private fun PlaylistTitle(playlist: PlaylistSummary, modifier: Modifier = Modifier, maxLines: Int = 2) {
    AppText(playlist.title, modifier.testTag("playlist-title-${playlist.id}"), size = 14, line = 18,
        exactLineHeight = 17.5.sp, maxLines = maxLines)
}

@Composable
fun ChannelPlaylistRow(playlist: PlaylistSummary, onOpen: () -> Unit, onMenu: () -> Unit, menuSize: Float = 48f) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp)
        .heightIn(min = 98.dp).testTag("channel-playlist-${playlist.id}")) {
        val coverWidth = if (LocalDensity.current.fontScale > 1.3f) minOf(160.dp, maxWidth * 0.4f) else 160.dp
        Row(Modifier.fillMaxWidth()) {
            PlaylistCover(playlist, Modifier.size(coverWidth, coverWidth * 9f / 16f + 8.dp), onOpen, compact = true)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f).padding(top = 8.dp).clickable(role = Role.Button, onClick = onOpen)) {
                PlaylistTitle(playlist, Modifier.fillMaxWidth(), maxLines = if (menuSize == 48f) 3 else 2)
                Spacer(Modifier.height(3.dp))
                AppText(stringResource(R.string.ui_playlist_owner, playlist.owner), size = 12, line = 14, color = DesignTokens.Secondary, maxLines = 1)
            }
            AppIconButton(AppIcon.More, stringResource(R.string.a11y_more_actions_for, playlist.title), diameter = menuSize.dp, onClick = onMenu)
        }
    }
}

@Composable
fun PlaylistShareSheet(onDismiss: () -> Unit, onShare: () -> Unit) {
    MenuBottomSheet(listOf(MenuSheetItem(AppIcon.Share, stringResource(R.string.share), "playlist-menu-share")),
        onDismiss, { onShare() }, tag = "playlist-share-menu", paneTitle = stringResource(R.string.ui_playlist_actions),
        rowStart = 12.dp, iconGap = 12.dp)
}
