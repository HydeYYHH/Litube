package com.hhst.youtubelite.subscriptions.presentation

import com.hhst.youtubelite.ui.components.mediaActions
import com.hhst.youtubelite.ui.components.MediaActionItem
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as listItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hhst.youtubelite.R
import com.hhst.youtubelite.home.domain.AppendState
import com.hhst.youtubelite.home.domain.FeedItem
import com.hhst.youtubelite.home.presentation.LoadingSpinner
import com.hhst.youtubelite.home.presentation.PillButton
import com.hhst.youtubelite.library.domain.SignInPrompt
import com.hhst.youtubelite.library.presentation.LibraryMessageCard
import com.hhst.youtubelite.library.presentation.LibrarySkeleton
import com.hhst.youtubelite.subscriptions.domain.*
import com.hhst.youtubelite.ui.components.MainTab
import com.hhst.youtubelite.ui.components.MainNavigationBar
import com.hhst.youtubelite.ui.design.*

/** The subscriptions page: chip strip, feed grid and the channels overlay. */
@Composable
fun SubscriptionsScreen(state: SubscriptionsUiState, onAction: (SubscriptionsAction) -> Unit, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxSize().background(Color.White).testTag("subscriptions-screen")) {
        val width = maxWidth.value
        val gridState = rememberLazyGridState()
        Column(Modifier.fillMaxSize().then(if (state.overlay != SubscriptionsOverlay.None) Modifier.clearAndSetSemantics {} else Modifier)) {
            SubscriptionsTopBar(width, onAction)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (val content = state.content) {
                    SubscriptionsContent.Loading -> LibrarySkeleton("subscriptions-skeleton")
                    SubscriptionsContent.Failed -> SubscriptionsError { onAction(SubscriptionsAction.Retry) }
                    is SubscriptionsContent.SignedOut -> LibraryMessageCard(
                        content.prompt.title, content.prompt.message, "subscriptions-signed-out",
                    ) { PillButton(content.prompt.action) { onAction(SubscriptionsAction.SignIn) } }
                    is SubscriptionsContent.Feed -> SubscriptionsFeed(content, width, state.selectedChip, gridState, onAction)
                }
            }
            MainNavigationBar("main", state.signedIn, null, selected = MainTab.Subscriptions) { tab -> onAction(SubscriptionsAction.Destination(tab)) }
        }
        if (state.overlay == SubscriptionsOverlay.Channels) {
            ChannelsOverlay(onAction)
        }
    }
}

@Composable
private fun SubscriptionsTopBar(width: Float, onAction: (SubscriptionsAction) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = DesignTokens.TopBarHeight).testTag("subscriptions-topbar"), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(if (width < 600f) 53.dp else 48.dp).height(DesignTokens.TopBarHeight)
            .clickable(role = Role.Button) { onAction(SubscriptionsAction.Destination(MainTab.Home)) }, contentAlignment = Alignment.Center) {
            LitubeMark(Modifier.size(24.dp))
        }
        AppText("Subscriptions", Modifier.weight(1f).testTag("subscriptions-title"), size = 20, line = 28,
            weight = FontWeight.Bold, maxLines = 1)
        AppIconButton(AppIcon.Search, "Search YouTube") { onAction(SubscriptionsAction.OpenSearch) }
    }
}

@Composable
private fun SubscriptionsChipStrip(selected: SubscriptionsChip, onAction: (SubscriptionsAction) -> Unit) {
    LazyRow(Modifier.fillMaxWidth().height(48.dp).testTag("subscriptions-chips"), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(horizontal = 16.dp)) {
        listItems(SubscriptionsChip.entries) { chip ->
            Box(Modifier.widthIn(min = 48.dp).clip(RoundedCornerShape(8.dp))
                .background(if (chip == selected) DesignTokens.Ink else DesignTokens.Surface)
                .clickable(role = Role.Tab) { onAction(SubscriptionsAction.SelectChip(chip)) }
                .padding(horizontal = 12.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
                AppText(chip.label, size = 14, line = 20, weight = FontWeight.Medium,
                    color = if (chip == selected) Color.White else DesignTokens.Ink)
            }
        }
        item {
            Box(Modifier.clip(RoundedCornerShape(8.dp)).background(DesignTokens.Surface)
                .clickable(role = Role.Button) { onAction(SubscriptionsAction.Manage) }
                .testTag("subscriptions-manage").padding(horizontal = 12.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
                AppText("Manage", size = 14, line = 20, weight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun ChannelAvatarStrip(channels: List<SubscribedChannel>, onAction: (SubscriptionsAction) -> Unit) {
    LazyRow(Modifier.fillMaxWidth().height(52.dp).testTag("subscriptions-channels"),
        verticalAlignment = Alignment.CenterVertically,
        contentPadding = PaddingValues(start = 8.dp, end = 0.dp)) {
        listItems(channels, key = { it.channelId }) { channel ->
            Box(Modifier.padding(end = 8.dp).size(41.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(32.dp).clip(CircleShape)
                    .clickable(role = Role.Button) { onAction(SubscriptionsAction.OpenChannel(channel.channelId)) }
                    .semantics { contentDescription = channel.name }.testTag("subs-strip-${channel.channelId}")) {
                    SubscriptionsArtwork(channel.avatar, Modifier.fillMaxSize())
                }
            }
        }
        item(key = "all-channels") {
            AppIconButton(AppIcon.ChannelGrid, "All channels",
                modifier = Modifier.testTag("subscriptions-manage"), diameter = 40.dp) {
                onAction(SubscriptionsAction.ShowChannels)
            }
        }
    }
}

@Composable
private fun SubscriptionsFeed(content: SubscriptionsContent.Feed, width: Float, selectedChip: SubscriptionsChip, gridState: androidx.compose.foundation.lazy.grid.LazyGridState, onAction: (SubscriptionsAction) -> Unit) {
    val compact = width < 600f
    val nearEnd by remember(content) { derivedStateOf {
        (gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) >= content.page.items.size - 2
    } }
    androidx.compose.runtime.LaunchedEffect(nearEnd, content.page.nextKey, content.append) {
        if (nearEnd && content.page.nextKey != null && content.append == AppendState.Idle) {
            onAction(SubscriptionsAction.LoadNext)
        }
    }
    Column(Modifier.fillMaxSize()) {
        if (compact) ChannelAvatarStrip(content.page.channels, onAction) else SubscriptionsChipStrip(selectedChip, onAction)
        LazyVerticalGrid(
            columns = GridCells.Fixed(if (compact) 1 else SubscriptionsLayout.columns(width)), state = gridState,
            modifier = Modifier.fillMaxSize().testTag("subscriptions-feed"),
            contentPadding = PaddingValues(start = SubscriptionsLayout.outerPadding(width).dp, end = SubscriptionsLayout.outerPadding(width).dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(DesignTokens.GridGap),
        ) {
            items(content.page.items, key = { it.id }, contentType = { "video" }) { item ->
                Box(Modifier.padding(top = if (compact) 8.dp else 12.dp, bottom = if (compact) 12.dp else 12.dp)) {
                    SubscriptionsVideoCard(item, compact = compact, onAction)
                }
            }
            if (content.page.items.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(top = 96.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            AppText("No videos", size = 20, line = 28, weight = FontWeight.Bold)
                            AppText("New videos from your subscriptions will show up here.",
                                Modifier.padding(top = 8.dp), color = DesignTokens.Secondary)
                        }
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }, key = "append") {
                when (content.append) {
                    AppendState.Loading -> Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) { LoadingSpinner() }
                    AppendState.Failed -> RetryAppend { onAction(SubscriptionsAction.LoadNext) }
                    AppendState.Idle -> Spacer(Modifier.height(1.dp))
                }
            }
        }
    }
}

@Composable
private fun RetryAppend(retry: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        AppText("Something went wrong. Tap to retry.", Modifier.weight(1f), color = DesignTokens.Secondary)
        PillButton("Retry", retry)
    }
}

@Composable
internal fun SubscriptionsArtwork(asset: String, modifier: Modifier = Modifier) {
    com.hhst.youtubelite.ui.design.Artwork(asset, modifier)
}

@Composable
internal fun SubscriptionsVideoCard(item: FeedItem.Video, compact: Boolean, onAction: (SubscriptionsAction) -> Unit) {
    Column(Modifier.testTag("subs-item-${item.id}")) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9).clip(RoundedCornerShape(if (compact) 0.dp else 12.dp))
            .mediaActions(MediaActionItem(item.id, item.title, item.author, item.artwork)) { onAction(SubscriptionsAction.OpenVideo(item.id)) }
            .semantics { contentDescription = "${item.title}, ${item.duration}" }) {
            SubscriptionsArtwork(item.artwork, Modifier.fillMaxSize())
            Box(Modifier.align(Alignment.BottomEnd).padding(8.dp)
                .background(if (item.live) DesignTokens.Red else Color.Black.copy(alpha = 0.75f), RoundedCornerShape(4.dp))
                .padding(horizontal = 4.dp, vertical = 1.dp)) {
                AppText(item.duration, size = 12, line = 16, weight = FontWeight.Medium, color = Color.White)
            }
        }
        if (compact) {
            Row(Modifier.padding(start = 12.dp, top = 8.dp, end = 0.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.size(40.dp).clip(CircleShape).clickable(role = Role.Button) {
                    onAction(SubscriptionsAction.OpenChannel(item.channelId))
                }.testTag("subs-channel-${item.channelId}").semantics { contentDescription = "Go to channel ${item.author}" }) {
                    SubscriptionsArtwork(item.artwork, Modifier.fillMaxSize())
                }
                Spacer(Modifier.width(12.dp))
                Box(Modifier.weight(1f)) {
                    Column(Modifier.padding(end = 48.dp)
                        .mediaActions(MediaActionItem(item.id, item.title, item.author, item.artwork)) { onAction(SubscriptionsAction.OpenVideo(item.id)) }) {
                        AppText(item.title, size = 14, exactLineHeight = 17.5.sp, weight = FontWeight.Medium, maxLines = 2)
                        AppText("${item.author} • ${item.metadata}", Modifier.padding(top = 3.dp),
                            size = 12, line = 14, color = DesignTokens.Secondary, maxLines = 1)
                    }
                    AppIconButton(AppIcon.More, "More actions for ${item.title}",
                        modifier = Modifier.align(Alignment.TopEnd), diameter = 48.dp) {
                        onAction(SubscriptionsAction.OpenMenu(item.id))
                    }
                }
            }
        } else {
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.size(36.dp).clip(CircleShape).clickable(role = Role.Button) {
                    onAction(SubscriptionsAction.OpenChannel(item.channelId))
                }.testTag("subs-channel-${item.channelId}").semantics { contentDescription = "Go to channel ${item.author}" }) {
                    SubscriptionsArtwork(item.artwork, Modifier.fillMaxSize())
                }
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    Column(Modifier.mediaActions(MediaActionItem(item.id, item.title, item.author, item.artwork)) { onAction(SubscriptionsAction.OpenVideo(item.id)) }) {
                        AppText(item.title, Modifier.padding(end = 24.dp), size = 16, line = 22, weight = FontWeight.Medium, maxLines = 2)
                        AppText("${item.author} · ${item.metadata}", Modifier.padding(top = 2.dp), size = 12, line = 18,
                            color = DesignTokens.Secondary, maxLines = 1)
                    }
                    AppIconButton(AppIcon.More, "More actions for ${item.title}",
                        modifier = Modifier.align(Alignment.TopEnd).offset(x = 10.dp, y = (-6).dp),
                        diameter = 40.dp) { onAction(SubscriptionsAction.OpenMenu(item.id)) }
                }
            }
        }
    }
}

@Composable
internal fun SubscriptionsError(onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("subscriptions-error").padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        AppText("Something went wrong", size = 20, line = 28, weight = FontWeight.Bold)
        AppText("Please try again.", Modifier.padding(top = 8.dp, bottom = 20.dp), color = DesignTokens.Secondary)
        PillButton("Retry", onRetry)
    }
}

@Composable
private fun ChannelsOverlay(onAction: (SubscriptionsAction) -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.White).testTag("channels-overlay")) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().heightIn(min = DesignTokens.TopBarHeight), verticalAlignment = Alignment.CenterVertically) {
                AppIconButton(AppIcon.Back, "Back", diameter = 48.dp) { onAction(SubscriptionsAction.CloseChannels) }
                AppText("All subscriptions", Modifier.weight(1f).padding(end = 16.dp), size = 20, line = 28, weight = FontWeight.Bold, maxLines = 1)
                Spacer(Modifier.weight(1f))
            }
            LazyColumn(Modifier.fillMaxSize().testTag("channels-list"),
                contentPadding = PaddingValues(top = 13.dp, bottom = 24.dp)) {
                listItems(channelsFixture(), key = { it.channelId }) { channel ->
                    // Measured row (`library-channels-390.json`): 72px pitch, 48px tap surface,
                    // 48px circle avatar at x12, name 16/400 at x72; no trailing control.
                    Row(Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 12.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(role = Role.Button) { onAction(SubscriptionsAction.OpenChannel(channel.channelId)) }
                        .testTag("channels-row-${channel.channelId}"),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(48.dp).clip(CircleShape)) { SubscriptionsArtwork(channel.avatar, Modifier.fillMaxSize()) }
                        AppText(channel.name, Modifier.padding(start = 12.dp), size = 16, line = 24, maxLines = 1)
                    }
                }
            }
        }
    }
}

private fun channelsFixture() = com.hhst.youtubelite.subscriptions.data.FixtureSubscriptionsRepository.channels()
