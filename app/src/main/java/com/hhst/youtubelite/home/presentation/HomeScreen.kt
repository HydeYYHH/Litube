package com.hhst.youtubelite.home.presentation

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Velocity
import com.hhst.youtubelite.ui.design.*
import com.hhst.youtubelite.home.domain.*

/** Stateless rendering boundary; local state is limited to saved scroll/focus state. */
@Composable
fun HomeScreen(state: HomeUiState, onAction: (HomeAction) -> Unit, modifier: Modifier = Modifier) {
    AppTheme {
        BoxWithConstraints(modifier.fillMaxSize().background(Color.White).testTag("home-screen")) {
            val width = maxWidth.value
            val grid = rememberLazyGridState()
            val density = LocalDensity.current.density
            var pull by remember { mutableFloatStateOf(0f) }
            val latestAction by rememberUpdatedState(onAction)
            val pullConnection = remember(grid, density) { object : NestedScrollConnection {
                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    if (source == NestedScrollSource.UserInput && available.y > 0 && grid.firstVisibleItemIndex == 0 && grid.firstVisibleItemScrollOffset == 0) {
                        pull = (pull + available.y * 0.5f).coerceAtMost(80f * density)
                        return Offset(0f, available.y)
                    }
                    return Offset.Zero
                }
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (pull > 0 && available.y < 0) {
                        val consumed = available.y.coerceAtLeast(-pull)
                        pull += consumed
                        return Offset(0f, consumed)
                    }
                    return Offset.Zero
                }
                override suspend fun onPreFling(available: Velocity): Velocity {
                    if (pull >= 56f * density) latestAction(HomeAction.Refresh)
                    pull = 0f
                    return Velocity.Zero
                }
            } }
            val feed = state.content as? HomeContent.Feed
            val nearEnd by remember(grid, feed?.page?.items?.size) { derivedStateOf { (grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) >= (feed?.page?.items?.size ?: Int.MAX_VALUE) - 2 } }
            LaunchedEffect(nearEnd, feed?.page?.nextKey, feed?.append, feed?.refreshing) {
                if (nearEnd && feed?.page?.nextKey != null && feed.append == AppendState.Idle && !feed.refreshing) onAction(HomeAction.LoadNext)
            }
            val searchOpen = state.overlay == HomeOverlay.Search
            Column(Modifier.fillMaxSize().then(if (state.overlay != HomeOverlay.None) Modifier.clearAndSetSemantics {} else Modifier)) {
                if (state.content == HomeContent.Loading) {
                    Box(Modifier.fillMaxWidth().height(DesignTokens.TopBarHeight).shadow(2.dp))
                } else {
                    HomeTopBar(onAction)
                }
                Box(Modifier.weight(1f).fillMaxWidth().nestedScroll(pullConnection)) {
                    if (pull > 0f) Box(Modifier.align(Alignment.TopCenter).padding(top = 12.dp)) { LoadingSpinner() }
                    Box(Modifier.fillMaxSize().graphicsLayer { translationY = pull }) {
                    when (val content = state.content) {
                        is HomeContent.Empty -> EmptyHome(content, showTools = !searchOpen, wide = width >= 528, onAction)
                        HomeContent.Failed -> ErrorHome(onAction)
                        HomeContent.Loading -> HomeSkeleton(width)
                        is HomeContent.Feed -> Column(Modifier.fillMaxSize()) {
                            ChipStrip(state.selectedChip, onAction)
                            if (content.refreshing) Box(Modifier.fillMaxWidth().height(2.dp).background(DesignTokens.Surface)) {
                                Box(Modifier.fillMaxWidth(0.4f).fillMaxHeight().background(DesignTokens.Red))
                            }
                            if (content.refreshFailed) RetryRow("Couldn't refresh. Try again.") { onAction(HomeAction.Refresh) }
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(HomeLayout.columns(width)), state = grid,
                                modifier = Modifier.fillMaxSize().testTag("home-feed"),
                                contentPadding = PaddingValues(start = HomeLayout.outerPadding(width).dp, end = HomeLayout.outerPadding(width).dp, top = 14.dp, bottom = 24.dp),
                                horizontalArrangement = Arrangement.spacedBy(DesignTokens.GridGap),
                                verticalArrangement = Arrangement.spacedBy(24.dp),
                            ) {
                                items(content.page.items, key = { it.id }, span = { if (it is FeedItem.Shorts) GridItemSpan(maxLineSpan) else GridItemSpan(1) }, contentType = { it.javaClass.simpleName }) { item ->
                                    FeedCard(item, compact = width < 528, onAction)
                                }
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    when (content.append) {
                                        AppendState.Loading -> Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) { LoadingSpinner() }
                                        AppendState.Failed -> RetryRow("Something went wrong. Tap to retry.") { onAction(HomeAction.LoadNext) }
                                        AppendState.Idle -> Spacer(Modifier.height(1.dp))
                                    }
                                }
                            }
                        }
                    }
                    }
                }
                HomeBottomBar(state.signedInPreview, state.content == HomeContent.Loading, onAction)
            }
            when (val overlay = state.overlay) {
                HomeOverlay.None -> Unit
                HomeOverlay.Search -> SearchOverlay(state, onAction)
                is HomeOverlay.Menu -> VideoMenu(onAction)
            }
        }
    }
}

@Composable
private fun HomeTopBar(onAction: (HomeAction) -> Unit) {
    Row(Modifier.fillMaxWidth().height(DesignTokens.TopBarHeight).testTag("home-topbar"), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(117.dp).fillMaxHeight().clickable(role = Role.Button) {
            onAction(HomeAction.Navigate(HomeNavigationRequest.Destination(HomeDestination.Home)))
        }.semantics { contentDescription = "Litube Home" }, contentAlignment = Alignment.CenterStart) {
            LitubeBrand(Modifier.padding(start = 12.dp))
        }
        Spacer(Modifier.weight(1f))
        AppIconButton(AppIcon.Search, "Search YouTube") { onAction(HomeAction.OpenSearch) }
    }
}

@Composable
private fun HomeBottomBar(signedIn: Boolean, loading: Boolean, onAction: (HomeAction) -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("home-navigation")) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(DesignTokens.NavigationDivider))
        Row(Modifier.fillMaxWidth().height(DesignTokens.BottomBarHeight)) {
            if (!loading) {
                val destinations = if (signedIn) HomeDestination.entries else listOf(HomeDestination.Home, HomeDestination.Shorts, HomeDestination.You)
                destinations.forEach { destination ->
                    Column(Modifier.weight(1f).fillMaxHeight()
                        .selectable(destination == HomeDestination.Home, role = Role.Tab) {
                            onAction(HomeAction.Navigate(HomeNavigationRequest.Destination(destination)))
                        }.testTag("nav-${destination.name}"),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        AppIcon(when (destination) {
                            HomeDestination.Home -> AppIcon.Home; HomeDestination.Shorts -> AppIcon.Shorts
                            HomeDestination.Subscriptions -> AppIcon.Subscriptions; HomeDestination.You -> AppIcon.You
                        })
                        AppText(destination.label, size = 11, line = 14)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChipStrip(selected: String, onAction: (HomeAction) -> Unit) {
    LazyRow(Modifier.fillMaxWidth().height(48.dp).testTag("home-chips"), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 12.dp)) {
        item { AppIconButton(AppIcon.Explore, "Explore", diameter = 40.dp, background = DesignTokens.Surface) { onAction(HomeAction.Navigate(HomeNavigationRequest.Explore)) } }
        items(HomeLayout.chips) { chip ->
            Box(Modifier.clip(RoundedCornerShape(8.dp)).background(if (chip == selected) DesignTokens.Ink else DesignTokens.Surface)
                .selectable(chip == selected, role = Role.Tab) { onAction(HomeAction.SelectChip(chip)) }.padding(horizontal = 12.dp, vertical = 6.dp)) {
                AppText(chip, size = 14, line = 20, weight = FontWeight.Medium, color = if (chip == selected) Color.White else DesignTokens.Ink)
            }
        }
    }
}

@Composable
private fun EmptyHome(content: HomeContent.Empty, showTools: Boolean, wide: Boolean, onAction: (HomeAction) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("home-empty"), horizontalAlignment = Alignment.CenterHorizontally) {
        if (showTools) {
            Spacer(Modifier.height(if (wide) 76.dp else 60.dp))
            LitubeMark(Modifier.size(width = 80.dp, height = 55.dp))
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 23.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppIconButton(AppIcon.Explore, "Explore", diameter = 40.dp, background = DesignTokens.Surface) { onAction(HomeAction.Navigate(HomeNavigationRequest.Explore)) }
                Box(Modifier.weight(1f).height(40.dp).clip(CircleShape).background(DesignTokens.Surface)
                    .clickable(role = Role.Button) { onAction(HomeAction.OpenSearch) }
                    .semantics { contentDescription = "Search YouTube" }.padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
                    AppText("Search YouTube", size = 16, line = 22, color = Color(0xFF909090))
                }
                AppIconButton(AppIcon.Voice, "Search with your voice", diameter = 40.dp, background = DesignTokens.Surface) { onAction(HomeAction.Navigate(HomeNavigationRequest.VoiceSearch)) }
            }
            Spacer(Modifier.height(48.dp))
        } else Spacer(Modifier.height(12.dp))
        Column(Modifier.padding(horizontal = 16.dp).widthIn(max = 392.dp).fillMaxWidth().testTag("empty-message")
            .messageCardShadow()
            .background(Color.White, RoundedCornerShape(12.dp)).padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            AppText(content.title, size = 24, line = 32, weight = FontWeight.Black, align = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            AppText(content.message, size = 14, line = 20, color = DesignTokens.Secondary, align = TextAlign.Center)
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun ErrorHome(onAction: (HomeAction) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).testTag("home-error"),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        AppIcon(AppIcon.Refresh, Modifier.size(48.dp), DesignTokens.Secondary)
        Spacer(Modifier.height(20.dp))
        AppText("Something went wrong", size = 20, line = 28, weight = FontWeight.Bold)
        AppText("Tap to retry", modifier = Modifier.padding(top = 8.dp, bottom = 20.dp), color = DesignTokens.Secondary)
        PillButton("Retry") { onAction(HomeAction.Refresh) }
    }
}

@Composable
internal fun PillButton(label: String, onClick: () -> Unit) {
    Box(Modifier.clip(CircleShape).background(DesignTokens.Surface).clickable(role = Role.Button, onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
        AppText(label, weight = FontWeight.Medium)
    }
}

@Composable
private fun RetryRow(label: String, retry: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        AppText(label, Modifier.weight(1f), color = DesignTokens.Secondary)
        PillButton("Retry", retry)
    }
}

@Composable
private fun HomeSkeleton(width: Float) {
    LazyVerticalGrid(GridCells.Fixed(HomeLayout.columns(width)), Modifier.fillMaxSize().testTag("home-skeleton"),
        contentPadding = PaddingValues(horizontal = HomeLayout.outerPadding(width).dp, vertical = if (width < 528) 0.dp else 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(40.dp)) {
        items(12) {
            Column {
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9).clip(RoundedCornerShape(if (width < 528) 0.dp else 12.dp)).background(DesignTokens.Ghost))
                Column(Modifier.padding(start = if (width < 528) 12.dp else 0.dp, top = 12.dp, end = 12.dp)) {
                    Box(Modifier.widthIn(max = 300.dp).fillMaxWidth().height(16.dp).background(DesignTokens.Ghost, RoundedCornerShape(2.dp)))
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.width(120.dp).height(16.dp).background(DesignTokens.Ghost, RoundedCornerShape(2.dp)))
                }
            }
        }
    }
}

@Composable
internal fun LoadingSpinner() {
    androidx.compose.foundation.Canvas(Modifier.size(24.dp).semantics { contentDescription = "Loading" }) {
        drawArc(DesignTokens.Secondary, -90f, 270f, false, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
    }
}
