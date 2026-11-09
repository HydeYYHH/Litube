package com.hhst.youtubelite.subscriptions.presentation

import com.hhst.youtubelite.ui.design.labelResource
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items as listItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hhst.youtubelite.R
import com.hhst.youtubelite.home.domain.AppendState
import com.hhst.youtubelite.home.domain.FeedItem
import com.hhst.youtubelite.watch.domain.toProvisional
import com.hhst.youtubelite.home.presentation.LoadingSpinner
import com.hhst.youtubelite.home.presentation.PillButton
import com.hhst.youtubelite.library.domain.SignInPrompt
import com.hhst.youtubelite.library.presentation.LibraryMessageCard
import com.hhst.youtubelite.library.presentation.LibrarySkeleton
import com.hhst.youtubelite.subscriptions.domain.*
import com.hhst.youtubelite.ui.components.MainTab
import com.hhst.youtubelite.ui.components.MainNavigationBar
import com.hhst.youtubelite.ui.design.*
import com.hhst.youtubelite.ui.design.appDescription
import androidx.compose.ui.res.stringResource
import com.hhst.youtubelite.dearrow.rememberDeArrowTitle
import com.hhst.youtubelite.dearrow.VideoArtwork
import kotlin.math.roundToInt

@Composable
fun SubscriptionsScreen(state: SubscriptionsUiState, onAction: (SubscriptionsAction) -> Unit, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxSize().background(DesignTokens.Background).testTag("subscriptions-screen")) {
        val width = maxWidth.value
        val gridState = rememberLazyGridState()
        val channelListState = rememberLazyListState()
        val channelStripState = rememberLazyListState()
        var headerHidden by rememberSaveable { mutableStateOf(false) }
        var displayedChannel by rememberSaveable { mutableStateOf(state.selectedChannel?.channelId) }
        var displayedChip by rememberSaveable { mutableStateOf(state.selectedChip) }
        val headerFraction by animateFloatAsState(if (headerHidden) 0f else 1f,
            com.hhst.youtubelite.ui.design.AppMotion.scrollResponse(LocalMotionScale.current), label = "subscriptions-header")
        val headerScroll = remember(gridState) { object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y > 0f) headerHidden = false
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                // Only hide after the feed moves, so horizontal strips and pull-to-refresh keep the header.
                if (source == NestedScrollSource.UserInput && consumed.y < 0f && gridState.canScrollBackward) headerHidden = true
                return Offset.Zero
            }
        } }
        LaunchedEffect(gridState) {
            snapshotFlow { gridState.canScrollBackward }.collect { scrolled ->
                if (!scrolled) headerHidden = false
            }
        }
        val headerRequired = !state.signedIn || state.overlay == SubscriptionsOverlay.Channels ||
            state.content != SubscriptionsContent.Loading && state.content !is SubscriptionsContent.Feed
        LaunchedEffect(headerRequired) {
            if (headerRequired) headerHidden = false
        }
        val leadingUpdate = (state.channels as? SubscriptionsChannels.Ready)?.channels
            ?.firstOrNull()?.takeIf { it.hasNewContent }?.channelId
        LaunchedEffect(leadingUpdate) {
            // A keyed LazyRow otherwise keeps the previous first channel anchored after reordering.
            if (leadingUpdate != null && state.selectedChannel == null) channelStripState.scrollToItem(0)
        }
        LaunchedEffect(state.selectedChannel?.channelId) {
            val id = state.selectedChannel?.channelId ?: return@LaunchedEffect
            val index = (state.channels as? SubscriptionsChannels.Ready)?.channels?.indexOfFirst { it.channelId == id } ?: -1
            if (index >= 0) channelStripState.animateScrollToItem((index - 1).coerceAtLeast(0))
        }
        LaunchedEffect(state.selectedChannel?.channelId, state.selectedChip) {
            if (displayedChannel != state.selectedChannel?.channelId || displayedChip != state.selectedChip) {
                gridState.scrollToItem(0)
                headerHidden = false
            }
            displayedChannel = state.selectedChannel?.channelId
            displayedChip = state.selectedChip
        }
        val showingChannels = state.overlay == SubscriptionsOverlay.Channels
        Column(Modifier.fillMaxSize().then(if (state.overlay != SubscriptionsOverlay.None && !showingChannels) Modifier.clearAndSetSemantics {} else Modifier)) {
            if (showingChannels) {
                ChannelsOverlay(state.channels, channelListState, onAction, Modifier.weight(1f))
            } else {
                // Measure the full stack (including large text), then release its space as it slides away.
                Column(Modifier.fillMaxWidth().clipToBounds().layout { measurable, constraints ->
                    val header = measurable.measure(constraints.copy(minHeight = 0))
                    val height = (header.height * headerFraction).roundToInt()
                    layout(header.width, height) { header.placeRelative(0, height - header.height) }
                }) {
                    SubscriptionsTopBar(width, state.selectedChannel != null, onAction)
                    if (state.signedIn) {
                        ChannelAvatarStrip(state.channels, state.selectedChannel?.channelId, channelStripState, onAction)
                        val selectedChannel = state.selectedChannel
                        if (selectedChannel == null) SubscriptionsChipStrip(state.selectedChip, state.chips, onAction)
                        else Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                            horizontalArrangement = Arrangement.End) {
                            Row(Modifier.clip(RoundedCornerShape(24.dp)).background(DesignTokens.Surface)
                                .clickable(role = Role.Button) { onAction(SubscriptionsAction.OpenChannel(selectedChannel.channelId)) }
                                .testTag("subscriptions-visit-channel").padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                AppIcon(AppIcon.You, Modifier.size(24.dp))
                                AppText(stringResource(R.string.subscription_visit_channel), weight = FontWeight.Medium)
                            }
                        }
                    }
                }
                RefreshableContent(value = state, refreshing = state.content == SubscriptionsContent.Loading,
                    onRefresh = { onAction(SubscriptionsAction.Retry) }, modifier = Modifier.weight(1f).fillMaxWidth().nestedScroll(headerScroll),
                    enabled = state.signedIn && state.overlay == SubscriptionsOverlay.None, contentKey = Triple(state.signedIn, state.selectedChannel?.channelId, state.selectedChip), tag = "subscriptions-refresh",
                    canPull = { !gridState.canScrollBackward }) { state ->
                    val verification = state.verification
                    when (val content = state.content) {
                    SubscriptionsContent.Loading -> LibrarySkeleton("subscriptions-skeleton")
                    SubscriptionsContent.Failed -> if (verification != null) VerificationCard(
                        verification,
                        onVerify = { onAction(SubscriptionsAction.OpenVerification(verification.challengeUrl)) },
                        onDismiss = { onAction(SubscriptionsAction.DismissVerification) },
                        modifier = Modifier.testTag("subscriptions-verification"),
                    ) else SubscriptionsError { onAction(SubscriptionsAction.Retry) }
                    is SubscriptionsContent.SignedOut -> LibraryMessageCard(
                        content.prompt.title, content.prompt.message, "subscriptions-signed-out",
                    ) { PillButton(content.prompt.action) { onAction(SubscriptionsAction.SignIn) } }
                    is SubscriptionsContent.Feed -> SubscriptionsFeed(content, width, gridState, state.verification, onAction)
                    }
                }
            }
            MainNavigationBar("main", state.signedIn, null, selected = MainTab.Subscriptions) { tab -> onAction(SubscriptionsAction.Destination(tab)) }
        }
    }
}

@Composable
private fun SubscriptionsTopBar(width: Float, selectedChannel: Boolean, onAction: (SubscriptionsAction) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = DesignTokens.TopBarHeight).testTag("subscriptions-topbar"), verticalAlignment = Alignment.CenterVertically) {
        if (selectedChannel) AppIconButton(AppIcon.Back, stringResource(R.string.a11y_back),
            modifier = Modifier.testTag("subscriptions-back-to-all")) { onAction(SubscriptionsAction.SelectChannel(null)) }
        else Box(Modifier.width(if (width < 600f) 53.dp else 48.dp).height(DesignTokens.TopBarHeight)
            .clickable(role = Role.Button) { onAction(SubscriptionsAction.Destination(MainTab.Home)) }, contentAlignment = Alignment.Center) {
            LitubeMark(Modifier.size(24.dp))
        }
        AppText(stringResource(R.string.subscriptions_title), Modifier.weight(1f).testTag("subscriptions-title"), size = 20, line = 28,
            weight = FontWeight.Bold, maxLines = 1)
        AppIconButton(AppIcon.Search, stringResource(R.string.a11y_search_youtube)) { onAction(SubscriptionsAction.OpenSearch) }
    }
}

@Composable
private fun SubscriptionsChipStrip(selected: SubscriptionsChip, chips: List<SubscriptionsChip>, onAction: (SubscriptionsAction) -> Unit) {
    LazyRow(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("subscriptions-chips"), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(horizontal = 16.dp)) {
        listItems(chips) { chip ->
            Box(Modifier.widthIn(min = 48.dp).clip(RoundedCornerShape(8.dp))
                .background(if (chip == selected) DesignTokens.Ink else DesignTokens.Surface)
                .clickable(role = Role.Tab) { onAction(SubscriptionsAction.SelectChip(chip)) }
                .padding(horizontal = 12.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
                AppText(stringResource(chip.labelResource), size = 14, line = 20, weight = FontWeight.Medium,
                    color = if (chip == selected) DesignTokens.Background else DesignTokens.Ink)
            }
        }
    }
}

@Composable
private fun ChannelAvatarStrip(channels: SubscriptionsChannels, selectedChannelId: String?, listState: LazyListState, onAction: (SubscriptionsAction) -> Unit) {
    val allDescription = stringResource(R.string.all_subscriptions)
    Row(Modifier.fillMaxWidth().testTag("subscriptions-channels"), verticalAlignment = Alignment.CenterVertically) {
        LazyRow(Modifier.weight(1f), state = listState,
            verticalAlignment = Alignment.Top, contentPadding = PaddingValues(start = 8.dp, top = 8.dp, bottom = 8.dp)) {
            when (channels) {
                is SubscriptionsChannels.Ready -> listItems(channels.channels, key = { it.channelId }) { channel ->
                    val isSelected = channel.channelId == selectedChannelId
                    val description = if (channel.hasNewContent) stringResource(R.string.subscription_new_activity, channel.name) else channel.name
                    Column(Modifier.width(80.dp).clickable(role = Role.Tab) {
                        onAction(SubscriptionsAction.SelectChannel(channel.channelId))
                    }.semantics(mergeDescendants = true) { contentDescription = description; selected = isSelected }
                        .testTag("subs-strip-${channel.channelId}"), horizontalAlignment = Alignment.CenterHorizontally) {
                        SubscriptionAvatar(channel, 64.dp, "subs-new-${channel.channelId}", isSelected)
                        AppText(channel.name, Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp), size = 12, line = 16,
                            weight = if (isSelected) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
                    }
                }
                SubscriptionsChannels.Loading -> items(4) {
                    Column(Modifier.width(80.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(64.dp).background(DesignTokens.Ghost, CircleShape))
                        Box(Modifier.padding(top = 8.dp).size(48.dp, 12.dp).background(DesignTokens.Ghost, RoundedCornerShape(4.dp)))
                    }
                }
                SubscriptionsChannels.Failed -> item {
                    AppText(stringResource(R.string.retry), Modifier.padding(16.dp).clickable {
                        onAction(SubscriptionsAction.RetryChannels)
                    }, color = DesignTokens.Link)
                }
            }
        }
        Box(Modifier.width(56.dp).heightIn(min = 64.dp).clickable(role = Role.Button) {
                onAction(SubscriptionsAction.ShowChannels)
            }.testTag("subscriptions-manage").semantics { contentDescription = allDescription }, contentAlignment = Alignment.Center) {
            AppText(stringResource(R.string.download_filter_all), size = 14, line = 20,
                color = DesignTokens.Link, weight = FontWeight.Medium)
        }
    }
}

@Composable
private fun SubscriptionAvatar(channel: SubscribedChannel, size: androidx.compose.ui.unit.Dp, badgeTag: String, selected: Boolean = false) {
    Box(Modifier.size(size)) {
        Box(Modifier.fillMaxSize().then(if (selected) Modifier.border(2.dp, DesignTokens.Ink, CircleShape).padding(4.dp) else Modifier)
            .clip(CircleShape)) { SubscriptionsArtwork(channel.avatar, Modifier.fillMaxSize()) }
        if (channel.hasNewContent) {
            Box(Modifier.align(Alignment.BottomEnd).size(12.dp)
                .background(DesignTokens.Background, CircleShape).padding(2.dp)
                .background(DesignTokens.Link, CircleShape).testTag(badgeTag))
        }
    }
}

@Composable
private fun SubscriptionsFeed(content: SubscriptionsContent.Feed, width: Float, gridState: androidx.compose.foundation.lazy.grid.LazyGridState, verification: com.hhst.youtubelite.core.VerificationRequired?, onAction: (SubscriptionsAction) -> Unit) {
    val compact = width < 600f
    val hidden = com.hhst.youtubelite.ui.design.LocalHideShorts.current
    val visibleItems = remember(content.page.items, hidden) { content.page.items.filterNot { hidden && it.short } }
    val nearEnd by remember(gridState, visibleItems.size) { derivedStateOf {
        (gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) >= visibleItems.size - 2
    } }
    androidx.compose.runtime.LaunchedEffect(nearEnd, content.page.nextKey, content.append) {
        // Scroll pages the feed only while it shows rows; an empty filtered feed is
        // continued by the view model within its bound, never by this effect alone.
        if (nearEnd && visibleItems.isNotEmpty() && content.page.nextKey != null && content.append == AppendState.Idle) {
            onAction(SubscriptionsAction.LoadNext)
        }
    }
    Column(Modifier.fillMaxSize()) {
        verification?.let { challenge ->
            VerificationBanner(challenge,
                onVerify = { onAction(SubscriptionsAction.OpenVerification(challenge.challengeUrl)) },
                onDismiss = { onAction(SubscriptionsAction.DismissVerification) },
                modifier = Modifier.padding(start = SubscriptionsLayout.outerPadding(width).dp, end = SubscriptionsLayout.outerPadding(width).dp, top = 8.dp).testTag("subscriptions-verification-banner"))
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(if (compact) 1 else SubscriptionsLayout.columns(width)), state = gridState,
            modifier = Modifier.fillMaxSize().testTag("subscriptions-feed"),
            contentPadding = PaddingValues(start = SubscriptionsLayout.outerPadding(width).dp, end = SubscriptionsLayout.outerPadding(width).dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(DesignTokens.GridGap),
        ) {
            items(visibleItems, key = { it.id }, contentType = { "video" }) { item ->
                Box((if (LocalMotionScale.current > 0f) Modifier.animateItem() else Modifier).padding(top = if (compact) 8.dp else 12.dp, bottom = 12.dp)) {
                    SubscriptionsVideoCard(item, compact = compact, onAction)
                }
            }
            if (visibleItems.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                    when {
                        // The server said the feed ends here; the filter found nothing in it.
                        content.page.nextKey == null ->
                            Box(Modifier.fillMaxWidth().padding(top = 96.dp), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    AppText(stringResource(R.string.ui_no_videos), size = 20, line = 28, weight = FontWeight.Bold)
                                    AppText(stringResource(R.string.ui_new_videos_from_your_subscriptions_will_show_up_here),
                                        Modifier.padding(top = 8.dp), color = DesignTokens.Secondary)
                                }
                            }
                        // An empty filtered page is not the end; the model keeps reading a
                        // bounded stretch, then this row hands control back to the user.
                        content.append == AppendState.Idle && content.emptyAutoLoads >= 3 -> Box(
                            Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center,
                        ) {
                            AppText(
                                stringResource(R.string.load_more),
                                Modifier.clip(RoundedCornerShape(8.dp))
                                    .clickable { onAction(SubscriptionsAction.LoadNext) }.padding(12.dp)
                                    .testTag("subscriptions-load-more"),
                                color = DesignTokens.Secondary,
                            )
                        }
                        else -> Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) {
                            AppText(stringResource(R.string.ui_looking_for_matching_videos), color = DesignTokens.Secondary)
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
        AppText(stringResource(R.string.ui_something_went_wrong_tap_to_retry), Modifier.weight(1f), color = DesignTokens.Secondary)
        PillButton(stringResource(R.string.retry), retry)
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
            .clickable(role = Role.Button) {
                    com.hhst.youtubelite.watch.domain.WatchProvisionalStore.put(item.toProvisional())
                    onAction(SubscriptionsAction.OpenVideo(item.id))
                }
            .appDescription(R.string.a11y_title_duration, item.title, item.duration)) {
            VideoArtwork(item.id, item.artwork, Modifier.fillMaxSize())
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
                }.testTag("subs-channel-${item.channelId}").appDescription(R.string.a11y_go_to_channel, item.author)) {
                    SubscriptionsArtwork(item.avatar.orEmpty(), Modifier.fillMaxSize())
                }
                Spacer(Modifier.width(12.dp))
                Box(Modifier.weight(1f)) {
                    Column(Modifier.padding(end = 48.dp)
                        .clickable(role = Role.Button) {
                    com.hhst.youtubelite.watch.domain.WatchProvisionalStore.put(item.toProvisional())
                    onAction(SubscriptionsAction.OpenVideo(item.id))
                }) {
                        AppText(rememberDeArrowTitle(item.id, item.title), size = 14, exactLineHeight = 17.5.sp, weight = FontWeight.Medium, maxLines = 2)
                        AppText(listOf(item.author, item.metadata).filter(String::isNotBlank).joinToString(" · "), Modifier.padding(top = 3.dp),
                            size = 12, line = 14, color = DesignTokens.Secondary, maxLines = 1)
                    }
                    AppIconButton(AppIcon.More, stringResource(R.string.a11y_more_actions_for, item.title),
                        modifier = Modifier.align(Alignment.TopEnd), diameter = 48.dp) {
                        onAction(SubscriptionsAction.OpenMenu(item.id))
                    }
                }
            }
        } else {
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.size(36.dp).clip(CircleShape).clickable(role = Role.Button) {
                    onAction(SubscriptionsAction.OpenChannel(item.channelId))
                }.testTag("subs-channel-${item.channelId}").appDescription(R.string.a11y_go_to_channel, item.author)) {
                    SubscriptionsArtwork(item.avatar.orEmpty(), Modifier.fillMaxSize())
                }
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    Column(Modifier.clickable(role = Role.Button) {
                    com.hhst.youtubelite.watch.domain.WatchProvisionalStore.put(item.toProvisional())
                    onAction(SubscriptionsAction.OpenVideo(item.id))
                }) {
                        AppText(rememberDeArrowTitle(item.id, item.title), Modifier.padding(end = 24.dp), size = 16, line = 22, weight = FontWeight.Medium, maxLines = 2)
                        AppText(listOf(item.author, item.metadata).filter(String::isNotBlank).joinToString(" · "), Modifier.padding(top = 2.dp), size = 12, line = 18,
                            color = DesignTokens.Secondary, maxLines = 1)
                    }
                    AppIconButton(AppIcon.More, stringResource(R.string.a11y_more_actions_for, item.title),
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
        AppText(stringResource(R.string.ui_something_went_wrong), size = 20, line = 28, weight = FontWeight.Bold)
        AppText(stringResource(R.string.ui_please_try_again), Modifier.padding(top = 8.dp, bottom = 20.dp), color = DesignTokens.Secondary)
        PillButton(stringResource(R.string.retry), onRetry)
    }
}

@Composable
private fun ChannelsOverlay(channels: SubscriptionsChannels, listState: LazyListState, onAction: (SubscriptionsAction) -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(DesignTokens.Background).testTag("channels-overlay")) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().heightIn(min = DesignTokens.TopBarHeight), verticalAlignment = Alignment.CenterVertically) {
                AppIconButton(AppIcon.Back, stringResource(R.string.a11y_back), diameter = 48.dp) { onAction(SubscriptionsAction.CloseChannels) }
                AppText(stringResource(R.string.all_subscriptions), Modifier.weight(1f).padding(end = 16.dp), size = 20, line = 28, weight = FontWeight.Bold, maxLines = 1)
            }
            when (channels) {
                SubscriptionsChannels.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    LoadingSpinner()
                }
                SubscriptionsChannels.Failed -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    AppText(stringResource(R.string.ui_couldn_t_load_channels), size = 20, line = 28, weight = FontWeight.Bold)
                    AppText(stringResource(R.string.ui_your_subscriptions_feed_is_still_available), Modifier.padding(top = 8.dp, bottom = 20.dp),
                        color = DesignTokens.Secondary)
                    PillButton(stringResource(R.string.retry), retryable@{ onAction(SubscriptionsAction.RetryChannels) })
                }
                is SubscriptionsChannels.Ready -> LazyColumn(Modifier.fillMaxSize().testTag("channels-list"), state = listState,
                    contentPadding = PaddingValues(top = 13.dp, bottom = 24.dp)) {
                    if (channels.channels.isEmpty()) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(top = 96.dp), contentAlignment = Alignment.Center) {
                                AppText(stringResource(R.string.ui_no_channels_yet), color = DesignTokens.Secondary)
                            }
                        }
                    }
                    listItems(channels.channels, key = { it.channelId }) { channel ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 80.dp).padding(horizontal = 16.dp, vertical = 8.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(role = Role.Button) { onAction(SubscriptionsAction.SelectChannel(channel.channelId)) }
                            .testTag("channels-row-${channel.channelId}"),
                            verticalAlignment = Alignment.CenterVertically) {
                            SubscriptionAvatar(channel, 48.dp, "channels-new-${channel.channelId}")
                            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                                AppText(channel.name, size = 16, line = 24, maxLines = 1)
                                if (channel.metadata.isNotBlank()) AppText(channel.metadata, size = 14, line = 20, maxLines = 1, color = DesignTokens.Secondary)
                            }
                        }
                    }
                    if (channels.nextKey != null) {
                        item(key = "channels-append") {
                            DirectoryAppendFooter(channels.append) { onAction(SubscriptionsAction.LoadMoreChannels) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DirectoryAppendFooter(append: com.hhst.youtubelite.home.domain.AppendState, onLoadMore: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
        when (append) {
            com.hhst.youtubelite.home.domain.AppendState.Loading -> LoadingSpinner()
            com.hhst.youtubelite.home.domain.AppendState.Failed -> PillButton(stringResource(R.string.retry)) { onLoadMore() }
            com.hhst.youtubelite.home.domain.AppendState.Idle -> PillButton(stringResource(R.string.load_more)) { onLoadMore() }
        }
    }
}
