package com.hhst.youtubelite.explore.presentation

import com.hhst.youtubelite.ui.design.labelResource
import com.hhst.youtubelite.ui.design.CollapsingAppBar
import com.hhst.youtubelite.ui.design.rememberAppBarCollapseState
import androidx.compose.ui.input.nestedscroll.nestedScroll

import com.hhst.youtubelite.ui.design.RefreshableContent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items as listItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hhst.youtubelite.explore.domain.ExploreAction
import com.hhst.youtubelite.explore.domain.ExploreCategory
import com.hhst.youtubelite.explore.domain.ExploreContent
import com.hhst.youtubelite.explore.domain.ExploreSection
import com.hhst.youtubelite.explore.domain.ExploreUiState
import com.hhst.youtubelite.home.domain.AppendState
import com.hhst.youtubelite.home.domain.FeedItem
import com.hhst.youtubelite.watch.domain.toProvisional
import com.hhst.youtubelite.home.presentation.PillButton
import com.hhst.youtubelite.library.presentation.LibraryError
import com.hhst.youtubelite.ui.design.AppIconButton
import com.hhst.youtubelite.ui.design.AppIcon
import com.hhst.youtubelite.ui.design.AppText
import com.hhst.youtubelite.ui.design.DesignTokens
import com.hhst.youtubelite.ui.design.VerificationCard
import com.hhst.youtubelite.ui.design.appDescription
import androidx.compose.ui.res.stringResource
import com.hhst.youtubelite.R
import com.hhst.youtubelite.dearrow.rememberDeArrowTitle
import com.hhst.youtubelite.dearrow.VideoArtwork

@Composable
fun ExploreScreen(state: ExploreUiState, onAction: (ExploreAction) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val appBar = rememberAppBarCollapseState(state.category, enabled = state.content is ExploreContent.Ready)
    BoxWithConstraints(modifier.fillMaxSize().background(DesignTokens.Background).testTag("explore-screen").nestedScroll(appBar.connection)) {
        val width = maxWidth.value
        // Scroll positions are retained per category (and per shelf) across tab switches
        // AND configuration rebuilds (saved via savers, not plain remember).
        val columnStates = rememberSaveable(saver = columnStatesSaver) { mutableStateMapOf<ExploreCategory, LazyListState>() }
        val gridStates = rememberSaveable(saver = gridStatesSaver) { mutableStateMapOf<ExploreCategory, LazyGridState>() }
        val shelfStates = rememberSaveable(saver = shelfStatesSaver) { mutableStateMapOf<String, LazyListState>() }
        Column(Modifier.fillMaxSize()) {
            CollapsingAppBar(appBar) {
Row(Modifier.fillMaxWidth().heightIn(min = DesignTokens.TopBarHeight).testTag("explore-topbar"), verticalAlignment = Alignment.CenterVertically) {
                AppIconButton(AppIcon.Back, stringResource(R.string.a11y_back), diameter = 48.dp) { onBack() }
                AppText(stringResource(R.string.a11y_explore), Modifier.padding(end = 16.dp), size = 20, line = 28, weight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
            }

            }
            CategoryChips(state.category, onAction)
            RefreshableContent(value = state, refreshing = state.content == ExploreContent.Loading,
                    onRefresh = { onAction(ExploreAction.Retry) }, modifier = Modifier.weight(1f).fillMaxWidth(),
                    enabled = true, contentKey = state.category, tag = "explore-refresh",
                    canPull = { if ((state.content as? ExploreContent.Ready)?.page?.sections?.isNotEmpty() == true)
                        columnStates[state.category]?.canScrollBackward != true
                        else gridStates[state.category]?.canScrollBackward != true }) { state ->
                when (val content = state.content) {
                    ExploreContent.Loading -> ExploreSkeleton(width)
                    ExploreContent.Failed -> if (state.verification != null) VerificationCard(
                        state.verification,
                        onVerify = { onAction(ExploreAction.OpenVerification(state.verification!!.challengeUrl)) },
                        onDismiss = { onAction(ExploreAction.DismissVerification) },
                        modifier = Modifier.testTag("explore-verification"),
                    ) else LibraryError("explore-error") { onAction(ExploreAction.Retry) }
                    is ExploreContent.Ready -> if (content.page.sections.isNotEmpty())
                        SectionedContainer(state.category, content, width,
                            columnStates.getOrPut(state.category) { LazyListState() },
                            { index -> shelfStates.getOrPut("${state.category.name}:$index") { LazyListState() } },
                            onAction)
                    else CategoryContainer(content, width,
                        gridStates.getOrPut(state.category) { LazyGridState() }, onAction)
                }
            }
        }
    }
}

/** Lazy scroll states are not parcelable; their positions are. */
private val columnStatesSaver: Saver<androidx.compose.runtime.snapshots.SnapshotStateMap<ExploreCategory, LazyListState>, Any> = mapSaver(
    save = { states -> states.entries.associate { (category, state) ->
        category.name to listOf(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset) } },
    restore = { saved -> mutableStateMapOf<ExploreCategory, LazyListState>().apply {
        saved.forEach { (name, pos) -> val p = pos as List<Int>
            put(ExploreCategory.valueOf(name), LazyListState(p[0], p[1])) } } },
)

private val gridStatesSaver: Saver<androidx.compose.runtime.snapshots.SnapshotStateMap<ExploreCategory, LazyGridState>, Any> = mapSaver(
    save = { states -> states.entries.associate { (category, state) ->
        category.name to listOf(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset) } },
    restore = { saved -> mutableStateMapOf<ExploreCategory, LazyGridState>().apply {
        saved.forEach { (name, pos) -> val p = pos as List<Int>
            put(ExploreCategory.valueOf(name), LazyGridState(p[0], p[1])) } } },
)

private val shelfStatesSaver: Saver<androidx.compose.runtime.snapshots.SnapshotStateMap<String, LazyListState>, Any> = mapSaver(
    save = { states -> states.entries.associate { (key, state) ->
        key to listOf(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset) } },
    restore = { saved -> mutableStateMapOf<String, LazyListState>().apply {
        saved.forEach { (key, pos) -> val p = pos as List<Int>
            put(key, LazyListState(p[0], p[1])) } } },
)

/**
 * First-load placeholder matching the page geometry: shelved rounded cards on a
 * lazy row (a plain Row squeezes trailing fixed-width cards to zero on narrow
 * screens). No duplicate chip row — the real category chips render above.
 */
@Composable
private fun ExploreSkeleton(width: Float) {
    val cardWidth = (width * 0.8f).coerceAtMost(300f).dp
    Column(Modifier.fillMaxSize().testTag("explore-skeleton").clearAndSetSemantics {}) {
        repeat(2) {
            Box(Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp).width(160.dp).height(20.dp)
                .clip(RoundedCornerShape(10.dp)).background(DesignTokens.Ghost))
            LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(horizontal = 16.dp)) {
                items(3) {
                    Column {
                        Box(Modifier.width(cardWidth).aspectRatio(16f / 9).clip(RoundedCornerShape(12.dp)).background(DesignTokens.Ghost))
                        Row(Modifier.padding(top = 8.dp)) {
                            Box(Modifier.size(36.dp).clip(CircleShape).background(DesignTokens.Ghost))
                            Spacer(Modifier.width(8.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Box(Modifier.width(180.dp).height(14.dp).clip(RoundedCornerShape(7.dp)).background(DesignTokens.Ghost))
                                Box(Modifier.width(120.dp).height(12.dp).clip(RoundedCornerShape(6.dp)).background(DesignTokens.Ghost))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryChips(selected: ExploreCategory, onAction: (ExploreAction) -> Unit) {
    LazyRow(Modifier.fillMaxWidth().height(48.dp).testTag("explore-chips"), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(horizontal = 16.dp)) {
        listItems(ExploreCategory.entries) { category ->
            Box(Modifier.clip(RoundedCornerShape(8.dp))
                .background(if (category == selected) DesignTokens.Ink else DesignTokens.Surface)
                .selectable(category == selected, role = Role.Tab) { onAction(ExploreAction.SelectCategory(category)) }
                .testTag("explore-chip-${category.name}")
                .padding(horizontal = 12.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
                AppText(stringResource(category.labelResource), size = 14, line = 20, weight = FontWeight.Medium,
                    color = if (category == selected) DesignTokens.Background else DesignTokens.Ink)
            }
        }
    }
}

@Composable
private fun SectionedContainer(category: ExploreCategory, content: ExploreContent.Ready, width: Float,
    columnState: LazyListState, shelfState: (Int) -> LazyListState, onAction: (ExploreAction) -> Unit) {
    // Carousel cards track the window: at a 240dp split they stay inside the viewport.
    val cardWidth = (width * 0.8f).coerceAtMost(300f).dp
    LazyColumn(
        Modifier.fillMaxSize().testTag("explore-sections"),
        state = columnState,
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        content.page.sections.forEachIndexed { index, section ->
            if (section.videos.isEmpty() && section.playlists.isEmpty()) {
                if (section.nextKey != null || section.append != AppendState.Idle) {
                    item(key = "pending-$index", contentType = "pending-shelf") {
                        ShelfTail(index, section.append, section.nextKey, section.automaticPagingPaused,
                            Modifier.fillMaxWidth().height(48.dp), onAction)
                    }
                }
                return@forEachIndexed
            }
            item(key = "section-$index", contentType = "section-header") {
                Column(Modifier.fillMaxWidth().padding(start = 16.dp, top = 16.dp, bottom = 8.dp).testTag("explore-section-${index}-title")) {
                    AppText(section.title ?: stringResource(R.string.a11y_explore), size = 18, line = 24, weight = FontWeight.Bold)
                }
            }
            item(key = "carousel-$index", contentType = "carousel") {
                LazyRow(
                    Modifier.fillMaxWidth().testTag("explore-carousel-$index"),
                    state = shelfState(index),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                ) {
                    listItems(section.videos, key = { it.id }, contentType = { "video" }) { item ->
                        Box(Modifier.width(cardWidth)) {
                            ExploreVideoCard(item, compact = false, onAction = onAction)
                        }
                    }
                    listItems(section.playlists, key = { "playlist-${it.id}" }, contentType = { "playlist" }) { playlist ->
                        ExplorePlaylistCard(playlist, Modifier.width(cardWidth), onAction)
                    }
                    if (section.nextKey != null || section.append != AppendState.Idle) {
                        item(key = "shelf-more-$index", contentType = "shelf-tail") {
                            ShelfTail(index, section.append, section.nextKey, section.automaticPagingPaused,
                                Modifier.width(96.dp).height(cardWidth * 9f / 16f), onAction)
                        }
                    }
                }
            }
        }
        item(key = "page-tail", contentType = "page-tail") {
            PageTail(content.append, content.page.nextKey, content.automaticPagingPaused, onAction)
        }
    }
}

@Composable
private fun ShelfTail(index: Int, append: AppendState, nextKey: String?, paused: Boolean,
    modifier: Modifier, onAction: (ExploreAction) -> Unit) {
    Box(modifier.testTag("explore-shelf-$index-tail"), contentAlignment = Alignment.Center) {
        when (append) {
            AppendState.Loading -> CircularProgressIndicator(Modifier.size(24.dp), color = DesignTokens.Secondary, strokeWidth = 2.dp)
            AppendState.Failed -> Box(Modifier.clip(RoundedCornerShape(8.dp)).background(DesignTokens.Surface)
                .clickable(role = Role.Button) { onAction(ExploreAction.LoadShelf(index)) }
                .testTag("explore-shelf-$index-retry").padding(horizontal = 16.dp, vertical = 10.dp)) {
                AppText(stringResource(R.string.retry), size = 12, line = 16, weight = FontWeight.Medium)
            }
            AppendState.Idle -> {
                if (paused) Box(Modifier.testTag("explore-shelf-$index-load-more")) {
                    PillButton(stringResource(R.string.load_more)) { onAction(ExploreAction.LoadShelf(index)) }
                } else {
                    LaunchedEffect(index, nextKey) { onAction(ExploreAction.AutomaticLoadShelf(index)) }
                    CircularProgressIndicator(Modifier.size(24.dp), color = DesignTokens.Secondary, strokeWidth = 2.dp)
                }
            }
        }
    }
}

@Composable
private fun PageTail(append: AppendState, nextKey: String?, paused: Boolean, onAction: (ExploreAction) -> Unit) {
    if (nextKey == null && append == AppendState.Idle) return
    Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
        when (append) {
            AppendState.Loading -> CircularProgressIndicator(Modifier.size(24.dp).testTag("explore-tail-loading"),
                color = DesignTokens.Secondary, strokeWidth = 2.dp)
            AppendState.Failed -> Box(Modifier.testTag("explore-tail-retry")) {
                PillButton(stringResource(R.string.retry)) { onAction(ExploreAction.LoadMore) }
            }
            AppendState.Idle -> if (paused) Box(Modifier.testTag("explore-load-more")) {
                PillButton(stringResource(R.string.load_more)) { onAction(ExploreAction.LoadMore) }
            } else LaunchedEffect(nextKey) { onAction(ExploreAction.AutomaticLoadMore) }
        }
    }
}

@Composable
private fun CategoryContainer(content: ExploreContent.Ready, width: Float, gridState: LazyGridState, onAction: (ExploreAction) -> Unit) {
    val columns = when {
        width < 528f -> 1
        width < 994f -> 2
        width < 1320f -> 3
        else -> 4
    }
    val compact = width < 528f
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = Modifier.fillMaxSize().testTag("explore-list"),
        state = gridState,
        contentPadding = PaddingValues(
            start = if (compact) 0.dp else 16.dp,
            end = if (compact) 0.dp else 16.dp,
            bottom = 24.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(DesignTokens.GridGap),
    ) {
        gridItems(content.page.videos, key = { it.id }, contentType = { "video" }) { item ->
            Box(Modifier.padding(bottom = if (compact) 0.dp else 16.dp)) {
                ExploreVideoCard(item, compact, onAction)
            }
        }
        gridItems(content.page.playlists, key = { "playlist-${it.id}" }, contentType = { "playlist" }) { playlist ->
            ExplorePlaylistCard(playlist, Modifier.padding(horizontal = if (compact) 12.dp else 0.dp, vertical = 8.dp), onAction)
        }
        item(key = "page-tail", span = { GridItemSpan(maxLineSpan) }, contentType = "page-tail") {
            PageTail(content.append, content.page.nextKey, content.automaticPagingPaused, onAction)
        }
    }
}

@Composable
private fun ExplorePlaylistCard(playlist: com.hhst.youtubelite.playlist.domain.PlaylistSummary,
    modifier: Modifier, onAction: (ExploreAction) -> Unit) {
    val open = { onAction(ExploreAction.OpenPlaylist(playlist.id)) }
    Column(modifier.testTag("explore-playlist-${playlist.id}")) {
        com.hhst.youtubelite.playlist.presentation.PlaylistCover(playlist,
            Modifier.fillMaxWidth().aspectRatio(16f / 9f), open)
        AppText(playlist.title, Modifier.fillMaxWidth().clickable(onClick = open).padding(top = 8.dp),
            size = 16, line = 22, weight = FontWeight.Medium, maxLines = 2)
        if (playlist.owner.isNotBlank()) AppText(playlist.owner, Modifier.padding(top = 2.dp),
            size = 12, line = 18, color = DesignTokens.Secondary, maxLines = 1)
    }
}

@Composable
private fun ExploreVideoCard(item: FeedItem.Video, compact: Boolean, onAction: (ExploreAction) -> Unit) {
    val badge = if (item.live) "LIVE" else item.duration
    Column(Modifier.fillMaxWidth().testTag("explore-item-${item.id}")) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9).clip(RoundedCornerShape(if (compact) 0.dp else 12.dp))
            .clickable(role = Role.Button) {
                com.hhst.youtubelite.watch.domain.WatchProvisionalStore.put(item.toProvisional())
                onAction(ExploreAction.OpenVideo(item.id))
            }
            .semantics { contentDescription = listOf(item.title, badge).filter(String::isNotBlank).joinToString(", ") }) {
            VideoArtwork(item.id, item.artwork, Modifier.fillMaxSize())
            if (badge.isNotBlank()) {
                Box(Modifier.align(Alignment.BottomEnd).padding(8.dp)
                    .background(if (item.live) DesignTokens.Red else Color.Black.copy(alpha = 0.75f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp)) {
                    AppText(badge, size = 12, line = 16, weight = FontWeight.Medium, color = Color.White)
                }
            }
        }
        Row(Modifier.padding(start = if (compact) 12.dp else 0.dp, top = 8.dp, end = if (compact) 12.dp else 16.dp), verticalAlignment = Alignment.Top) {
            // The author avatar, never the video still; a blank channel id goes nowhere.
            // A missing avatar shows a stable letter placeholder, not perpetual loading.
            Box(Modifier.size(36.dp).clip(CircleShape)
                .then(if (item.channelId.isNotBlank()) Modifier.clickable(role = Role.Button) {
                    onAction(ExploreAction.OpenChannel(item.channelId))
                }.appDescription(R.string.a11y_go_to_channel, item.author) else Modifier)) {
                val avatar = item.avatar
                if (avatar.isNullOrBlank()) {
                    Box(Modifier.fillMaxSize().background(DesignTokens.Surface), contentAlignment = Alignment.Center) {
                        AppText(item.author.trim().take(1).uppercase(), size = 14, line = 18,
                            weight = FontWeight.Medium, color = DesignTokens.Secondary)
                    }
                } else Artwork(avatar, Modifier.fillMaxSize())
            }
            Spacer(Modifier.width(8.dp))
            Column {
                AppText(rememberDeArrowTitle(item.id, item.title), size = 16, line = 22, weight = FontWeight.Medium, maxLines = 2)
                AppText(listOf(item.author, item.metadata).filter(String::isNotBlank).joinToString(" · "),
                    Modifier.padding(top = 2.dp), size = 12, line = 18,
                    color = DesignTokens.Secondary, maxLines = 1)
            }
        }
    }
}

@Composable
private fun Artwork(asset: String, modifier: Modifier) {
    com.hhst.youtubelite.ui.design.Artwork(asset, modifier)
}
