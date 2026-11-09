package com.hhst.youtubelite.playlist.presentation
import com.hhst.youtubelite.ui.design.appDescription
import com.hhst.youtubelite.ui.design.labelResource
import com.hhst.youtubelite.ui.design.CollapsingAppBar
import com.hhst.youtubelite.ui.design.rememberAppBarCollapseState
import androidx.compose.ui.input.nestedscroll.nestedScroll

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.res.stringResource
import com.hhst.youtubelite.R

@Composable
fun PlaylistScreen(state: PlaylistUiState, onAction: (PlaylistAction) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val appBar = rememberAppBarCollapseState(state.playlistId, enabled = state.overlay == PlaylistOverlay.None && state.content is PlaylistContent.Ready)
    AppTheme {
        BoxWithConstraints(modifier.fillMaxSize().nestedScroll(appBar.connection).background(DesignTokens.Background).testTag("playlist-page")) {
            val width = maxWidth.value
            val viewport = rememberUpdatedState(width)
            val listState = rememberLazyListState()
            val headerScrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
            val loadedPage = (state.content as? PlaylistContent.Ready)?.page
            var lastPage by remember(state.playlistId) { mutableStateOf(loadedPage) }
            SideEffect { if (state.content != PlaylistContent.Loading) lastPage = loadedPage }
            val page = loadedPage ?: lastPage.takeIf { state.content == PlaylistContent.Loading }
            val emptyList = page?.entries?.isEmpty() == true
            val local = page?.summary?.local == true
            // Device-stored lists have no server artwork for the toolbar; an empty one
            // shows the neutral compact header instead of the accent hero.
            val neutralToolbar = page == null || (local && emptyList)
            Column(Modifier.fillMaxSize().then(if (state.overlay != PlaylistOverlay.None) Modifier.clearAndSetSemantics {} else Modifier)) {
                CollapsingAppBar(appBar) {
Row(Modifier.fillMaxWidth().height(48.dp).background(if (neutralToolbar) DesignTokens.Background else page?.summary?.accent?.let(::Color) ?: DesignTokens.Background)
                    .testTag("playlist-toolbar"), verticalAlignment = Alignment.CenterVertically) {
                    val color = if (neutralToolbar) DesignTokens.Ink else Color.White
                    AppIconButton(AppIcon.Back, stringResource(R.string.a11y_back), color = color, onClick = onBack)
                    Box(Modifier.weight(1f)) {
                        if (headerScrolled) AppText(page?.summary?.title.orEmpty(),
                            size = 20, line = 28, weight = FontWeight.Bold, color = color, maxLines = 1)
                    }
                    AppIconButton(AppIcon.Search, stringResource(R.string.a11y_search), color = color) { onAction(PlaylistAction.OpenSearch) }
                    AppIconButton(AppIcon.More, stringResource(R.string.a11y_more_options), color = color) { onAction(PlaylistAction.OpenMenu) }
                }

                }
                RefreshableContent(value = state, refreshing = state.content == PlaylistContent.Loading,
                    onRefresh = { onAction(PlaylistAction.Retry) }, modifier = Modifier.weight(1f).fillMaxWidth(),
                    enabled = state.overlay == PlaylistOverlay.None, contentKey = state.playlistId, tag = "playlist-refresh",
                    canPull = { !listState.canScrollBackward }) { state ->
                    when (val content = state.content) {
                        PlaylistContent.Loading -> PlaylistSkeleton(width)
                        PlaylistContent.Unavailable -> PlaylistMessage("playlist-unavailable", stringResource(R.string.ui_this_playlist_isn_t_available))
                        PlaylistContent.Failed -> if (state.verification != null) VerificationCard(
                            state.verification,
                            onVerify = { onAction(PlaylistAction.OpenVerification(state.verification!!.challengeUrl)) },
                            onDismiss = { onAction(PlaylistAction.DismissVerification) },
                            modifier = Modifier.testTag("playlist-verification"),
                        ) else PlaylistMessage("playlist-error", stringResource(R.string.ui_something_went_wrong)) {
                            AppText(stringResource(R.string.retry), Modifier.padding(top = 16.dp).clip(CircleShape).background(DesignTokens.Surface)
                                .clickable { onAction(PlaylistAction.Retry) }.padding(horizontal = 20.dp, vertical = 10.dp), weight = FontWeight.Medium)
                        }
                        is PlaylistContent.Ready -> {
                            val nearEnd by remember(content) { derivedStateOf {
                                (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) >= content.page.entries.size - 2
                            } }
                            androidx.compose.runtime.LaunchedEffect(nearEnd, content.page.nextKey, state.append) {
                                if (nearEnd && content.page.nextKey != null && state.append == com.hhst.youtubelite.home.domain.AppendState.Idle) {
                                    onAction(PlaylistAction.LoadNext)
                                }
                            }
                            LazyColumn(Modifier.fillMaxSize().testTag("playlist-list"), state = listState,
                                contentPadding = PaddingValues(bottom = 16.dp)) {
                                item(key = "header") {
                                    val w = viewport.value
                                    if (local && emptyList) PlaylistCompactHeader(content.page)
                                    else PlaylistHero(content.page, w, PlaylistLayout.sideBySide(w), state.descriptionExpanded, onAction)
                                }
                                if (content.page.entries.isEmpty() && content.page.nextKey == null) item {
                                    // Formal empty state: an honest message, no fake cover or author.
                                    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp).testTag("playlist-empty"),
                                        horizontalAlignment = Alignment.CenterHorizontally) {
                                        AppText(stringResource(R.string.ui_no_videos_in_this_playlist_yet), size = 16, line = 22, weight = FontWeight.Medium,
                                            align = TextAlign.Center)
                                        AppText(stringResource(R.string.ui_videos_you_add_will_appear_here), Modifier.padding(top = 6.dp),
                                            size = 14, line = 20, color = DesignTokens.Secondary, align = TextAlign.Center)
                                    }
                                }
                                items(content.page.entries, key = { it.id }, contentType = { "video" }) { entry -> PlaylistVideoRow(entry, onAction) }
                                item(key = "append") {
                                    PlaylistAppendRow(state, onAction)
                                }
                            }
                            state.verification?.let { challenge ->
                                VerificationBanner(challenge,
                                    onVerify = { onAction(PlaylistAction.OpenVerification(challenge.challengeUrl)) },
                                    onDismiss = { onAction(PlaylistAction.DismissVerification) },
                                    modifier = Modifier.align(Alignment.TopCenter).padding(horizontal = 12.dp, vertical = 8.dp)
                                        .testTag("playlist-verification-banner"))
                            }
                        }
                    }
                }
                PlaylistNavigation(state.signedIn, onAction)
            }
            when (state.overlay) {
                PlaylistOverlay.None -> Unit
                PlaylistOverlay.Search -> SearchPanel(state.query, if (state.query.isBlank()) emptyList() else listOf(state.query),
                    { onAction(PlaylistAction.QueryChanged(it)) }, { onAction(PlaylistAction.Search(it)) },
                    { onAction(PlaylistAction.CloseOverlay) }, { onAction(PlaylistAction.VoiceSearch) })
                PlaylistOverlay.Menu -> MenuBottomSheet(
                    listOf(MenuSheetItem(AppIcon.Shuffle, stringResource(R.string.playback_end_shuffle), "playlist-shuffle"),
                        MenuSheetItem(AppIcon.Download, stringResource(R.string.download), "playlist-download")),
                    { onAction(PlaylistAction.CloseOverlay) }, { onAction(if (it == 0) PlaylistAction.Shuffle else PlaylistAction.Download) },
                    tag = "playlist-menu", paneTitle = stringResource(R.string.ui_playlist_actions))
                is PlaylistOverlay.VideoMenu -> MenuBottomSheet(
                    PlaylistVideoAction.entries.map { MenuSheetItem(when(it) {
                        PlaylistVideoAction.WatchLater -> null
                        PlaylistVideoAction.Save -> AppIcon.Bookmark
                    }, stringResource(it.labelResource), "playlist-video-action-${it.name}", if (it == PlaylistVideoAction.WatchLater) 16.dp else null) },
                    { onAction(PlaylistAction.CloseOverlay) }, { onAction(PlaylistAction.VideoMenuAction(PlaylistVideoAction.entries[it])) },
                    tag = "playlist-video-menu", paneTitle = stringResource(R.string.ui_video_actions))
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
                    PlaylistButtons(onAction, stretch = false, local = page.summary.local, empty = page.entries.isEmpty())
                    Spacer(Modifier.height(8.dp))
                }
            }
        } else {
            Column(Modifier.padding(16.dp)) {
                PlaylistArtwork(page.summary.artwork, Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(8.dp)).clickable { onAction(PlaylistAction.PlayAll) }.testTag("playlist-hero-cover"))
                Spacer(Modifier.height(16.dp))
                PlaylistIdentity(page, width, descriptionExpanded, onAction)
                PlaylistButtons(onAction, stretch = width < 600f, local = page.summary.local, empty = page.entries.isEmpty())
            }
        }
    }
}

@Composable
private fun PlaylistCompactHeader(page: PlaylistPage) {
    Column(Modifier.fillMaxWidth().padding(16.dp).testTag("playlist-hero-local")) {
        AppText(page.summary.title, size = 24, line = 32, weight = FontWeight.Bold)
        page.summary.countLabel?.let {
            AppText(it, Modifier.padding(top = 4.dp), size = 12, line = 18, color = DesignTokens.Secondary)
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
    // A device-stored list has no channel to jump to and no artwork-as-avatar:
    // the owner renders as plain text, never a clickable identity row.
    val owner: @Composable () -> Unit = {
        if (page.summary.local) {
            Row(Modifier.heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
                AppText(page.summary.owner, size = text, line = line, color = Color.White, maxLines = 1)
            }
        } else {
            Row(Modifier.heightIn(min = 32.dp).clickable { onAction(PlaylistAction.OpenOwner) }, verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.padding(2.dp).size(24.dp).clip(CircleShape)) { PlaylistArtwork(page.summary.artwork, Modifier.fillMaxSize()) }
                Spacer(Modifier.width(6.dp))
                AppText(page.summary.owner, size = text, line = line, color = Color.White, maxLines = 1)
            }
        }
    }
    val metadata = listOfNotNull(stringResource(R.string.ui_playlist), page.summary.countLabel, page.summary.views.takeIf(String::isNotBlank))
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
    AppText(page.description, Modifier.padding(top = 12.dp).widthIn(max = 600.dp).animateContentSize(tween(180))
        .clickable(role = Role.Button) { onAction(PlaylistAction.ToggleDescription) }
        .appDescription(if (descriptionExpanded) R.string.ui_show_less_description else R.string.ui_show_full_description)
        .testTag("playlist-description"),
        size = text, line = line, color = Color.White.copy(alpha = 0.7f),
        maxLines = if (descriptionExpanded) Int.MAX_VALUE else 2)
}

@Composable
private fun PlaylistButtons(onAction: (PlaylistAction) -> Unit, stretch: Boolean, local: Boolean = false, empty: Boolean = false) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 12.dp).testTag("playlist-actions")) {
        val scroll = LocalDensity.current.fontScale > 1.3f && maxWidth < 400.dp
        Row(Modifier.fillMaxWidth().then(if (scroll) Modifier.horizontalScroll(rememberScrollState()) else Modifier),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!empty) {
                // Ink on Background pair follows the theme together: a fixed black on
                // the themed background went black-on-black in dark mode.
                Row(Modifier.then(if (stretch && !scroll) Modifier.weight(1f) else Modifier).heightIn(min = 40.dp)
                    .clip(CircleShape).background(DesignTokens.Background).clickable(role = Role.Button) { onAction(PlaylistAction.PlayAll) }
                    .padding(horizontal = 16.dp, vertical = 8.dp).testTag("playlist-play-all"),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    AppIcon(AppIcon.Play, color = DesignTokens.Ink)
                    Spacer(Modifier.width(6.dp))
                    AppText(stringResource(R.string.ui_play_all), weight = FontWeight.Medium, color = DesignTokens.Ink)
                }
            }
            // Saving/sharing a device-stored list has no server to talk to.
            if (!local) {
                AppIconButton(AppIcon.Bookmark, stringResource(R.string.a11y_save_playlist), diameter = 40.dp, background = Color.White.copy(alpha = 0.1f), color = Color.White) { onAction(PlaylistAction.Save) }
                AppIconButton(AppIcon.Share, stringResource(R.string.a11y_share_playlist), diameter = 40.dp, background = Color.White.copy(alpha = 0.1f), color = Color.White) { onAction(PlaylistAction.Share) }
            }
            AppIconButton(AppIcon.More, stringResource(R.string.a11y_more_playlist_actions), diameter = 40.dp, background = Color.White.copy(alpha = 0.1f), color = Color.White) { onAction(PlaylistAction.OpenMenu) }
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
                .clickable(role = Role.Button) { onAction(PlaylistAction.OpenVideo(entry.id)) }.testTag("playlist-entry-cover-${entry.id}")) {
                PlaylistArtwork(entry.artwork, Modifier.fillMaxSize())
                AppText(entry.duration, Modifier.align(Alignment.BottomEnd).padding(4.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp),
                    size = 12, line = 18, weight = FontWeight.Medium, color = Color.White)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).clickable(role = Role.Button) { onAction(PlaylistAction.OpenVideo(entry.id)) }) {
                AppText(entry.title, Modifier.fillMaxWidth().padding(end = 24.dp).testTag("playlist-entry-title-${entry.id}"), weight = FontWeight.Medium, maxLines = 2)
                AppText(entry.channel, Modifier.padding(top = 4.dp), size = 12, line = 18, color = DesignTokens.Secondary, maxLines = 1)
                val metadata = listOf(entry.views, entry.age).filter(String::isNotBlank).joinToString(" • ")
                if (metadata.isNotEmpty()) AppText(metadata, Modifier.padding(top = 4.dp),
                    size = 12, line = 18, color = DesignTokens.Secondary, maxLines = 1)
            }
        }
        AppIconButton(AppIcon.More, stringResource(R.string.a11y_more_actions_for, entry.title), diameter = 40.dp,
            modifier = Modifier.align(Alignment.TopEnd).offset(x = 10.dp, y = (-6).dp).testTag("playlist-entry-menu-${entry.id}")) {
            onAction(PlaylistAction.OpenVideoMenu(entry.id))
        }
    }
}

@Composable
private fun PlaylistAppendRow(state: PlaylistUiState, onAction: (PlaylistAction) -> Unit) {
    when {
        state.enumeration is PlaylistEnumeration.Running -> Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).testTag("playlist-enumeration"),
            verticalAlignment = Alignment.CenterVertically) {
            com.hhst.youtubelite.home.presentation.LoadingSpinner()
            AppText(stringResource(R.string.ui_playlist_loading_count, state.enumeration.loaded), Modifier.weight(1f).padding(start = 12.dp),
                size = 14, line = 20, color = DesignTokens.Secondary)
            AppText(stringResource(R.string.cancel), Modifier.clip(CircleShape).clickable(role = Role.Button) { onAction(PlaylistAction.CancelEnumeration) }
                .padding(horizontal = 12.dp, vertical = 6.dp), size = 14, line = 20, weight = FontWeight.Medium)
        }
        state.enumeration is PlaylistEnumeration.Failed -> Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).testTag("playlist-enumeration-failed"),
            verticalAlignment = Alignment.CenterVertically) {
            AppText(stringResource(R.string.ui_couldn_t_load_the_full_playlist), Modifier.weight(1f), size = 14, line = 20, color = DesignTokens.Secondary)
            AppText(stringResource(R.string.retry), Modifier.clip(CircleShape).clickable(role = Role.Button) { onAction(PlaylistAction.RetryEnumeration) }
                .padding(horizontal = 12.dp, vertical = 6.dp), size = 14, line = 20, weight = FontWeight.Medium)
        }
        state.append == com.hhst.youtubelite.home.domain.AppendState.Loading -> Row(
            Modifier.fillMaxWidth().padding(top = 12.dp).testTag("playlist-append-loading"),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            com.hhst.youtubelite.home.presentation.LoadingSpinner()
        }
        state.append == com.hhst.youtubelite.home.domain.AppendState.Failed -> Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).testTag("playlist-append-failed"),
            verticalAlignment = Alignment.CenterVertically) {
            AppText(stringResource(R.string.ui_couldn_t_load_more_entries), Modifier.weight(1f), size = 14, line = 20, color = DesignTokens.Secondary)
            AppText(stringResource(R.string.retry), Modifier.clip(CircleShape).clickable(role = Role.Button) { onAction(PlaylistAction.LoadNext) }
                .padding(horizontal = 12.dp, vertical = 6.dp), size = 14, line = 20, weight = FontWeight.Medium)
        }
        else -> Spacer(Modifier.height(1.dp))
    }
}

@Composable
private fun PlaylistNavigation(signedIn: Boolean, onAction: (PlaylistAction) -> Unit) {
    BrowseNavigationBar("playlist", signedIn) { destination ->
        onAction(PlaylistAction.Destination(PlaylistDestination.valueOf(destination.name)))
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
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("playlist-loading").clearAndSetSemantics {}) {
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
