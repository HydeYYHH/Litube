package com.hhst.youtubelite.home.presentation

import com.hhst.youtubelite.ui.design.reasonResources
import com.hhst.youtubelite.ui.design.labelResource
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.animation.core.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import com.hhst.youtubelite.ui.design.*
import com.hhst.youtubelite.R
import com.hhst.youtubelite.home.domain.*
import com.hhst.youtubelite.playlist.data.FixturePlaylistRepository
import com.hhst.youtubelite.feedback.presentation.FeedbackReason
import com.hhst.youtubelite.feedback.presentation.FeedbackSheet
import com.hhst.youtubelite.saves.domain.NewPlaylistDraft
import com.hhst.youtubelite.saves.domain.SavePlaylist
import com.hhst.youtubelite.saves.presentation.NewPlaylistSheet
import com.hhst.youtubelite.saves.presentation.SaveSheetLive
import com.hhst.youtubelite.saves.presentation.SaveToPlaylistSheet
import com.hhst.youtubelite.ui.design.appDescription
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.delay
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle

/** Stateless rendering boundary; local state is limited to saved scroll/focus state. */
// The warm collector belongs to LaunchedEffect and is cancelled on key changes
// or disposal; the lint follows HomeActivity's deferred setup through onResume.
@android.annotation.SuppressLint("RepeatOnLifecycleWrongUsage")
@Composable
fun HomeScreen(state: HomeUiState, onAction: (HomeAction) -> Unit, modifier: Modifier = Modifier,
    searchRows: com.hhst.youtubelite.ui.components.SearchRows? = null,
    onWarmCandidate: ((String) -> Unit)? = null,
    postInteractions: com.hhst.youtubelite.post.presentation.PostCardInteractions? = null) {
    AppTheme {
        val hideShorts = state.hideShorts || LocalHideShorts.current
        BoxWithConstraints(modifier.fillMaxSize().background(DesignTokens.Background).testTag("home-screen")) {
            val width = maxWidth.value
            val currentWidth by rememberUpdatedState(width)
            val chipExtraHeight = with(LocalDensity.current) { (20.sp.toDp() - 20.dp).coerceAtLeast(0.dp) }
            val feedTopInset = (if (width < 528f) 98.dp else 110.dp) + chipExtraHeight
            val chromeTravel = 108f + chipExtraHeight.value
            val grid = rememberLazyGridState()
            var chromeHidden by rememberSaveable { mutableStateOf(false) }
            var displayedFilter by rememberSaveable { mutableStateOf(state.selectedChip) }
            var displayedSession by rememberSaveable { mutableStateOf(state.sessionGeneration) }
            LaunchedEffect(state.selectedChip, state.sessionGeneration) {
                if (displayedFilter != state.selectedChip || displayedSession != null && displayedSession != state.sessionGeneration) {
                    grid.scrollToItem(0)
                    chromeHidden = false
                }
                displayedFilter = state.selectedChip
                displayedSession = state.sessionGeneration
            }
            val chromeOffset by animateFloatAsState(if (chromeHidden) 1f else 0f,
                AppMotion.scrollResponse(LocalMotionScale.current), label = "header")
            val density = LocalDensity.current.density
            val feed = state.content as? HomeContent.Feed
            val visibleItems = remember(feed?.page?.items, hideShorts) {
                feed?.page?.items.orEmpty().filterNot { hideShorts && (it is FeedItem.Shorts || it is FeedItem.Video && it.short) }
            }
            val warmCandidate by rememberUpdatedState(onWarmCandidate)
            val homeLifecycle = LocalLifecycleOwner.current.lifecycle
            LaunchedEffect(grid, visibleItems, state.overlay == HomeOverlay.None, onWarmCandidate != null, homeLifecycle) {
                if (onWarmCandidate == null || state.overlay != HomeOverlay.None) return@LaunchedEffect
                val candidates = visibleItems.associateBy { it.id }
                homeLifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                    snapshotFlow {
                        if (grid.isScrollInProgress) null else grid.layoutInfo.let { layout ->
                            layout.visibleItemsInfo.firstNotNullOfOrNull { visible ->
                                val shown = minOf(visible.offset.y + visible.size.height, layout.viewportEndOffset) -
                                    maxOf(visible.offset.y, layout.viewportStartOffset)
                                if (shown * 2 < visible.size.height) null else when (val item = candidates[visible.key]) {
                                    is FeedItem.Video -> item.takeUnless { it.live }?.id
                                    is FeedItem.Shorts -> item.videos.firstOrNull { !it.live }?.id
                                    else -> null
                                }
                            }
                        }
                    }.collectLatest { id ->
                        if (id != null) { delay(600); warmCandidate?.invoke(id) }
                    }
                }
            }
            val catalogMode = state.chips == HomeCatalog.filters
            val userRefreshing = when (val visible = state.content) {
                is HomeContent.Feed -> visible.refreshing && visible.userRefresh
                is HomeContent.Empty -> visible.refreshing && visible.userRefresh
                else -> false
            }
            val pullMotion = rememberPullRefreshState(userRefreshing,
                enabled = state.overlay == HomeOverlay.None && state.content != HomeContent.Loading,
                canPull = { feed == null || !grid.canScrollBackward }) { onAction(HomeAction.Refresh) }
            val pull = pullMotion.offset
            val chromeConnection = remember(pullMotion) { object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (source == NestedScrollSource.UserInput && available.y > 0f) chromeHidden = false
                    return Offset.Zero
                }

                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    if (source == NestedScrollSource.UserInput && consumed.y < 0f && grid.canScrollBackward && pullMotion.offset == 0f)
                        chromeHidden = true
                    return Offset.Zero
                }
            } }
            LaunchedEffect(grid, feed != null) {
                if (feed == null) return@LaunchedEffect
                snapshotFlow { grid.firstVisibleItemIndex to grid.firstVisibleItemScrollOffset }.collect { current ->
                    if (current.first == 0 && current.second == 0) chromeHidden = false
                }
            }
            val nearEnd by remember(grid) { derivedStateOf {
                val layout = grid.layoutInfo
                layout.totalItemsCount > 0 && (layout.visibleItemsInfo.lastOrNull()?.index ?: -1) >= layout.totalItemsCount - 3
            } }
            LaunchedEffect(nearEnd, feed?.page?.nextKey, feed?.append, feed?.refreshing) {
                if (nearEnd && feed?.page?.nextKey != null && feed.append == AppendState.Idle && !feed.refreshing && !feed.automaticPagingPaused)
                    onAction(HomeAction.AutomaticLoadNext)
            }
            val searchOpen = state.overlay == HomeOverlay.Search
            Column(Modifier.fillMaxSize().then(if (state.overlay != HomeOverlay.None) Modifier.clearAndSetSemantics {} else Modifier)) {
                if (feed == null && state.content != HomeContent.Loading) {
                    HomeTopBar(onAction)
                    if (catalogMode) ChipStrip(state.selectedChip, state.chips, true, onAction)
                }
                Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().nestedScroll(chromeConnection)
                    .pullRefreshGesture(pullMotion)) {
                    when (val content = state.content) {
                        is HomeContent.Empty -> Box(Modifier.fillMaxSize().graphicsLayer { translationY = pull }) {
                            EmptyHome(content, showTools = !searchOpen && !catalogMode, wide = width >= 528,
                                onAction = onAction, showMessage = !catalogMode)
                        }
                        HomeContent.Failed -> Box(Modifier.fillMaxSize().graphicsLayer { translationY = pull }) {
                            if (state.verification != null) VerificationCard(
                                state.verification,
                                onVerify = { onAction(HomeAction.Navigate(HomeNavigationRequest.OpenVerification(state.verification!!.challengeUrl))) },
                                onDismiss = { onAction(HomeAction.DismissVerification) },
                                modifier = Modifier.testTag("home-verification"),
                            ) else ErrorHome(onAction)
                        }
                        HomeContent.Loading -> Box(Modifier.fillMaxSize()) {
                            HomeSkeleton(width, feedTopInset)
                            // Keep navigation and card anchors in place while the first page loads.
                            Box(Modifier.offset(y = DesignTokens.TopBarHeight).background(DesignTokens.Background)) {
                                ChipStrip(state.selectedChip, state.chips, catalogMode, onAction)
                            }
                            Box(Modifier.background(DesignTokens.Background)) { HomeTopBar(onAction) }
                        }
                        is HomeContent.Feed -> Box(Modifier.fillMaxSize()) {
                            // Pull moves the feed rows only; the chrome stack keeps its place.
                            Column(Modifier.fillMaxSize().graphicsLayer { translationY = pull }) {
                            if (content.refreshFailed) Box(Modifier.graphicsLayer { translationY = (96f + chipExtraHeight.value - chromeTravel * chromeOffset) * density }) {
                                RetryRow(stringResource(R.string.ui_couldn_t_refresh_try_again)) { onAction(HomeAction.Refresh) }
                            }
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(HomeLayout.columns(width)), state = grid,
                                modifier = Modifier.fillMaxSize().testTag("home-feed"),
                                contentPadding = PaddingValues(start = HomeLayout.outerPadding(width).dp, end = HomeLayout.outerPadding(width).dp, top = feedTopInset, bottom = 24.dp),
                                horizontalArrangement = Arrangement.spacedBy(DesignTokens.GridGap),
                            ) {
                                homeCards(visibleItems, currentWidth, state.live, onAction, postInteractions)
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    when (content.append) {
                                        AppendState.Loading -> Box(Modifier.fillMaxWidth().padding(top = 80.dp, bottom = 12.dp), contentAlignment = Alignment.Center) { LoadingSpinner() }
                                        AppendState.Failed -> RetryRow(stringResource(R.string.ui_something_went_wrong_tap_to_retry)) { onAction(HomeAction.LoadNext) }
                                        AppendState.Idle -> if (content.page.nextKey != null && content.automaticPagingPaused) {
                                            Box(Modifier.fillMaxWidth().padding(vertical = 16.dp).testTag("home-load-more"), contentAlignment = Alignment.Center) {
                                                PillButton(stringResource(R.string.load_more)) { onAction(HomeAction.LoadNext) }
                                            }
                                        } else Spacer(Modifier.height(1.dp))
                                    }
                                }
                            }
                            }
                            Box(Modifier.graphicsLayer { translationY = (48f - chromeTravel * chromeOffset) * density }.background(DesignTokens.Background)) { ChipStrip(state.selectedChip, state.chips, content.page.catalog != null, onAction) }
                            Box(Modifier.graphicsLayer { translationY = -48f * chromeOffset * density }.background(DesignTokens.Background)) { HomeTopBar(onAction) }
                        }
                    }
                    val refreshAnchor = if (state.content is HomeContent.Feed)
                        (96f + chipExtraHeight.value - chromeTravel * chromeOffset).dp else 0.dp
                    PullRefreshIndicator(pullMotion, "home-refresh-indicator", refreshAnchor)
                }
                HomeBottomBar(state.signedInPreview, onAction, state.avatar)
            }
            when (val overlay = state.overlay) {
                HomeOverlay.None -> Unit
                HomeOverlay.Search -> SearchOverlay(state, onAction, searchRows)
                is HomeOverlay.Menu -> if (overlay.kind == FeedMenuKind.Mix) MixMenu(onAction, state.live) else VideoMenu(
                    onAction, overlay.kind, state.live, state.signedInPreview,
                    (state.content as? HomeContent.Feed)?.page?.items?.filterIsInstance<FeedItem.Video>()
                        ?.firstOrNull { it.id == overlay.itemId && it.feedbackGeneration == state.sessionGeneration },
                    overlay,
                )
                is HomeOverlay.Save -> if (overlay.newPlaylist) NewPlaylistSheet(
                    draft = NewPlaylistDraft(overlay.title, overlay.description),
                    onDismiss = { onAction(HomeAction.CloseOverlay) },
                    onDraftChange = { onAction(HomeAction.SaveDraftChanged(it.title, it.description)) },
                    onSubmit = { onAction(HomeAction.SubmitNewPlaylist) },
                    pending = overlay.pending,
                ) else if (overlay.live) SaveToPlaylistSheet(
                    playlists = emptyList(),
                    onDismiss = { onAction(HomeAction.CloseOverlay) },
                    onSelect = {},
                    onNewPlaylist = { onAction(HomeAction.OpenNewPlaylist) },
                    live = SaveSheetLive(
                        choices = overlay.choices,
                        failed = overlay.choicesFailed,
                        pending = overlay.pending,
                        onToggle = { onAction(HomeAction.SaveChoiceToggled(it)) },
                        onRetry = { onAction(HomeAction.SaveChoicesRetry) },
                    ),
                ) else SaveToPlaylistSheet(
                    playlists = homeSavePlaylists,
                    onDismiss = { onAction(HomeAction.CloseOverlay) },
                    onSelect = { onAction(HomeAction.SaveDestinationSelected(it)) },
                    onNewPlaylist = { onAction(HomeAction.OpenNewPlaylist) },
                )
                is HomeOverlay.Feedback -> FeedbackSheet(
                    title = stringResource(overlay.kind.labelResource),
                    reasons = overlay.kind.reasons.mapIndexed { index, reason ->
                        FeedbackReason(reason, "feedback-reason-$index", overlay.kind.reasonResources[index])
                    },
                    onDismiss = { onAction(HomeAction.CloseOverlay) },
                    onSelect = { onAction(HomeAction.FeedbackReason(it.label)) },
                )
            }
            if (feed != null) state.verification?.let { verification ->
                VerificationBanner(
                    verification,
                    onVerify = { onAction(HomeAction.Navigate(HomeNavigationRequest.OpenVerification(verification.challengeUrl))) },
                    onDismiss = { onAction(HomeAction.DismissVerification) },
                    modifier = Modifier.align(Alignment.BottomCenter)
                        .padding(start = 16.dp, end = 16.dp, bottom = DesignTokens.BottomBarHeight + 16.dp)
                        .testTag("home-verification-banner"),
                )
            }
        }
    }
}

private val homeSavePlaylists: List<SavePlaylist> =
    FixturePlaylistRepository.personalLists()
        .filter { it.id != FixturePlaylistRepository.WATCH_LATER }
        .map { SavePlaylist(it.id, it.title) }

private fun LazyGridScope.homeCards(items: List<FeedItem>, width: Float, live: Boolean, onAction: (HomeAction) -> Unit,
    postInteractions: com.hhst.youtubelite.post.presentation.PostCardInteractions?) {
    items(items, key = { it.id }, span = {
        if (it is FeedItem.Shorts || it is FeedItem.Post) GridItemSpan(maxLineSpan) else GridItemSpan(1)
    }, contentType = { it.javaClass.simpleName }) { item ->
        Box((if (LocalMotionScale.current > 0f) Modifier.animateItem() else Modifier).padding(bottom = if (item is FeedItem.Post) 16.dp else 24.dp)) {
            FeedCard(item, compact = width < 528f, windowWidth = width, live, onAction, postInteractions)
        }
    }
}

@Composable
private fun HomeTopBar(onAction: (HomeAction) -> Unit) {
    Row(Modifier.fillMaxWidth().height(DesignTokens.TopBarHeight).testTag("home-topbar"), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(117.dp).fillMaxHeight().clickable(role = Role.Button) {
            onAction(HomeAction.Navigate(HomeNavigationRequest.Destination(HomeDestination.Home)))
        }.appDescription(R.string.a11y_litube_home), contentAlignment = Alignment.CenterStart) {
            LitubeBrand(Modifier.padding(start = 12.dp))
        }
        Spacer(Modifier.weight(1f))
        AppIconButton(AppIcon.Search, stringResource(R.string.a11y_search_youtube)) { onAction(HomeAction.OpenSearch) }
    }
}

@Composable
private fun HomeBottomBar(signedIn: Boolean, onAction: (HomeAction) -> Unit, avatar: String? = null) {
    val haptic = rememberHapticAction(com.hhst.youtubelite.core.HapticsController.Event.TAB)
    Column(Modifier.fillMaxWidth().testTag("home-navigation")) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(DesignTokens.NavigationDivider))
        Row(Modifier.fillMaxWidth().height(DesignTokens.BottomBarHeight)) {
            val destinations = if (signedIn) HomeDestination.entries else listOf(HomeDestination.Home, HomeDestination.Shorts, HomeDestination.You)
            destinations.filterNot { LocalHideShorts.current && it == HomeDestination.Shorts }.forEach { destination ->
                Column(Modifier.weight(1f).fillMaxHeight()
                    .selectable(destination == HomeDestination.Home, role = Role.Tab) {
                        haptic()
                        onAction(HomeAction.Navigate(HomeNavigationRequest.Destination(destination)))
                    }.testTag("nav-${destination.name}"),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    if (signedIn && destination == HomeDestination.You) {
                        if (avatar.isNullOrBlank()) {
                            Image(
                                painterResource(R.drawable.sample_bunny),
                                contentDescription = null, modifier = Modifier.size(24.dp).clip(CircleShape),
                                contentScale = ContentScale.Crop,
                            )
                        } else {
                            com.hhst.youtubelite.ui.design.Artwork(avatar,
                                modifier = Modifier.size(24.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                        }
                    } else AppIcon(when (destination) {
                        HomeDestination.Home -> AppIcon.Home; HomeDestination.Shorts -> AppIcon.Shorts
                        HomeDestination.Subscriptions -> AppIcon.Subscriptions; HomeDestination.You -> AppIcon.You
                    })
                    AppText(stringResource(destination.labelResource), size = 11, line = 14, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun ChipStrip(selected: String, chips: List<String>, catalog: Boolean, onAction: (HomeAction) -> Unit) {
    val extraHeight = with(LocalDensity.current) { (20.sp.toDp() - 20.dp).coerceAtLeast(0.dp) }
    LazyRow(Modifier.fillMaxWidth().height(48.dp + extraHeight).testTag("home-chips"), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(horizontal = 16.dp)) {
        item {
            Box(Modifier.padding(end = 12.dp).size(width = 48.dp, height = 32.dp + extraHeight)
                .clip(RoundedCornerShape(2.dp)).background(DesignTokens.Surface)
                .clickable(role = Role.Button) { onAction(HomeAction.Navigate(HomeNavigationRequest.Explore)) }
                .appDescription(R.string.a11y_explore), contentAlignment = Alignment.Center) {
                AppIcon(AppIcon.Explore)
            }
        }
        items(chips) { chip ->
            Box(Modifier.widthIn(min = 48.dp).clip(RoundedCornerShape(8.dp)).background(if (chip == selected) DesignTokens.Ink else DesignTokens.Surface)
                .selectable(chip == selected, role = Role.Tab) { onAction(HomeAction.SelectChip(chip)) }.padding(horizontal = 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center) {
                val label = if (!catalog) chip else when (HomeCatalog.forFilter(chip)) {
                    HomeCatalog.Popular -> stringResource(R.string.download_filter_all)
                    HomeCatalog.MusicCharts -> stringResource(R.string.home_music_charts)
                    HomeCatalog.Gaming -> stringResource(R.string.home_popular_gaming)
                    HomeCatalog.Sports -> stringResource(R.string.home_popular_sports)
                    HomeCatalog.News -> stringResource(R.string.home_top_news)
                    null -> chip
                }
                AppText(label, size = 14, line = 20, weight = FontWeight.Medium, color = if (chip == selected) DesignTokens.Background else DesignTokens.Ink)
            }
        }
    }
}

@Composable
private fun EmptyHome(content: HomeContent.Empty, showTools: Boolean, wide: Boolean, onAction: (HomeAction) -> Unit,
    showMessage: Boolean = true) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("home-empty"), horizontalAlignment = Alignment.CenterHorizontally) {
        if (showTools) {
            Spacer(Modifier.height(if (wide) 76.dp else 60.dp))
            LitubeMark(Modifier.size(width = 80.dp, height = 55.dp))
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 23.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppIconButton(AppIcon.Explore, stringResource(R.string.a11y_explore), diameter = 40.dp, background = DesignTokens.Surface) { onAction(HomeAction.Navigate(HomeNavigationRequest.Explore)) }
                Box(Modifier.weight(1f).height(40.dp).clip(CircleShape).background(DesignTokens.Surface)
                    .clickable(role = Role.Button) { onAction(HomeAction.OpenSearch) }
                    .appDescription(R.string.a11y_search_youtube).padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
                    AppText(stringResource(R.string.a11y_search_youtube), size = 16, line = 22, color = Color(0xFF909090))
                }
                AppIconButton(AppIcon.Voice, stringResource(R.string.a11y_voice_search), diameter = 40.dp, background = DesignTokens.Surface) { onAction(HomeAction.Navigate(HomeNavigationRequest.VoiceSearch)) }
            }
            Spacer(Modifier.height(48.dp))
        } else Spacer(Modifier.height(12.dp))
        Column(Modifier.padding(horizontal = 16.dp).widthIn(max = 392.dp).fillMaxWidth().testTag("empty-message")
            .messageCardShadow()
            .background(DesignTokens.Background, RoundedCornerShape(12.dp)).padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            AppText(content.title.ifBlank { stringResource(R.string.home_empty_title) }, size = 24, line = 32, weight = FontWeight.Black, align = TextAlign.Center)
            if (showMessage) {
                Spacer(Modifier.height(12.dp))
                AppText(content.message.ifBlank { stringResource(R.string.home_empty_message) }, size = 14, line = 20, color = DesignTokens.Secondary, align = TextAlign.Center)
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun ErrorHome(onAction: (HomeAction) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).testTag("home-error"),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        AppText(stringResource(R.string.ui_something_went_wrong), size = 20, line = 28, weight = FontWeight.Bold)
        AppText(stringResource(R.string.ui_please_try_again), modifier = Modifier.padding(top = 8.dp, bottom = 20.dp), color = DesignTokens.Secondary)
        PillButton(stringResource(R.string.retry)) { onAction(HomeAction.Refresh) }
    }
}

@Composable
internal fun PillButton(label: String, onClick: () -> Unit) {
    Box(Modifier.clip(CircleShape).background(DesignTokens.Surface).clickable(role = Role.Button, onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        AppText(label, weight = FontWeight.Medium)
    }
}

@Composable
private fun RetryRow(label: String, retry: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        AppText(label, Modifier.weight(1f), color = DesignTokens.Secondary)
        PillButton(stringResource(R.string.retry), retry)
    }
}

@Composable
private fun HomeSkeleton(width: Float, topInset: Dp) {
    LazyVerticalGrid(GridCells.Fixed(HomeLayout.columns(width)), Modifier.fillMaxSize().testTag("home-skeleton").clearAndSetSemantics {},
        contentPadding = PaddingValues(start = HomeLayout.outerPadding(width).dp, end = HomeLayout.outerPadding(width).dp,
            top = topInset, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(DesignTokens.GridGap), verticalArrangement = Arrangement.spacedBy(40.dp)) {
        items(12) { index ->
            Column(Modifier.testTag("skeleton-card-$index")) {
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9).clip(RoundedCornerShape(if (width < 528f) 0.dp else 12.dp)).background(DesignTokens.Ghost).testTag("skeleton-thumb-$index"))
                Row(Modifier.padding(start = if (width < 528) 12.dp else 0.dp, top = 8.dp, end = if (width < 528) 12.dp else 0.dp)) {
                    Box(Modifier.size(36.dp).clip(CircleShape).background(DesignTokens.Ghost).testTag("skeleton-avatar-$index"))
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Box(Modifier.widthIn(max = 300.dp).fillMaxWidth().height(16.dp).background(DesignTokens.Ghost, RoundedCornerShape(8.dp)))
                        Spacer(Modifier.height(8.dp))
                        Box(Modifier.width(120.dp).height(16.dp).background(DesignTokens.Ghost, RoundedCornerShape(8.dp)))
                    }
                }
            }
        }
    }
}

@Composable
internal fun LoadingSpinner() {
    val rotation by rememberInfiniteTransition(label = "loading").animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(800, easing = LinearEasing)), label = "rotation",
    )
    androidx.compose.foundation.Canvas(Modifier.size(32.dp).appDescription(R.string.a11y_loading)) {
        val stroke = 2.dp.toPx()
        val style = androidx.compose.ui.graphics.drawscope.Stroke(stroke)
        drawCircle(Color(0xFFEEEEEE), radius = size.minDimension / 2 - stroke / 2, style = style)
        drawArc(Color(0xFF666666), rotation + 225f, 90f, false,
            topLeft = Offset(stroke / 2, stroke / 2), size = Size(size.width - stroke, size.height - stroke), style = style)
    }
}
