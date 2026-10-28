package com.hhst.youtubelite.channel.presentation

import com.hhst.youtubelite.ui.components.mediaActions
import com.hhst.youtubelite.ui.components.MediaActionItem
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.semantics.selected
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hhst.youtubelite.R
import com.hhst.youtubelite.channel.domain.ChannelDestination
import com.hhst.youtubelite.channel.domain.ChannelAction
import com.hhst.youtubelite.channel.domain.ChannelContent
import com.hhst.youtubelite.channel.domain.ChannelLayout
import com.hhst.youtubelite.channel.domain.ChannelPage
import com.hhst.youtubelite.channel.domain.ChannelProfile
import com.hhst.youtubelite.channel.domain.ChannelTab
import com.hhst.youtubelite.channel.domain.ChannelUiState
import com.hhst.youtubelite.channel.domain.ChannelVideo
import com.hhst.youtubelite.channel.domain.ChannelOverlay
import com.hhst.youtubelite.channel.domain.ChannelPlaylistSort
import com.hhst.youtubelite.channel.domain.ChannelSection
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.hhst.youtubelite.ui.design.*
import com.hhst.youtubelite.ui.design.appDescription
import androidx.compose.ui.res.stringResource
import com.hhst.youtubelite.dearrow.rememberDeArrowTitle
import com.hhst.youtubelite.dearrow.VideoArtwork

private val SubscribeForeground = Color(0xFFF1F1F1)

private val ChannelLink = Color(0xFF065FD4)

@Composable
fun ChannelScreen(
    state: ChannelUiState,
    onAction: (ChannelAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AppTheme {
        BoxWithConstraints(modifier.fillMaxSize().background(DesignTokens.Background).testTag("channel-page")) {
            val width = maxWidth.value
            val height = maxHeight.value
            // Each tab owns a list state that survives tab switches and activity recreation.
            val homeState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
            val videosState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
            val searchState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
            val aboutScroll = rememberScrollState()
            val playlistsState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
            val shortsState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
            val liveState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
            val postsState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
            val postState = rememberSaveableStateHolder()
            fun listFor(tab: ChannelTab) = when (tab) {
                ChannelTab.Search -> searchState
                ChannelTab.Home -> homeState
                ChannelTab.Playlists -> playlistsState
                ChannelTab.Shorts -> shortsState
                ChannelTab.Live -> liveState
                ChannelTab.Posts -> postsState
                else -> videosState
            }
            var previousTab by rememberSaveable { mutableStateOf(state.selectedTab) }
            // A tab keeps its exact index/offset once visited; only a FIRST visit
            // inherits the outgoing tab's header collapse. The channel is fixed per
            // activity instance, so these sets never leak across channels.
            val visitedTabs = rememberSaveable(
                saver = androidx.compose.runtime.saveable.listSaver<MutableList<ChannelTab>, ChannelTab>(
                    save = { it.toList() }, restore = { it.toMutableList() }),
            ) { androidx.compose.runtime.mutableStateListOf<ChannelTab>() }
            LaunchedEffect(state.selectedTab, state.content is ChannelContent.Ready) {
                if (state.content !is ChannelContent.Ready || state.selectedTab == previousTab) return@LaunchedEffect
                val source = listFor(previousTab)
                val target = listFor(state.selectedTab)
                val firstVisit = state.selectedTab !in visitedTabs
                visitedTabs.add(previousTab)
                visitedTabs.add(state.selectedTab)
                previousTab = state.selectedTab
                if (firstVisit && target.firstVisibleItemIndex == 0) {
                    if (source.firstVisibleItemIndex > 0) target.scrollToItem(1)
                    else if (target.firstVisibleItemScrollOffset == 0) {
                        target.scrollToItem(0, source.firstVisibleItemScrollOffset)
                    }
                }
            }
            Column(Modifier.fillMaxSize().then(if (state.overlay != ChannelOverlay.None) Modifier.clearAndSetSemantics {} else Modifier)) {
                ChannelTopBar(state, onBack, onAction)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (val content = state.content) {
                        ChannelContent.Loading -> ChannelSkeleton(width)
                        ChannelContent.Failed -> if (state.verification != null) VerificationCard(
                            state.verification,
                            onVerify = { onAction(ChannelAction.OpenVerification(state.verification!!.challengeUrl)) },
                            onDismiss = { onAction(ChannelAction.DismissVerification) },
                            modifier = Modifier.testTag("channel-verification"),
                        ) else ChannelFailed(onAction)
                        is ChannelContent.Ready -> when (state.selectedTab) {
                            ChannelTab.Search -> ChannelSearchTab(content.page, width, height, searchState, state.searchQuery, state.submittedQuery, onAction,
                                state.searchResults, canPage = state.searchNextKey != null, append = state.searchAppend)
                            ChannelTab.Home -> ChannelHomeTab(content.page, width, homeState, onAction)
                            ChannelTab.Playlists -> ChannelPlaylistsTab(content.page, width, height, playlistsState, state.playlistSort, state.playlists, onAction,
                                canPage = state.playlistsNextKey != null, append = state.playlistsAppend)
                            ChannelTab.Shorts -> ChannelShortsTab(content.page, state.shorts, state.shortsSort, width, shortsState, onAction,
                                production = state.production, canPage = state.shortsNextKey != null, append = state.shortsAppend)
                            ChannelTab.Live -> ChannelLiveTab(content.page, state.live, state.liveSort, width, liveState, onAction,
                                canPage = state.liveNextKey != null, append = state.liveAppend)
                            ChannelTab.Posts -> postState.SaveableStateProvider(state.channelId) {
                                ChannelPostsTab(content.page, state.posts, width, postsState, state.expandedPosts, onAction,
                                    production = state.production, canPage = state.postsNextKey != null, append = state.postsAppend)
                            }
                            else -> ChannelVideosTab(content.page, width, videosState, onAction, state.videosAppend, state.videosTab)
                        }
                    }
                    state.verification?.let { challenge ->
                        VerificationBanner(challenge,
                            onVerify = { onAction(ChannelAction.OpenVerification(challenge.challengeUrl)) },
                            onDismiss = { onAction(ChannelAction.DismissVerification) },
                            modifier = Modifier.align(Alignment.TopCenter).padding(horizontal = 12.dp, vertical = 8.dp)
                                .testTag("channel-verification-banner"))
                    }
                }
                ChannelNavigationBar(onAction)
            }
            if (state.overlay == ChannelOverlay.About && state.content is ChannelContent.Ready)
                ChannelAboutPanel(state.content.page.profile, width, height, onAction, aboutScroll)
            if (state.overlay is ChannelOverlay.PlaylistMenu) com.hhst.youtubelite.playlist.presentation.PlaylistShareSheet(
                { onAction(ChannelAction.CloseOverlay) }, { onAction(ChannelAction.SharePlaylist) })
            ChannelCommunityMenuSheets(state.overlay, onAction)
        }
    }
}

@Composable
private fun ChannelPlaylistsTab(page: ChannelPage, width: Float, height: Float, state: LazyListState,
    sort: ChannelPlaylistSort, playlists: ChannelSection<List<com.hhst.youtubelite.playlist.domain.PlaylistSummary>>,
    onAction: (ChannelAction) -> Unit,
    canPage: Boolean = false, append: com.hhst.youtubelite.home.domain.AppendState = com.hhst.youtubelite.home.domain.AppendState.Idle) {
    val viewport = rememberUpdatedState(width)
    // "Last video added" only exists when the source carries update dates; otherwise the
    // control would present server order as a sort it cannot perform.
    val rows = (playlists as? ChannelSection.Ready)?.value.orEmpty()
    val canSortByUpdate = rows.any { it.updatedDays != null }
    androidx.compose.runtime.LaunchedEffect(state.layoutInfo.totalItemsCount, canPage, append) {
        val info = state.layoutInfo
        val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
        if (info.totalItemsCount > 0 && last >= info.totalItemsCount - 4 && canPage &&
            append == com.hhst.youtubelite.home.domain.AppendState.Idle) onAction(ChannelAction.LoadPlaylistsNext)
    }
    LazyColumn(Modifier.fillMaxSize().testTag("channel-playlists-list"), state = state, contentPadding = PaddingValues(bottom = 24.dp)) {
        item { ChannelHeader(page, viewport.value, onAction) }
        stickyHeader("channel-tabbar") { ChannelTabBar(ChannelTab.Playlists, onAction) }
        if (canSortByUpdate) item {
            val fieldHeight = maxOf(50.dp, with(androidx.compose.ui.platform.LocalDensity.current) { 14.sp.toDp() } + 28.dp)
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp).horizontalScroll(rememberScrollState())) {
                SelectField("channel-playlist-sort", sort, ChannelPlaylistSort.entries, { it.label }, 176f, fieldHeight) {
                    onAction(ChannelAction.SortPlaylists(it))
                }
                Spacer(Modifier.height(1.dp))
            }
            Spacer(Modifier.height(12.dp))
        }
        when (playlists) {
            is ChannelSection.Ready -> {
                if (rows.isEmpty()) item {
                    AppText("This channel has no playlists", Modifier.fillMaxWidth().padding(24.dp).testTag("channel-playlists-empty"), color = DesignTokens.Secondary)
                }
                val sorted = if (sort == ChannelPlaylistSort.LastVideo) rows.sortedBy { it.updatedDays ?: Int.MAX_VALUE } else rows
                items(sorted, key = { it.id }) { playlist ->
                    com.hhst.youtubelite.playlist.presentation.ChannelPlaylistRow(playlist,
                        { onAction(ChannelAction.OpenPlaylist(playlist.id)) }, { onAction(ChannelAction.OpenPlaylistMenu(playlist.id)) },
                        if (width >= 931f || width >= 550f && width <= height) 40f else 48f)
                }
            }
            ChannelSection.Empty -> item {
                AppText("This channel has no playlists", Modifier.fillMaxWidth().padding(24.dp).testTag("channel-playlists-empty"), color = DesignTokens.Secondary)
            }
            else -> item { ChannelTabStatus(ChannelTab.Playlists, playlists, onAction) }
        }
        if (canPage || append != com.hhst.youtubelite.home.domain.AppendState.Idle) item(key = "channel-playlists-append") {
            Box(Modifier.fillMaxWidth().padding(16.dp).testTag("channel-playlists-append"), contentAlignment = Alignment.Center) {
                when (append) {
                    com.hhst.youtubelite.home.domain.AppendState.Loading ->
                        AppText("Loading…", color = DesignTokens.Secondary)
                    com.hhst.youtubelite.home.domain.AppendState.Failed ->
                        AppText("Couldn't load more playlists. Tap to retry.",
                            Modifier.clip(RoundedCornerShape(8.dp)).clickable { onAction(ChannelAction.LoadPlaylistsNext) }.padding(8.dp),
                            color = DesignTokens.Secondary)
                    else -> Unit
                }
            }
        }
    }
}

/** A deferred tab's own loading/failure status; the header above stays interactive. */
@Composable
private fun ChannelTabStatus(tab: ChannelTab, section: ChannelSection<*>, onAction: (ChannelAction) -> Unit) {
    val key = tab.name.lowercase()
    Box(Modifier.fillMaxWidth().height(240.dp).testTag("channel-$key-${if (section is ChannelSection.Failed) "failed" else "loading"}"),
        contentAlignment = Alignment.Center) {
        if (section is ChannelSection.Failed) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                AppText("Couldn't load this tab.", color = DesignTokens.Secondary)
                Spacer(Modifier.height(12.dp))
                Box(Modifier.clip(RoundedCornerShape(8.dp)).background(DesignTokens.Surface)
                    .clickable(role = Role.Button) { onAction(ChannelAction.RetrySection(tab)) }
                    .testTag("channel-$key-retry").padding(horizontal = 16.dp, vertical = 8.dp)) {
                    AppText("Retry", weight = FontWeight.Medium)
                }
            }
        } else CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 2.5.dp, color = DesignTokens.Secondary)
    }
}

@Composable
private fun ChannelNavigationBar(onAction: (ChannelAction) -> Unit) {
    BrowseNavigationBar("channel") { destination ->
        onAction(ChannelAction.SelectDestination(ChannelDestination.valueOf(destination.name)))
    }
}

@Composable
private fun ChannelTopBar(state: ChannelUiState, onBack: () -> Unit, onAction: (ChannelAction) -> Unit) {
    val title = (state.content as? ChannelContent.Ready)?.page?.profile?.name ?: ""
    Row(Modifier.fillMaxWidth().height(ChannelLayout.ToolbarHeight.dp).background(DesignTokens.Background)
        .testTag("channel-topbar"), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(53.dp), contentAlignment = Alignment.CenterStart) {
            AppIconButton(AppIcon.Back, stringResource(R.string.a11y_close_channel_page), diameter = 48.dp) { onBack() }
        }
        AppText(title, Modifier.weight(1f).testTag("channel-title"), size = 20, line = 28,
            weight = FontWeight.Bold, maxLines = 1)
        AppIconButton(AppIcon.Search, stringResource(R.string.a11y_search_within_channel), diameter = 48.dp) {
            onAction(ChannelAction.ChannelSearch)
        }
        // The anonymous channel page offers no section menu; production hides the dead entry.
        if (!state.production) AppIconButton(AppIcon.More, stringResource(R.string.a11y_more_channel_options), diameter = 48.dp) {
            onAction(ChannelAction.OpenSectionMenu)
        }
    }
}

@Composable
internal fun ChannelTabBar(selected: ChannelTab, onAction: (ChannelAction) -> Unit) {
    val scroll = rememberScrollState()
    var viewport by remember { mutableIntStateOf(0) }
    val bounds = remember { mutableStateMapOf<ChannelTab, Pair<Int, Int>>() }
    LaunchedEffect(selected, bounds[selected], viewport) {
        val (left, right) = bounds[selected] ?: return@LaunchedEffect
        if (viewport == 0) return@LaunchedEffect
        when {
            left < scroll.value -> scroll.scrollTo(left)
            right > scroll.value + viewport -> scroll.scrollTo(right - viewport)
        }
    }
    Column(Modifier.fillMaxWidth().background(DesignTokens.Background).testTag("channel-tabbar")) {
        Row(Modifier.fillMaxWidth().height(ChannelLayout.TabBarHeight.dp)
            .onSizeChanged { viewport = it.width }
            .horizontalScroll(scroll).testTag("channel-tabs-row"), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(ChannelLayout.TabsStartInset.dp))
            ChannelTab.entries.forEach { tab ->
                val isSelected = tab == selected
                Box(Modifier.height(ChannelLayout.TabBarHeight.dp)
                    .widthIn(min = ChannelLayout.TabMinWidth.dp)
                    .onGloballyPositioned {
                        val left = it.positionInParent().x.toInt()
                        bounds[tab] = left to left + it.size.width
                    }.testTag("channel-tab-${tab.name}").semantics { this.selected = isSelected }
                    .clickable(role = Role.Tab) { onAction(ChannelAction.SelectTab(tab)) },
                    contentAlignment = Alignment.Center) {
                    AppText(tab.label, size = 16, line = 22, weight = FontWeight.Medium, maxLines = 1,
                        color = if (isSelected) DesignTokens.Ink else DesignTokens.Secondary)
                    if (isSelected) {
                        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth()
                            .height(ChannelLayout.TabUnderlineHeight.dp).background(DesignTokens.Ink))
                    }
                }
                Spacer(Modifier.width(ChannelLayout.TabsGap.dp))
            }
        }
        Box(Modifier.fillMaxWidth().height(ChannelLayout.TabBarBorder.dp).background(DesignTokens.Ghost))
    }
}

@Composable
private fun Artwork(asset: String, modifier: Modifier = Modifier) {
    com.hhst.youtubelite.ui.design.Artwork(asset, modifier)
}

@Composable
private fun ChannelHomeTab(
    page: ChannelPage,
    width: Float,
    state: LazyListState,
    onAction: (ChannelAction) -> Unit,
) {
    val shelves = page.homeShelves.ifEmpty {
        page.videos.filter { it.homeShelf }.takeIf { it.isNotEmpty() }
            ?.let { listOf(com.hhst.youtubelite.channel.domain.ChannelHomeShelf("", videos = it)) }.orEmpty()
    }
    val viewport = rememberUpdatedState(width)
    LazyColumn(
        state = state,
        modifier = Modifier.fillMaxSize().testTag("channel-home-list"),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item { ChannelHeader(page, viewport.value, onAction) }
        // The sampled page scrolls the tab row with the channel header and pins it once
        // the header is gone, so tabs stay reachable from every scroll depth.
        stickyHeader("channel-tabbar") { ChannelTabBar(ChannelTab.Home, onAction) }
        if (shelves.isEmpty()) {
            item { ChannelNoVideos() }
        } else shelves.forEachIndexed { index, shelf ->
            if (shelf.title.isNotBlank() || shelf.description.isNotBlank()) item("shelf-$index") {
                Column(Modifier.padding(horizontal = ChannelLayout.PageInset.dp)) {
                    Spacer(Modifier.height(12.dp))
                    if (shelf.title.isNotBlank()) AppText(shelf.title, size = 16, line = 22, weight = FontWeight.Bold, maxLines = 1)
                    if (shelf.description.isNotBlank()) AppText(shelf.description, Modifier.padding(top = 2.dp),
                        size = 12, line = 18, color = DesignTokens.Secondary, maxLines = 1)
                    Spacer(Modifier.height(8.dp))
                }
            }
            items(shelf.videos, key = { "$index:${it.id}" }) { video ->
                Box(Modifier.padding(horizontal = ChannelLayout.ListRowInset.dp)) {
                    ChannelVideoRow(video, ChannelLayout.menuSize(viewport.value).dp, onAction)
                }
            }
        }
    }
}

@Composable
private fun ChannelVideosTab(
    page: ChannelPage,
    width: Float,
    state: LazyListState,
    onAction: (ChannelAction) -> Unit,
    append: com.hhst.youtubelite.home.domain.AppendState = com.hhst.youtubelite.home.domain.AppendState.Idle,
    videosTab: ChannelSection<Unit> = ChannelSection.Ready(Unit),
) {
    val viewport = rememberUpdatedState(width)
    val canPage = page.videosNextKey != null
    val nearEnd by remember {
        androidx.compose.runtime.derivedStateOf {
            val info = state.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 4
        }
    }
    LaunchedEffect(nearEnd, canPage, append) {
        if (nearEnd && canPage && append == com.hhst.youtubelite.home.domain.AppendState.Idle) onAction(ChannelAction.LoadVideosNext)
    }
    LazyColumn(
        state = state,
        modifier = Modifier.fillMaxSize().testTag("channel-videos-list"),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item { ChannelHeader(page, viewport.value, onAction) }
        stickyHeader("channel-tabbar") { ChannelTabBar(ChannelTab.Videos, onAction) }
        if (videosTab !is ChannelSection.Ready) {
            // A deferred first read reports here; the header above is already usable.
            item { ChannelTabStatus(ChannelTab.Videos, videosTab, onAction) }
        } else if (page.videos.isEmpty()) {
            item { ChannelNoVideos() }
        } else {
            val columns = ChannelLayout.gridColumns(viewport.value)
            if (columns == 1) {
                items(page.videos, key = { it.id }) { video ->
                    Box(Modifier.padding(horizontal = ChannelLayout.ListRowInset.dp)) {
                        ChannelVideoRow(video, ChannelLayout.menuSize(viewport.value).dp, onAction)
                    }
                }
            } else {
                items(
                    page.videos.chunked(columns),
                    key = { row -> row.joinToString("-") { it.id } },
                ) { row ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = ChannelLayout.PageInset.dp),
                        horizontalArrangement = Arrangement.spacedBy(ChannelLayout.PageInset.dp)) {
                        row.forEach { video ->
                            ChannelVideoRow(video, ChannelLayout.menuSize(viewport.value).dp, onAction, ChannelLayout.cardWidth(viewport.value))
                        }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            if (canPage || append != com.hhst.youtubelite.home.domain.AppendState.Idle) item {
                Box(Modifier.fillMaxWidth().padding(16.dp).testTag("channel-videos-append"), contentAlignment = Alignment.Center) {
                    when (append) {
                        com.hhst.youtubelite.home.domain.AppendState.Loading ->
                            AppText("Loading…", color = DesignTokens.Secondary)
                        com.hhst.youtubelite.home.domain.AppendState.Failed ->
                            AppText("Couldn't load more videos. Tap to retry.",
                                Modifier.clip(RoundedCornerShape(8.dp)).clickable { onAction(ChannelAction.LoadVideosNext) }.padding(8.dp),
                                color = DesignTokens.Secondary)
                        else -> Unit
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelVideoRow(
    video: ChannelVideo,
    menuDiameter: Dp,
    onAction: (ChannelAction) -> Unit,
    cellWidth: Float? = null,
) {
    Row(Modifier
        .then(if (cellWidth != null) Modifier.width(cellWidth.dp) else Modifier.fillMaxWidth())
        .heightIn(min = ChannelLayout.VideoRowHeight.dp)
        .testTag("channel-video-${video.id}")) {
        Box(Modifier.padding(top = 12.dp).size(ChannelLayout.ThumbnailWidth.dp, ChannelLayout.ThumbnailHeight.dp)
            .clip(RoundedCornerShape(ChannelLayout.ThumbnailRadius.dp))
            .mediaActions(MediaActionItem(video.id, video.title, artwork = video.artwork)) { onAction(ChannelAction.OpenVideo(video.id)) }
            .appDescription(R.string.a11y_title_duration, video.title, video.duration)
            .testTag("channel-thumb-${video.id}")) {
            VideoArtwork(video.id, video.artwork, Modifier.fillMaxSize())
            if (video.duration.isNotBlank() || video.live) Box(Modifier.align(Alignment.BottomEnd).padding(ChannelLayout.DurationBadgeInset.dp)
                .background(if (video.live) DesignTokens.Red else Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                .padding(horizontal = 4.dp, vertical = 1.dp)) {
                AppText(if (video.live) "LIVE" else video.duration, size = 12, line = 18, weight = FontWeight.Medium, color = Color.White, maxLines = 1)
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f).padding(top = 14.dp)
            .mediaActions(MediaActionItem(video.id, video.title, artwork = video.artwork)) { onAction(ChannelAction.OpenVideo(video.id)) }) {
            AppText(rememberDeArrowTitle(video.id, video.title), Modifier.fillMaxWidth(), size = 14, line = 18, maxLines = 2, exactLineHeight = 17.sp)
            Spacer(Modifier.height(2.dp))
            if (video.age.isNotBlank() && video.views.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppText(video.views, size = 12, line = 16, maxLines = 1, color = DesignTokens.Secondary)
                    Spacer(Modifier.width(4.dp))
                    AppText("•", size = 12, line = 16, maxLines = 1, color = DesignTokens.Secondary)
                    Spacer(Modifier.width(4.dp))
                    AppText(video.age, size = 12, line = 16, maxLines = 1, color = DesignTokens.Secondary)
                }
            } else {
                AppText(listOf(video.views, video.age).filter { it.isNotBlank() }.joinToString(" • "), size = 12, line = 16, maxLines = 1, color = DesignTokens.Secondary)
            }
        }
        AppIconButton(AppIcon.More, stringResource(R.string.a11y_more_actions_for, video.title), diameter = menuDiameter) {
            onAction(ChannelAction.OpenItemMenu(video.id))
        }
    }
}



@Composable
private fun ChannelBanner(asset: String?, width: Float) {
    if (asset != null) {
        Box(Modifier.padding(horizontal = ChannelLayout.PageInset.dp)
            .fillMaxWidth()
            .height(((width - ChannelLayout.PageInset * 2f) / ChannelLayout.BannerAspect).dp)
            .clip(RoundedCornerShape(12.dp))
            .testTag("channel-banner")) { Artwork(asset, Modifier.fillMaxSize()) }
    }
}

@Composable
private fun ChannelIdentityAvatar(asset: String, size: Float) {
    Box(Modifier.size(size.dp).clip(CircleShape).testTag("channel-avatar")) {
        Artwork(asset, Modifier.fillMaxSize())
    }
}

@Composable
private fun BylineDot(fontSize: Int) {
    AppText("•", size = fontSize, line = ChannelLayout.identityLine(fontSize.toFloat()),
        color = DesignTokens.Secondary, maxLines = 1)
}

@Composable
private fun BylineIdentity(text: String, fontSize: Int) {
    AppText(text, size = fontSize, line = ChannelLayout.identityLine(fontSize.toFloat()),
        color = DesignTokens.Secondary, maxLines = 1)
}

@Composable
internal fun ChannelHeader(page: ChannelPage, width: Float, onAction: (ChannelAction) -> Unit) {
    val profile = page.profile
    val wide = ChannelLayout.wide(width)
    val compact = width < 600f
    Column(Modifier.fillMaxWidth().testTag("channel-header")) {
        Spacer(Modifier.height(ChannelLayout.HeaderTopGap.dp))
        ChannelBanner(profile.banner, width)
        Spacer(Modifier.height(ChannelLayout.HeaderTopGap.dp))
        Row(Modifier.padding(horizontal = ChannelLayout.PageInset.dp)) {
            Box(Modifier.padding(top = if (wide) (if (width >= 1148f) 18.dp else 15.dp) else 0.dp)) {
                ChannelIdentityAvatar(profile.avatar, ChannelLayout.avatarSize(width))
            }
            Spacer(Modifier.width(ChannelLayout.PageInset.dp))
            Column(Modifier.weight(1f).padding(top = if (!compact && !wide) 10.dp else 0.dp)) {
                ChannelIdentity(page, width)
                if (!compact) {
                    Spacer(Modifier.height(12.dp))
                    if (wide) {
                        ChannelDetails(profile, width, onAction)
                        Spacer(Modifier.height(ChannelLayout.linksSubscribeGap(width).dp))
                        ChannelSubscribe(true, onAction, profile.subscribed == true)
                    } else ChannelDescription(profile.description, width, onAction)
                }
            }
        }
        if (compact) {
            Spacer(Modifier.height(ChannelLayout.IdentityDescriptionGap.dp))
            Column(Modifier.padding(horizontal = ChannelLayout.PageInset.dp)) {
                ChannelDetails(profile, width, onAction)
                Spacer(Modifier.height(ChannelLayout.linksSubscribeGap(width).dp))
                ChannelSubscribe(false, onAction, profile.subscribed == true)
            }
        } else if (!wide) {
            Column(Modifier.padding(horizontal = ChannelLayout.PageInset.dp)) {
                if (profile.links.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    ChannelLinks(profile, width, onAction)
                }
                Spacer(Modifier.height(ChannelLayout.linksSubscribeGap(width).dp))
                ChannelSubscribe(false, onAction, profile.subscribed == true)
            }
        }
        Spacer(Modifier.height(ChannelLayout.subscribeTabBarGap(width).dp))
    }
}

@Composable
private fun ChannelIdentity(page: ChannelPage, width: Float) {
    val profile = page.profile
    val fontSize = ChannelLayout.identityFontSize(width).toInt()
    Column(Modifier.fillMaxWidth().testTag("channel-identity")) {
        ChannelName(profile.name, width)
        Spacer(Modifier.height(ChannelLayout.identityNameGap(width).dp))
        val stats = listOf(profile.subscribers, profile.videoCount).filter { it.isNotBlank() }
        if (ChannelLayout.mergesIdentityRows(width)) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                if (profile.handle.isNotBlank()) AppText(profile.handle, Modifier.testTag("channel-handle"), size = fontSize,
                    line = ChannelLayout.identityLine(fontSize.toFloat()), weight = FontWeight.Medium, maxLines = 1)
                stats.forEachIndexed { index, text ->
                    if (index > 0 || profile.handle.isNotBlank()) {
                        Spacer(Modifier.width(4.dp))
                        BylineDot(fontSize)
                        Spacer(Modifier.width(4.dp))
                    }
                    BylineIdentity(text, fontSize)
                }
            }
        } else {
            if (profile.handle.isNotBlank()) AppText(profile.handle, Modifier.fillMaxWidth().testTag("channel-handle"), size = fontSize,
                line = ChannelLayout.identityLine(fontSize.toFloat()), weight = FontWeight.Medium, maxLines = 1)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                stats.forEachIndexed { index, text ->
                    if (index > 0) {
                        Spacer(Modifier.width(4.dp))
                        BylineDot(fontSize)
                        Spacer(Modifier.width(4.dp))
                    }
                    BylineIdentity(text, fontSize)
                }
            }
        }
    }
}

@Composable
private fun ChannelName(name: String, width: Float) {
    val size = ChannelLayout.nameFontSize(width)
    // Barlow Semi Condensed SemiBold (SIL OFL 1.1) ships as a static cut: no
    // variation settings, and no width-axis squeeze carried over from the old font.
    val family = remember(size) { FontFamily(Font(R.font.barlow_semicondensed, FontWeight.SemiBold)) }
    BasicText(name, Modifier.fillMaxWidth().testTag("channel-name"),
        style = DesignTokens.text(size.toInt(), ChannelLayout.nameLineHeight(width).toInt(), FontWeight.SemiBold).copy(fontFamily = family),
        maxLines = 2, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun ChannelDetails(profile: ChannelProfile, width: Float, onAction: (ChannelAction) -> Unit) {
    Column(Modifier.widthIn(max = 600.dp).fillMaxWidth()) {
        ChannelDescription(profile.description, width, onAction)
        if (profile.links.isNotEmpty()) {
            Spacer(Modifier.height(ChannelLayout.descriptionLinksGap(width).dp))
            ChannelLinks(profile, width, onAction)
        }
    }
}

@Composable
private fun ChannelDescription(description: String, width: Float, onAction: (ChannelAction) -> Unit) {
    val fontSize = ChannelLayout.identityFontSize(width).toInt()
    val lineHeight = ChannelLayout.identityLine(fontSize.toFloat())
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = DesignTokens.text(fontSize, lineHeight, color = DesignTokens.Secondary)
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("channel-description")
        .clickable(role = Role.Button) { onAction(ChannelAction.OpenFullDescription) }) {
        val limit = ChannelLayout.descriptionLines(width)
        val ink = DesignTokens.Ink
        val text = remember(description, constraints.maxWidth, style, density.fontScale, ink) {
            fun summary(end: Int) = buildAnnotatedString {
                append(description.take(end).trimEnd())
                if (end > 0) append(" ")
                withStyle(SpanStyle(fontWeight = FontWeight.Medium, color = ink)) { append("...more") }
            }
            var low = 0
            var high = description.length
            while (low < high) {
                val middle = (low + high + 1) / 2
                val result = measurer.measure(summary(middle), style, maxLines = limit,
                    constraints = androidx.compose.ui.unit.Constraints(maxWidth = constraints.maxWidth))
                if (result.hasVisualOverflow) high = middle - 1 else low = middle
            }
            summary(low)
        }
        BasicText(text, style = style, maxLines = limit, overflow = TextOverflow.Clip)
    }
}

@Composable
private fun ChannelLinks(profile: ChannelProfile, width: Float, onAction: (ChannelAction) -> Unit) {
    val first = profile.links.firstOrNull() ?: return
    val fontSize = ChannelLayout.identityFontSize(width).toInt()
    val lineHeight = ChannelLayout.identityLine(fontSize.toFloat())
    Row(Modifier.fillMaxWidth().testTag("channel-links")
        .clickable(role = Role.Button) { onAction(ChannelAction.OpenFullDescription) },
        verticalAlignment = Alignment.CenterVertically) {
        AppText(first, Modifier.weight(1f, fill = false), size = fontSize, line = lineHeight, color = ChannelLink, maxLines = 1)
        val extra = profile.links.size - 1
        if (extra > 0) {
            Spacer(Modifier.width(4.dp))
            AppText("and $extra more link" + if (extra > 1) "s" else "",
                size = fontSize, line = lineHeight, weight = FontWeight.Medium, maxLines = 1)
        }
    }
}

@Composable
private fun ChannelSubscribe(wide: Boolean, onAction: (ChannelAction) -> Unit, subscribed: Boolean = false) {
    val foreground = if (DesignTokens.Background == Color.White) SubscribeForeground else DesignTokens.Background
    val subscribedBackground = if (DesignTokens.Background == Color.White) SubscribeForeground else DesignTokens.Surface
    Box(Modifier
        .then(if (wide) Modifier.wrapContentSize(align = Alignment.TopStart, unbounded = false) else Modifier.fillMaxWidth(1f))
        .height(ChannelLayout.SubscribeHeight.dp)
        .clip(RoundedCornerShape(ChannelLayout.SubscribeRadius.dp))
        .background(if (subscribed) subscribedBackground else DesignTokens.Ink)
        .clickable(role = Role.Button) { onAction(ChannelAction.Subscribe) }
        .testTag("channel-subscribe"),
        contentAlignment = Alignment.Center) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            AppText(if (subscribed) "Subscribed" else "Subscribe", size = 14, line = 18, weight = FontWeight.Medium,
                color = if (subscribed) DesignTokens.Ink else foreground)
        }
    }
}

@Composable
private fun ChannelNoVideos() {
    Column(Modifier.fillMaxWidth().testTag("channel-empty").padding(top = 48.dp, start = 24.dp, end = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        AppText("This channel has no videos.", color = DesignTokens.Secondary, align = TextAlign.Center)
    }
}

@Composable
private fun ChannelSkeleton(width: Float) {
    val bannerHeight = ((width - ChannelLayout.PageInset * 2f) / ChannelLayout.BannerAspect).dp
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("channel-skeleton").clearAndSetSemantics {}) {
        Spacer(Modifier.height(ChannelLayout.HeaderTopGap.dp))
        Box(Modifier.padding(horizontal = ChannelLayout.PageInset.dp)
            .fillMaxWidth().height(bannerHeight).background(DesignTokens.Ghost))
        Spacer(Modifier.height(ChannelLayout.HeaderTopGap.dp + 8.dp))
        Row(Modifier.padding(horizontal = ChannelLayout.PageInset.dp)) {
            Box(Modifier.size(ChannelLayout.avatarSize(width).dp).background(DesignTokens.Ghost, CircleShape))
            Spacer(Modifier.width(ChannelLayout.PageInset.dp))
            Column(Modifier.weight(1f).padding(top = 8.dp)) {
                Box(Modifier.widthIn(max = 180.dp).fillMaxWidth().height(24.dp).background(DesignTokens.Ghost, RoundedCornerShape(2.dp)))
                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth(0.85f).height(18.dp).background(DesignTokens.Ghost, RoundedCornerShape(2.dp)))
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth(0.7f).height(18.dp).background(DesignTokens.Ghost, RoundedCornerShape(2.dp)))
            }
        }
        repeat(3) { index ->
            Row(Modifier.height(ChannelLayout.VideoRowHeight.dp).padding(horizontal = ChannelLayout.ListRowInset.dp)) {
                Box(Modifier.padding(top = 12.dp)
                    .size(ChannelLayout.ThumbnailWidth.dp, ChannelLayout.ThumbnailHeight.dp)
                    .background(DesignTokens.Ghost, RoundedCornerShape(ChannelLayout.ThumbnailRadius.dp))
                    .testTag("skeleton-thumb-$index"))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f).padding(top = 14.dp)) {
                    Box(Modifier.fillMaxWidth(0.8f).height(16.dp)
                        .background(DesignTokens.Ghost, RoundedCornerShape(2.dp)))
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.width(120.dp).height(16.dp)
                        .background(DesignTokens.Ghost, RoundedCornerShape(2.dp)))
                }
            }
        }
    }
}

@Composable
private fun ChannelFailed(onAction: (ChannelAction) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .heightIn(min = maxHeight).padding(24.dp).testTag("channel-error"),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            AppText("Something went wrong", size = 20, line = 28, weight = FontWeight.Bold)
            AppText("Please try again.", Modifier.padding(top = 8.dp, bottom = 20.dp), color = DesignTokens.Secondary)
            Box(Modifier.clip(CircleShape).background(DesignTokens.Surface)
                .clickable(role = Role.Button) { onAction(ChannelAction.Retry) }
                .padding(horizontal = 16.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                AppText("Retry", weight = FontWeight.Medium)
            }
        }
    }
}
