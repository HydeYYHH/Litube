package com.hhst.youtubelite.playlist.presentation

import com.hhst.youtubelite.ui.components.mediaActions
import com.hhst.youtubelite.ui.components.MediaActionItem
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hhst.youtubelite.playlist.domain.*
import com.hhst.youtubelite.ui.design.*

@Composable
fun PlaylistScreen(state: PlaylistUiState, onAction: (PlaylistAction) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    AppTheme {
        BoxWithConstraints(modifier.fillMaxSize().background(Color.White).testTag("playlist-page")) {
            val width = maxWidth.value
            val viewport = rememberUpdatedState(width)
            val listState = rememberLazyListState()
            val headerScrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
            val page = (state.content as? PlaylistContent.Ready)?.page
            Column(Modifier.fillMaxSize().then(if (state.overlay != PlaylistOverlay.None) Modifier.clearAndSetSemantics {} else Modifier)) {
                Row(Modifier.fillMaxWidth().height(48.dp).background(page?.summary?.accent?.let(::Color) ?: Color.White)
                    .testTag("playlist-toolbar"), verticalAlignment = Alignment.CenterVertically) {
                    val color = if (page != null) Color.White else DesignTokens.Ink
                    AppIconButton(AppIcon.Back, "Back", color = color, onClick = onBack)
                    Box(Modifier.weight(1f)) {
                        if (headerScrolled) AppText(page?.summary?.title.orEmpty(),
                            size = 20, line = 28, weight = FontWeight.Bold, color = color, maxLines = 1)
                    }
                    AppIconButton(AppIcon.Search, "Search", color = color) { onAction(PlaylistAction.OpenSearch) }
                    AppIconButton(AppIcon.More, "More options", color = color) { onAction(PlaylistAction.OpenMenu) }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (val content = state.content) {
                        PlaylistContent.Loading -> PlaylistSkeleton(width)
                        PlaylistContent.Unavailable -> PlaylistMessage("playlist-unavailable", "This playlist isn't available")
                        PlaylistContent.Failed -> PlaylistMessage("playlist-error", "Something went wrong") {
                            AppText("Retry", Modifier.padding(top = 16.dp).clip(CircleShape).background(DesignTokens.Surface)
                                .clickable { onAction(PlaylistAction.Retry) }.padding(horizontal = 20.dp, vertical = 10.dp), weight = FontWeight.Medium)
                        }
                        is PlaylistContent.Ready -> LazyColumn(Modifier.fillMaxSize().testTag("playlist-list"), state = listState,
                            contentPadding = PaddingValues(bottom = 16.dp)) {
                            item(key = "header") {
                                val w = viewport.value
                                PlaylistHero(content.page, w, PlaylistLayout.sideBySide(w), state.descriptionExpanded, onAction)
                            }
                            if (content.page.entries.isEmpty()) item {
                                AppText("No videos in this playlist yet", Modifier.fillMaxWidth().padding(24.dp).testTag("playlist-empty"),
                                    color = DesignTokens.Secondary, align = TextAlign.Center)
                            }
                            items(content.page.entries, key = { it.id }) { entry -> PlaylistVideoRow(entry, onAction) }
                        }
                    }
                }
                PlaylistNavigation(onAction)
            }
            when (state.overlay) {
                PlaylistOverlay.None -> Unit
                PlaylistOverlay.Search -> SearchPanel(state.query, if (state.query.isBlank()) emptyList() else listOf(state.query),
                    { onAction(PlaylistAction.QueryChanged(it)) }, { onAction(PlaylistAction.Search(it)) },
                    { onAction(PlaylistAction.CloseOverlay) }, { onAction(PlaylistAction.VoiceSearch) })
                PlaylistOverlay.Menu -> MenuBottomSheet(
                    listOf(MenuSheetItem(AppIcon.Shuffle, "Shuffle", "playlist-shuffle"),
                        MenuSheetItem(AppIcon.Download, "Download", "playlist-download")),
                    { onAction(PlaylistAction.CloseOverlay) }, { onAction(if (it == 0) PlaylistAction.Shuffle else PlaylistAction.Download) },
                    tag = "playlist-menu", paneTitle = "Playlist actions")
                is PlaylistOverlay.VideoMenu -> MenuBottomSheet(
                    PlaylistVideoAction.entries.map { MenuSheetItem(when(it) {
                        PlaylistVideoAction.WatchLater -> null
                        PlaylistVideoAction.Save -> AppIcon.Bookmark
                    }, it.label, "playlist-video-action-${it.name}", if (it == PlaylistVideoAction.WatchLater) 16.dp else null) },
                    { onAction(PlaylistAction.CloseOverlay) }, { onAction(PlaylistAction.VideoMenuAction(PlaylistVideoAction.entries[it])) },
                    tag = "playlist-video-menu", paneTitle = "Video actions")
            }
        }
    }
}

@Composable
private fun PlaylistHero(
    page: PlaylistPage,
    width: Float,
    wide: Boolean,
    descriptionExpanded: Boolean,
    onAction: (PlaylistAction) -> Unit,
) {
    val accent = Color(page.summary.accent)
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(0.dp)).background(accent).testTag("playlist-hero")) {
        Box(Modifier.matchParentSize()) {
            PlaylistArtwork(page.summary.artwork, Modifier.requiredWidth((width * 2).dp).aspectRatio(16f / 9f)
                .offset(x = (-width / 2).dp, y = (-width * 9 / 32).dp).blur(30.dp, BlurredEdgeTreatment.Unbounded).alpha(0.7f))
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(0f to accent.copy(alpha = 0.8f),
                0.33f to accent.copy(alpha = 0.298f), 1f to accent.copy(alpha = 0.8f))))
        }
        if (wide) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                PlaylistArtwork(page.summary.artwork, Modifier.size(PlaylistLayout.coverWidth(width).dp, PlaylistLayout.coverHeight(width).dp)
                    .clip(RoundedCornerShape(8.dp)).clickable { onAction(PlaylistAction.PlayAll) }.testTag("playlist-hero-cover"))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    PlaylistIdentity(page, width, descriptionExpanded, onAction)
                    PlaylistButtons(onAction, stretch = false)
                    Spacer(Modifier.height(8.dp))
                }
            }
        } else {
            Column(Modifier.padding(16.dp)) {
                PlaylistArtwork(page.summary.artwork, Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(8.dp)).clickable { onAction(PlaylistAction.PlayAll) }.testTag("playlist-hero-cover"))
                Spacer(Modifier.height(16.dp))
                PlaylistIdentity(page, width, descriptionExpanded, onAction)
                PlaylistButtons(onAction, stretch = width < 600f)
            }
        }
    }
}

@Composable
private fun PlaylistIdentity(
    page: PlaylistPage,
    width: Float,
    descriptionExpanded: Boolean,
    onAction: (PlaylistAction) -> Unit,
) {
    val large = PlaylistLayout.largeText(width)
    val medium = PlaylistLayout.mediumText(width)
    val text = if (medium) 14 else 12
    val line = if (medium) 20 else 18
    AppText(page.summary.title, Modifier.testTag("playlist-heading"), size = if (large) 36 else 24,
        line = if (large) 50 else 32, weight = FontWeight.Bold, color = Color.White, maxLines = 2)
    val owner: @Composable () -> Unit = {
        Row(Modifier.heightIn(min = 32.dp).clickable { onAction(PlaylistAction.OpenOwner) }, verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.padding(2.dp).size(24.dp).clip(CircleShape)) { PlaylistArtwork(page.summary.artwork, Modifier.fillMaxSize()) }
            Spacer(Modifier.width(6.dp))
            AppText(page.summary.owner, size = text, line = line, color = Color.White, maxLines = 1)
        }
    }
    val metadata = listOfNotNull("Playlist", page.summary.countLabel, page.summary.views.takeIf(String::isNotBlank))
    if (large) FlowRow(Modifier.padding(top = 2.dp), verticalArrangement = Arrangement.Center) {
        owner()
        metadata.forEach { part ->
            Row(Modifier.align(Alignment.CenterVertically), verticalAlignment = Alignment.CenterVertically) {
                AppText("•", Modifier.padding(horizontal = 4.dp), size = text, line = line, color = Color.White.copy(alpha = 0.7f))
                AppText(part, size = text, line = line, color = Color.White.copy(alpha = 0.7f), maxLines = 1)
            }
        }
    } else {
        Box(Modifier.padding(top = 2.dp)) { owner() }
        FlowRow(Modifier.padding(top = 2.dp)) {
            metadata.forEachIndexed { index, part ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (index > 0) AppText("•", Modifier.padding(horizontal = 4.dp), size = text, line = line, color = Color.White.copy(alpha = 0.7f))
                    AppText(part, size = text, line = line, color = Color.White.copy(alpha = 0.7f), maxLines = 1)
                }
            }
        }
    }
    AppText(page.description, Modifier.padding(top = 12.dp).widthIn(max = 600.dp)
        .clickable(role = Role.Button) { onAction(PlaylistAction.ToggleDescription) }
        .semantics { contentDescription = if (descriptionExpanded) "Show less description" else "Show full description" }
        .testTag("playlist-description"),
        size = text, line = line, color = Color.White.copy(alpha = 0.7f),
        maxLines = if (descriptionExpanded) Int.MAX_VALUE else 2)
}

@Composable
private fun PlaylistButtons(onAction: (PlaylistAction) -> Unit, stretch: Boolean) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 12.dp).testTag("playlist-actions")) {
        val scroll = LocalDensity.current.fontScale > 1.3f && maxWidth < 400.dp
        Row(Modifier.fillMaxWidth().then(if (scroll) Modifier.horizontalScroll(rememberScrollState()) else Modifier),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.then(if (stretch && !scroll) Modifier.weight(1f) else Modifier).heightIn(min = 40.dp)
                .clip(CircleShape).background(Color.White).clickable(role = Role.Button) { onAction(PlaylistAction.PlayAll) }
                .padding(horizontal = 16.dp, vertical = 8.dp).testTag("playlist-play-all"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                AppIcon(AppIcon.Play, color = Color.Black)
                Spacer(Modifier.width(6.dp))
                AppText("Play all", weight = FontWeight.Medium, color = Color.Black)
            }
            AppIconButton(AppIcon.Bookmark, "Save playlist", diameter = 40.dp, background = Color.White.copy(alpha = 0.1f), color = Color.White) { onAction(PlaylistAction.Save) }
            AppIconButton(AppIcon.Share, "Share playlist", diameter = 40.dp, background = Color.White.copy(alpha = 0.1f), color = Color.White) { onAction(PlaylistAction.Share) }
            AppIconButton(AppIcon.More, "More playlist actions", diameter = 40.dp, background = Color.White.copy(alpha = 0.1f), color = Color.White) { onAction(PlaylistAction.OpenMenu) }
        }
    }
}

@Composable
private fun PlaylistVideoRow(entry: PlaylistEntry, onAction: (PlaylistAction) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
        .heightIn(min = 90.dp).testTag("playlist-entry-${entry.id}")) {
        val thumbnailWidth = if (LocalDensity.current.fontScale > 1.3f) minOf(160.dp, maxWidth * 0.4f) else 160.dp
        Row(Modifier.fillMaxWidth()) {
            Box(Modifier.size(thumbnailWidth, thumbnailWidth * 9f / 16f).clip(RoundedCornerShape(8.dp))
                .mediaActions(MediaActionItem(entry.videoId, entry.title, entry.channel, entry.artwork)) { onAction(PlaylistAction.OpenVideo(entry.id)) }.testTag("playlist-entry-cover-${entry.id}")) {
                PlaylistArtwork(entry.artwork, Modifier.fillMaxSize())
                AppText(entry.duration, Modifier.align(Alignment.BottomEnd).padding(4.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp),
                    size = 12, line = 18, weight = FontWeight.Medium, color = Color.White)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).mediaActions(MediaActionItem(entry.videoId, entry.title, entry.channel, entry.artwork)) { onAction(PlaylistAction.OpenVideo(entry.id)) }) {
                AppText(entry.title, Modifier.fillMaxWidth().padding(end = 24.dp).testTag("playlist-entry-title-${entry.id}"), weight = FontWeight.Medium, maxLines = 2)
                AppText(entry.channel, Modifier.padding(top = 4.dp), size = 12, line = 18, color = DesignTokens.Secondary, maxLines = 1)
                Row(Modifier.padding(top = 4.dp)) {
                    AppText(entry.views, Modifier.weight(1f, fill = false), size = 12, line = 18, color = DesignTokens.Secondary, maxLines = 1)
                    AppText("•", Modifier.padding(horizontal = 4.dp), size = 12, line = 18, color = DesignTokens.Secondary)
                    AppText(entry.age, Modifier.weight(1f, fill = false), size = 12, line = 18, color = DesignTokens.Secondary, maxLines = 1)
                }
            }
        }
        AppIconButton(AppIcon.More, "More actions for ${entry.title}", diameter = 40.dp,
            modifier = Modifier.align(Alignment.TopEnd).offset(x = 10.dp, y = (-6).dp).testTag("playlist-entry-menu-${entry.id}")) {
            onAction(PlaylistAction.OpenVideoMenu(entry.id))
        }
    }
}

@Composable
private fun PlaylistNavigation(onAction: (PlaylistAction) -> Unit) {
    Column(Modifier.fillMaxWidth().background(Color.White)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(DesignTokens.NavigationDivider))
        Row(Modifier.fillMaxWidth().height(56.dp)) {
            listOf(PlaylistDestination.Home to AppIcon.HomeOutline, PlaylistDestination.Shorts to AppIcon.Shorts, PlaylistDestination.You to AppIcon.You).forEach { (destination, icon) ->
                Column(Modifier.weight(1f).fillMaxHeight().clickable(role = Role.Tab) { onAction(PlaylistAction.Destination(destination)) },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    AppIcon(icon)
                    AppText(destination.name, size = 11, line = 14)
                }
            }
        }
    }
}

@Composable
private fun PlaylistMessage(tag: String, message: String, action: @Composable () -> Unit = {}) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight)
            .padding(24.dp).testTag(tag), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            AppText(message, size = 16, line = 22, align = TextAlign.Center)
            action()
        }
    }
}

@Composable
private fun PlaylistSkeleton(width: Float) {
    val wide = PlaylistLayout.sideBySide(width)
    val coverWidth = if (wide) PlaylistLayout.coverWidth(width) else width - 32f
    val coverHeight = if (wide) PlaylistLayout.coverHeight(width) else coverWidth * 9f / 16f
    val headerHeight = if (wide) maxOf(coverHeight + 32f, 206f) else coverHeight + if (PlaylistLayout.largeText(width)) 214f else 216f
    val cover: @Composable () -> Unit = {
        Box(Modifier.size(coverWidth.dp, coverHeight.dp).background(DesignTokens.Ghost, RoundedCornerShape(8.dp)).testTag("playlist-loading-cover"))
    }
    val details: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.fillMaxWidth(0.8f).height(if (PlaylistLayout.largeText(width)) 36.dp else 24.dp).background(DesignTokens.Ghost))
            Box(Modifier.fillMaxWidth(0.55f).height(12.dp).background(DesignTokens.Ghost))
            Box(Modifier.fillMaxWidth(0.7f).height(12.dp).background(DesignTokens.Ghost))
            Box(Modifier.width(108.dp).height(40.dp).background(DesignTokens.Ghost, CircleShape))
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("playlist-loading")) {
        Box(Modifier.fillMaxWidth().height(headerHeight.dp).padding(16.dp)) {
            if (wide) Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                cover()
                Spacer(Modifier.width(16.dp))
                Box(Modifier.weight(1f)) { details() }
            } else Column {
                cover()
                Spacer(Modifier.height(16.dp))
                details()
            }
        }
        repeat(3) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Box(Modifier.size(160.dp, 90.dp).background(DesignTokens.Ghost, RoundedCornerShape(8.dp)))
                Box(Modifier.padding(start = 12.dp).weight(1f).height(16.dp).background(DesignTokens.Ghost))
            }
        }
    }
}
