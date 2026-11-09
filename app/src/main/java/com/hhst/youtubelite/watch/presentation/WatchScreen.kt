@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hhst.youtubelite.watch.presentation

import com.hhst.youtubelite.ui.design.reasonResources
import com.hhst.youtubelite.ui.design.labelResource
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hhst.youtubelite.R
import com.hhst.youtubelite.playlist.data.FixturePlaylistRepository
import com.hhst.youtubelite.feedback.presentation.FeedbackReason
import com.hhst.youtubelite.feedback.presentation.FeedbackSheet
import com.hhst.youtubelite.saves.domain.NewPlaylistDraft
import com.hhst.youtubelite.saves.domain.SavePlaylist
import com.hhst.youtubelite.saves.presentation.NewPlaylistSheet
import com.hhst.youtubelite.saves.presentation.SaveSheetLive
import com.hhst.youtubelite.saves.presentation.SaveToPlaylistSheet
import com.hhst.youtubelite.ui.design.*
import com.hhst.youtubelite.watch.domain.*
import com.hhst.youtubelite.ui.design.appDescription
import androidx.compose.ui.res.stringResource
import com.hhst.youtubelite.dearrow.rememberDeArrowTitle
import com.hhst.youtubelite.dearrow.VideoArtwork

@Composable
fun WatchScreen(
    state: WatchUiState,
    onAction: (WatchAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    fullscreen: Boolean = false,
    playerSlot: @Composable (Modifier) -> Unit = { WatchPlayer(it, onBack) },
    menuContent: @Composable () -> Unit = { WatchVideoMenu(state, onAction) },
    sidebarCovered: Boolean = false,
    drawBackground: Boolean = true,
    /** An overlay host still needs the embedded destination measured after rotation. */
    keepPlayerSlotInFullscreen: Boolean = false,
) {
    val showPlayer = !fullscreen || keepPlayerSlotInFullscreen
    val mainScroll = rememberSaveable(state.videoId, saver = LazyListState.Saver) { LazyListState() }
    val sidebarMainScroll = rememberSaveable(state.videoId, saver = LazyListState.Saver) { LazyListState() }
    val compactRelatedScroll = rememberSaveable(state.videoId, saver = LazyListState.Saver) { LazyListState() }
    val wideRelatedScroll = rememberSaveable(state.videoId, saver = LazyListState.Saver) { LazyListState() }
    AppTheme {
        Box(modifier.fillMaxSize().then(if (drawBackground) Modifier.background(DesignTokens.Background) else Modifier).testTag("watch-screen")) {
            BoxWithConstraints(
                Modifier.fillMaxSize().then(
                    if (state.overlay is WatchOverlay.ItemMenu || state.overlay is WatchOverlay.Save ||
                        state.overlay is WatchOverlay.Feedback) Modifier.clearAndSetSemantics {} else Modifier,
                ),
            ) {
                val width = maxWidth.value
                val height = maxHeight.value
                val sidebarMode = WatchLayout.sidebar(width, height)
                val columnWidth = WatchLayout.columnWidth(width, height)
                val playerHeight = WatchLayout.playerHeight(width, height)
                val pinned = WatchLayout.pinnedPlayer(width, height)

                if (sidebarMode) {
                    Row(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            WatchMainBody(
                                state = state,
                                scroll = sidebarMainScroll,
                                viewportWidth = width,
                                viewportHeight = height,
                                playerHeight = playerHeight,
                                pinned = pinned,
                                includeRelated = false,
                                showPlayer = showPlayer,
                                playerSlot = playerSlot,
                                onAction = onAction,
                            )
                            if (pinned && showPlayer) {
                                // The player is fixed over the main column only.
                                Box(
                                    Modifier.align(Alignment.TopStart)
                                        .padding(top = WatchLayout.TopBarHeight.dp)
                                        .fillMaxWidth()
                                        .height(playerHeight.dp),
                                ) {
                                    playerSlot(Modifier.fillMaxSize().testTag("watch-player-slot"))
                                }
                            }
                        }
                        Box(if (sidebarCovered) Modifier.clearAndSetSemantics {} else Modifier) {
                            WatchSidebarPane(state, width, if (WatchLayout.sidebarWidth(width) >= WatchLayout.SidebarWideWidth) wideRelatedScroll else compactRelatedScroll, onAction)
                        }
                    }
                } else {
                    WatchMainBody(
                        state = state,
                        scroll = mainScroll,
                        viewportWidth = width,
                        viewportHeight = height,
                        playerHeight = playerHeight,
                        pinned = pinned,
                        includeRelated = true,
                        showPlayer = showPlayer,
                        playerSlot = playerSlot,
                        onAction = onAction,
                    )
                    if (pinned && showPlayer) {
                        Box(
                            Modifier.align(Alignment.TopStart)
                                .padding(top = WatchLayout.TopBarHeight.dp)
                                .width(columnWidth.dp)
                                .height(playerHeight.dp),
                        ) {
                            playerSlot(Modifier.fillMaxSize().testTag("watch-player-slot"))
                        }
                    }
                }
            }
            menuContent()
        }
    }
}

@Composable
internal fun WatchVideoMenu(state: WatchUiState, onAction: (WatchAction) -> Unit) {
    when (val overlay = state.overlay) {
        is WatchOverlay.ItemMenu -> MenuBottomSheet(
            items = watchVideoMenuActions(state.live, (state.content as? WatchContent.Ready)?.page?.related?.firstOrNull { it.id == overlay.videoId }).map {
                MenuSheetItem(it.icon(), stringResource(it.labelResource), "watch-menu-${it.name}")
            },
            onDismiss = { onAction(WatchAction.CloseOverlay) },
            onSelect = { index -> onAction(WatchAction.MenuAction(watchVideoMenuActions(state.live, (state.content as? WatchContent.Ready)?.page?.related?.firstOrNull { it.id == overlay.videoId })[index])) },
            tag = "watch-video-menu",
            paneTitle = stringResource(R.string.ui_video_actions),
            fillWatchHeight = false,
            header = if (state.menuLoading || state.menuFailed) ({
                Box(Modifier.fillMaxWidth().heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
                    if (state.menuLoading) Box(Modifier.testTag("watch-menu-loading")) { com.hhst.youtubelite.home.presentation.LoadingSpinner() }
                    else AppText(stringResource(R.string.retry), modifier = Modifier.testTag("watch-menu-retry")
                        .clickable { onAction(WatchAction.RetryMenu) }.padding(12.dp))
                }
            }) else null,
        )
        is WatchOverlay.Save -> if (overlay.newPlaylist) NewPlaylistSheet(
            draft = NewPlaylistDraft(overlay.title, overlay.description),
            onDismiss = { onAction(WatchAction.CloseOverlay) },
            onDraftChange = { onAction(WatchAction.SaveDraftChanged(it.title, it.description)) },
            onSubmit = { onAction(WatchAction.SubmitNewPlaylist) },
            pending = overlay.pending,
        ) else if (overlay.live) SaveToPlaylistSheet(
            playlists = emptyList(),
            onDismiss = { onAction(WatchAction.CloseOverlay) },
            onSelect = {},
            onNewPlaylist = { onAction(WatchAction.OpenNewPlaylist) },
            live = SaveSheetLive(
                choices = overlay.choices,
                failed = overlay.choicesFailed,
                pending = overlay.pending,
                onToggle = { onAction(WatchAction.SaveChoiceToggled(it)) },
                onRetry = { onAction(WatchAction.SaveChoicesRetry) },
            ),
        ) else SaveToPlaylistSheet(
            playlists = savePlaylists,
            onDismiss = { onAction(WatchAction.CloseOverlay) },
            onSelect = { onAction(WatchAction.SaveDestinationSelected(it)) },
            onNewPlaylist = { onAction(WatchAction.OpenNewPlaylist) },
        )
        is WatchOverlay.Feedback -> FeedbackSheet(
            title = stringResource(overlay.kind.labelResource),
            reasons = overlay.kind.reasons.mapIndexed { index, reason ->
                FeedbackReason(reason, "feedback-reason-$index", overlay.kind.reasonResources[index])
            },
            onDismiss = { onAction(WatchAction.CloseOverlay) },
            onSelect = { onAction(WatchAction.FeedbackReason(it.label)) },
        )
        else -> Unit
    }
}

private val savePlaylists: List<SavePlaylist> =
    FixturePlaylistRepository.personalLists()
        .filter { it.id != FixturePlaylistRepository.WATCH_LATER }
        .map { SavePlaylist(it.id, it.title) }

private fun WatchVideoMenuAction.icon(): AppIcon = when (this) {
    WatchVideoMenuAction.Queue -> AppIcon.QueueAdd
    WatchVideoMenuAction.Download -> AppIcon.Download
    WatchVideoMenuAction.WatchLater -> AppIcon.Clock
    WatchVideoMenuAction.SaveToPlaylist -> AppIcon.Bookmark
    WatchVideoMenuAction.Share -> AppIcon.Share
    WatchVideoMenuAction.NotInterested -> AppIcon.Block
    WatchVideoMenuAction.DontRecommend -> AppIcon.Close
}

@Composable
private fun WatchMainBody(
    state: WatchUiState,
    scroll: LazyListState,
    viewportWidth: Float,
    viewportHeight: Float,
    playerHeight: Float,
    pinned: Boolean,
    includeRelated: Boolean,
    showPlayer: Boolean,
    playerSlot: @Composable (Modifier) -> Unit,
    onAction: (WatchAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (includeRelated) RelatedPaging(state, scroll, onAction)
    val columns = if (WatchLayout.relatedVariant(viewportWidth, viewportHeight) == WatchRelatedVariant.Grid)
        WatchLayout.gridColumns(viewportWidth) else 1
    val related = (state.content as? WatchContent.Ready)?.page?.related.orEmpty()
    val relatedRows = remember(related, columns) { related.chunked(columns) }
    Box(modifier.fillMaxSize().clipToBounds()) {
    LazyColumn(Modifier.fillMaxSize().testTag("watch-scroll"), state = scroll) {
        item("player") { Spacer(Modifier.height(playerHeight.dp)) }
        when (val content = state.content) {
            WatchContent.Loading -> item("loading") {
                val known = state.provisional
                if (known == null) WatchSkeleton(includeRelated) else WatchProvisionalHeader(known)
            }
            WatchContent.Failed -> item("error") {
                if (state.verification != null) VerificationCard(state.verification,
                    onVerify = { onAction(WatchAction.OpenVerification) },
                    onDismiss = { onAction(WatchAction.DismissVerification) },
                    modifier = Modifier.testTag("watch-verification")) else WatchError(onAction)
            }
            is WatchContent.Ready -> {
                item("metadata") { WatchMetadata(content.page.video, state, onAction) }
                item("comments") { WatchComments(content.page.video, onAction) }
                if (includeRelated) {
                    val grid = columns > 1
                    item("related-start") { Spacer(Modifier.height(if (grid) 8.dp else 12.dp)) }
                    items(relatedRows, key = { row -> "related:${row.first().id}" }, contentType = { "related-row" }) { row ->
                        WatchRelatedFeed(row, viewportWidth, viewportHeight, onAction)
                    }
                    item("related-end") { Spacer(Modifier.height(8.dp)) }
                    item("pagination") { RelatedFooter(state, onAction) }
                }
            }
        }
    }
    if (!pinned && showPlayer) {
        val density = LocalDensity.current
        playerSlot(Modifier.fillMaxWidth().height(playerHeight.dp).offset {
            androidx.compose.ui.unit.IntOffset(0, if (scroll.firstVisibleItemIndex == 0)
                -scroll.firstVisibleItemScrollOffset else -with(density) { playerHeight.dp.roundToPx() })
        }.testTag("watch-player-slot"))
    }
    }
}

@Composable
private fun RelatedPaging(state: WatchUiState, scroll: LazyListState, onAction: (WatchAction) -> Unit) {
    val latestAction by rememberUpdatedState(onAction)
    val cursor = (state.content as? WatchContent.Ready)?.page?.nextKey
    LaunchedEffect(scroll, cursor, state.relatedFailed) {
        if (cursor == null || state.relatedFailed) return@LaunchedEffect
        snapshotFlow { scroll.layoutInfo.let { it.totalItemsCount > 0 &&
            (it.visibleItemsInfo.lastOrNull()?.index ?: 0) >= it.totalItemsCount - 3 } }
            .collect { if (it) latestAction(WatchAction.LoadRelated) }
    }
}

@Composable
private fun RelatedFooter(state: WatchUiState, onAction: (WatchAction) -> Unit) {
    if (state.relatedLoading) Box(Modifier.fillMaxWidth().height(56.dp).testTag("watch-related-loading"), contentAlignment = Alignment.Center) {
        androidx.compose.material3.CircularProgressIndicator(Modifier.size(24.dp), color = DesignTokens.Ink, strokeWidth = 2.dp)
    }
    if (state.relatedFailed) Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("watch-related-retry")
        .clickable { onAction(WatchAction.LoadRelated) }, contentAlignment = Alignment.Center) { AppText(stringResource(R.string.retry)) }
}

/**
 * Paints the tapped card's known fields while the page read is in flight; unknown
 * slots stay blank, no inert controls render, and the real block replaces this
 * without a full-page reset.
 */
@Composable
private fun WatchProvisionalHeader(known: WatchProvisional) {
    Column(Modifier.fillMaxWidth().testTag("watch-provisional")) {
        Column(Modifier.padding(horizontal = 3.dp)) {
            Column(Modifier.padding(start = 13.dp, end = 13.dp, top = 12.dp, bottom = 5.dp)) {
                WatchTitle(rememberDeArrowTitle(known.videoId, known.title))
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth().heightIn(min = 18.dp).testTag("watch-meta"),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (known.live) {
                        Box(Modifier.padding(end = 8.dp).background(DesignTokens.Red, RoundedCornerShape(2.dp))
                            .padding(horizontal = 4.dp)) {
                            AppText(stringResource(R.string.player_live), size = 12, line = 18, weight = FontWeight.Medium, color = Color.White)
                        }
                    }
                    if (known.views.isNotBlank()) AppText(known.views, Modifier.padding(end = 8.dp), size = 12, line = 18, color = DesignTokens.Secondary)
                    if (!known.live && known.age.isNotBlank()) AppText(known.age, size = 12, line = 18, color = DesignTokens.Secondary)
                }
            }
            Spacer(Modifier.height(3.dp))
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).testTag("watch-action-avatar"), contentAlignment = Alignment.Center) {
                Artwork(known.avatar.orEmpty(),
                    Modifier.size(32.dp).clip(CircleShape))
            }
            AppText(known.channel, Modifier.weight(1f).padding(end = 8.dp).testTag("watch-channel-name"),
                size = 14, line = 20, weight = FontWeight.Medium, maxLines = 2)
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun WatchSkeleton(includeRelated: Boolean) {
    Column(Modifier.fillMaxWidth().testTag("watch-skeleton").clearAndSetSemantics {}) {
        Column(Modifier.padding(horizontal = 3.dp)) {
            Column(Modifier.padding(start = 13.dp, end = 13.dp, top = 12.dp, bottom = 5.dp)) {
                Box(
                    Modifier.fillMaxWidth(0.72f).height(20.dp)
                        .background(DesignTokens.Ghost, RoundedCornerShape(2.dp)),
                )
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier.width(196.dp).height(12.dp)
                        .background(DesignTokens.Ghost, RoundedCornerShape(2.dp)),
                )
            }
            Spacer(Modifier.height(3.dp))
        }
        Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(32.dp).clip(CircleShape).background(DesignTokens.Ghost))
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f).height(16.dp).background(DesignTokens.Ghost, RoundedCornerShape(2.dp)))
            Spacer(Modifier.width(24.dp))
            Box(Modifier.size(78.dp, 32.dp).background(DesignTokens.Ghost, RoundedCornerShape(16.dp)))
        }
        Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            repeat(5) { Box(Modifier.weight(1f).height(36.dp).background(DesignTokens.Ghost, RoundedCornerShape(18.dp))) }
        }
        Column(Modifier.fillMaxWidth().height(WatchLayout.CommentsSectionHeight.dp)) {
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    .height(WatchLayout.CommentsCardHeight.dp)
                    .clip(RoundedCornerShape(12.dp)).background(DesignTokens.Ghost),
            )
        }
        if (includeRelated) {
            Column(Modifier.padding(top = 12.dp)) {
                repeat(4) { index ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Box(
                            Modifier.width(160.dp).height(90.dp)
                                .clip(RoundedCornerShape(12.dp)).background(DesignTokens.Ghost),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Box(
                                Modifier.fillMaxWidth().height(16.dp)
                                    .background(DesignTokens.Ghost, RoundedCornerShape(2.dp)),
                            )
                            Spacer(Modifier.height(8.dp))
                            Box(
                                Modifier.width(120.dp).height(14.dp)
                                    .background(DesignTokens.Ghost, RoundedCornerShape(2.dp)),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WatchError(onAction: (WatchAction) -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(24.dp).testTag("watch-error"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AppText(stringResource(R.string.ui_something_went_wrong), size = 20, line = 28, weight = FontWeight.Bold)
        AppText(
            stringResource(R.string.ui_please_try_again),
            Modifier.padding(top = 8.dp, bottom = 20.dp),
            color = DesignTokens.Secondary,
        )
        Box(
            Modifier.clip(CircleShape).background(DesignTokens.Surface)
                .clickable(role = Role.Button) { onAction(WatchAction.Retry) }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            AppText(stringResource(R.string.retry), weight = FontWeight.Medium)
        }
    }
}

@Composable
private fun WatchMetadata(video: WatchVideo, state: WatchUiState, onAction: (WatchAction) -> Unit) {
    val motionScale = LocalMotionScale.current
    Column(Modifier.fillMaxWidth().testTag("watch-metadata")) {
        Column(Modifier.padding(horizontal = 3.dp)) {
            Column(
                Modifier.padding(start = 13.dp, end = 13.dp, top = 12.dp, bottom = 5.dp),
            ) {
                WatchTitle(rememberDeArrowTitle(video.id, video.title))
                Spacer(Modifier.height(6.dp))
                WatchMetaRow(video, state, onAction)
                androidx.compose.animation.AnimatedVisibility(state.descriptionExpanded,
                    enter = androidx.compose.animation.expandVertically(androidx.compose.animation.core.tween(motionDuration(AppMotion.ExpandMs, motionScale))),
                    exit = androidx.compose.animation.shrinkVertically(androidx.compose.animation.core.tween(motionDuration(AppMotion.CollapseMs, motionScale)))) {
                    Column {
                    Spacer(Modifier.height(12.dp))
                    WatchDescription(
                        video.description,
                        onTimestamp = { onAction(WatchAction.SeekToTimestamp(it)) },
                        onLink = { onAction(WatchAction.OpenDescriptionLink(it)) },
                        modifier = Modifier.fillMaxWidth().testTag("watch-description"),
                    )
                    }
                }
            }
            Spacer(Modifier.height(3.dp))
        }
        WatchActionBar(video, state.actionPending, state.pendingVideoAction, state.live, onAction)
    }
}

@Composable
private fun WatchTitle(title: String) {
    BasicText(
        title,
        Modifier.fillMaxWidth().testTag("watch-title")
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen).drawWithCache {
            val fade = Brush.horizontalGradient(0.9f to Color.Black, 1f to Color.Transparent)
            onDrawWithContent {
                drawContent()
                drawRect(fade, blendMode = BlendMode.DstIn)
            }
        },
        style = DesignTokens.text(18, 26, FontWeight.Black),
        maxLines = 1,
        overflow = TextOverflow.Clip,
    )
}

@Composable
private fun WatchMetaRow(video: WatchVideo, state: WatchUiState, onAction: (WatchAction) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 18.dp).horizontalScroll(rememberScrollState()).testTag("watch-meta"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (video.live) {
            Box(Modifier.padding(end = 8.dp).background(DesignTokens.Red, RoundedCornerShape(2.dp)).padding(horizontal = 4.dp).testTag("watch-live-badge")) {
                AppText(stringResource(R.string.player_live), size = 12, line = 18, weight = FontWeight.Medium, color = Color.White)
            }
        }
        if (video.handle.isNotBlank()) AppText(video.handle, Modifier.padding(end = 8.dp), size = 12, line = 18, weight = FontWeight.Medium)
        if (video.views.isNotBlank()) AppText(video.views, Modifier.padding(end = 8.dp), size = 12, line = 18, color = DesignTokens.Secondary)
        if (!video.live && video.age.isNotBlank()) AppText(video.age, Modifier.padding(end = 8.dp), size = 12, line = 18, color = DesignTokens.Secondary)
        AppText(
            stringResource(R.string.ui_more),
            Modifier.testTag("watch-more").appDescription(if (state.descriptionExpanded) R.string.ui_show_less else R.string.ui_show_more).clickable(role = Role.Button) { onAction(WatchAction.ToggleDescription) },
            size = 12,
            line = 18,
            weight = FontWeight.Medium,
        )
    }
}

@Composable
private fun WatchActionBar(video: WatchVideo, pending: Boolean, pendingAction: WatchAction?, live: Boolean, onAction: (WatchAction) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).testTag("watch-action-avatar").appDescription(R.string.a11y_go_to_channel, video.channel)
                    .clickable(enabled = video.channelId.isNotBlank(), role = Role.Button) { onAction(WatchAction.OpenChannel(video.channelId)) }, contentAlignment = Alignment.Center) {
                    Artwork(video.avatar.orEmpty(), Modifier.size(32.dp).clip(CircleShape))
                }
                AppText(video.channel, Modifier.weight(1f).padding(end = 8.dp).testTag("watch-channel-name")
                    .clickable(enabled = video.channelId.isNotBlank(), role = Role.Button) { onAction(WatchAction.OpenChannel(video.channelId)) },
                    size = 14, line = 20, weight = FontWeight.Medium, maxLines = 2)
            }
            SubscribeButton(video.subscribed, { onAction(WatchAction.Subscribe) },
                Modifier.testTag("watch-action-subscribe"), enabled = video.channelId.isNotBlank() && !pending,
                pending = pendingAction == WatchAction.Subscribe, onDarkSurface = false, channelId = video.channelId)
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("watch-actions")
            .padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf(
                Triple(AppIcon.Like, stringResource(R.string.ui_like_this_video), WatchAction.Like),
                Triple(AppIcon.Dislike, stringResource(R.string.ui_dislike_this_video), WatchAction.Dislike),
                Triple(AppIcon.Download, stringResource(R.string.download), WatchAction.Download),
                Triple(AppIcon.QueueAdd, stringResource(R.string.add_to_queue), WatchAction.Queue),
                Triple(AppIcon.Bookmark, stringResource(R.string.comments_composer_save), WatchAction.Save),
                Triple(AppIcon.OpenWith, stringResource(R.string.open_with), WatchAction.OpenWith),
                Triple(AppIcon.Share, stringResource(R.string.share), WatchAction.Share),
                Triple(AppIcon.Report, stringResource(R.string.ui_report), WatchAction.Report),
            // Report's submission needs a feedback token the page never carries; it hides in production.
            ).filter { !live || it.third != WatchAction.Report }.forEachIndexed { index, (icon, description, action) ->
                WatchSlotButton(icon, description,
                    label = when (index) { 0 -> video.likeCount ?: video.likes.removeSuffix(" likes").takeIf { it.isNotBlank() }; 1 -> video.dislikeCount; else -> null },
                    selected = (index == 0 && video.rating == VideoRating.Like) || (index == 1 && video.rating == VideoRating.Dislike),
                    enabled = index > 1 || !pending,
                    pending = pendingAction == action,
                    testTag = "watch-action-${listOf("like", "dislike", "download", "queue", "save", "open-with", "share", "report")[index]}",
                    onClick = { onAction(action) })
            }
        }
    }
}

@Composable
private fun WatchSlotButton(
    icon: AppIcon,
    description: String,
    testTag: String,
    onClick: () -> Unit,
    label: String? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    pending: Boolean = false,
) {
    val surfaceHeight = with(LocalDensity.current) { maxOf(36.dp, 20.sp.toDp() + 12.dp) }
    val haptic = com.hhst.youtubelite.ui.design.rememberHapticAction()
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    Box(
        Modifier.heightIn(min = surfaceHeight + 12.dp).widthIn(min = 48.dp)
            .clickable(enabled = enabled, interactionSource = interactions, indication = null, role = Role.Button) { haptic(); onClick() }
            .semantics { contentDescription = description; this.selected = selected }
            .testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        Row(Modifier.heightIn(min = surfaceHeight).clip(RoundedCornerShape(18.dp))
            .background(if (selected) DesignTokens.Red.copy(alpha = 0.12f) else if (pressed) DesignTokens.Ghost else DesignTokens.Surface)
            .testTag("$testTag-surface").padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (pending) com.hhst.youtubelite.ui.design.ActionProgress()
            else AppIcon(icon, Modifier.testTag("$testTag-icon"), color = if (selected) DesignTokens.Red else DesignTokens.Ink)
            if (!label.isNullOrBlank()) {
                Spacer(Modifier.width(8.dp))
                AppText(label, Modifier.testTag("$testTag-count"), size = 14, line = 20, maxLines = 1)
            }
        }
    }
}

@Composable
private fun WatchComments(video: WatchVideo, onAction: (WatchAction) -> Unit) {
    val liveChat = video.live && !video.chatKey.isNullOrBlank()
    val chatFailed = video.live && video.chatLookupFailed
    // A live stream without a chat continuation has no usable chat: show the
    // unavailable card, not an ordinary comments panel.
    val chatUnavailable = video.live && video.chatKey.isNullOrBlank() && !chatFailed
    Column(
        Modifier.fillMaxWidth().testTag("watch-comments").heightIn(min = WatchLayout.CommentsSectionHeight.dp).padding(bottom = 13.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier.fillMaxWidth()
                .padding(horizontal = 16.dp)
                .heightIn(min = WatchLayout.CommentsCardHeight.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(DesignTokens.Surface)
                .testTag("watch-comments-card")
                .clickable(enabled = !chatUnavailable, role = Role.Button) {
                    onAction(if (chatFailed) WatchAction.Retry else WatchAction.OpenComments(video.id))
                }
                .padding(12.dp),
        ) {
            Column {
                Row(Modifier.heightIn(min = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppText(if (video.live) {
                        stringResource(R.string.chat_live)
                    } else stringResource(R.string.comments_title), size = 14, line = 20, weight = FontWeight.Medium)
                    Spacer(Modifier.width(4.dp))
                    if (!video.live) AppText(video.commentCount, size = 14, line = 20, color = DesignTokens.Secondary)
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!video.live && video.commentsStatus == WatchCommentsStatus.Ready && video.topComment.text.isNotBlank()) {
                        Artwork(video.topComment.artwork, Modifier.size(24.dp).clip(CircleShape))
                        Spacer(Modifier.width(12.dp))
                    }
                    AppText(when {
                        liveChat -> stringResource(R.string.chat_join_live)
                        chatFailed -> stringResource(R.string.chat_load_failed)
                        chatUnavailable -> stringResource(R.string.chat_unavailable)
                        else -> when (video.commentsStatus) {
                            WatchCommentsStatus.Loading -> stringResource(R.string.ui_loading_comments)
                            WatchCommentsStatus.Empty -> stringResource(R.string.ui_no_comments_yet_2)
                            WatchCommentsStatus.Disabled -> stringResource(R.string.ui_comments_are_turned_off_2)
                            WatchCommentsStatus.Failed -> stringResource(R.string.ui_couldn_t_load_comments_tap_to_view)
                            WatchCommentsStatus.Ready -> video.topComment.text
                        }
                    }, Modifier.weight(1f).testTag("watch-comment-preview"), size = 14, line = 20, maxLines = 2)
                }
            }
        }
    }
}

@Composable
private fun WatchSidebarPane(state: WatchUiState, viewportWidth: Float, scroll: LazyListState, onAction: (WatchAction) -> Unit) {
    RelatedPaging(state, scroll, onAction)
    val paneWidth = WatchLayout.sidebarWidth(viewportWidth)
    val wide = paneWidth >= WatchLayout.SidebarWideWidth
    Box(
        Modifier.width(paneWidth.dp).fillMaxHeight()
            .padding(top = WatchLayout.TopBarHeight.dp)
            .testTag("watch-related"),
    ) {
        when (val content = state.content) {
            WatchContent.Loading -> Column(
                Modifier.fillMaxWidth().padding(start = 8.dp, end = if (wide) 0.dp else 8.dp)
                    .padding(top = if (wide) 0.dp else 4.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                repeat(3) {
                    Row(Modifier.fillMaxWidth()) {
                        SidebarGhostCell(if (wide) 246f else 240f, if (wide) 238f else 240f)
                        if (wide) SidebarGhostCell(246f, 238f)
                    }
                }
            }
            WatchContent.Failed -> Unit
            is WatchContent.Ready -> {
                val related = content.page.related
                val rows = remember(related) { related.chunked(2) }
                if (wide) {
                    LazyColumn(
                        Modifier.fillMaxWidth().padding(start = 8.dp),
                        state = scroll,
                        contentPadding = PaddingValues(bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        items(rows, key = { row -> "related:${row.first().id}" }, contentType = { "related-row" }) { row ->
                            Row(Modifier.fillMaxWidth()) {
                                row.forEach { SidebarCell(it, 246f, 238f, onAction) }
                            }
                        }
                        item("pagination") { RelatedFooter(state, onAction) }
                    }
                } else {
                    LazyColumn(
                        Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 4.dp),
                        state = scroll,
                        contentPadding = PaddingValues(bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        items(related, key = { it.id }, contentType = { "related" }) { item ->
                            SidebarCell(item, 240f, 240f, onAction)
                        }
                        item("pagination") { RelatedFooter(state, onAction) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SidebarCell(item: WatchRelated, cellWidth: Float, thumbWidth: Float, onAction: (WatchAction) -> Unit) {
    Column(Modifier.width(cellWidth.dp).testTag("watch-related-${item.id}")) {
        Box(
            Modifier.padding(top = 2.dp).width(thumbWidth.dp)
                .height(thumbWidth.dp * 9f / 16f),
        ) {
            RelatedThumb(item, Modifier.fillMaxSize(), radius = 12.dp, onAction = onAction)
        }
        Spacer(Modifier.height(4.dp))
        Row(
            Modifier.fillMaxWidth().padding(end = (cellWidth - thumbWidth).dp),
            verticalAlignment = Alignment.Top,
        ) {
            RelatedDetailsAvatar(item, onAction)
            Spacer(Modifier.width(12.dp))
            RelatedText(
                item,
                Modifier.weight(1f).padding(top = 8.dp),
                titleBottomDp = 8f,
                onAction = onAction,
            )
            RelatedKebab(item, onAction)
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun SidebarGhostCell(cellWidth: Float, thumbWidth: Float) {
    Column(Modifier.width(cellWidth.dp)) {
        Box(
            Modifier.padding(top = 2.dp).width(thumbWidth.dp).height(thumbWidth.dp * 9f / 16f)
                .clip(RoundedCornerShape(12.dp)).background(DesignTokens.Ghost),
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(DesignTokens.Ghost))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).padding(top = 8.dp)) {
                Box(Modifier.fillMaxWidth().height(14.dp).background(DesignTokens.Ghost, RoundedCornerShape(2.dp)))
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth(0.7f).height(12.dp).background(DesignTokens.Ghost, RoundedCornerShape(2.dp)))
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun WatchRelatedFeed(
    items: List<WatchRelated>,
    viewportWidth: Float,
    viewportHeight: Float,
    onAction: (WatchAction) -> Unit,
) {
    when (WatchLayout.relatedVariant(viewportWidth, viewportHeight)) {
        WatchRelatedVariant.Phone -> PhoneFeed(items, viewportWidth, onAction)
        WatchRelatedVariant.Grid -> GridFeed(items, viewportWidth, onAction)
        WatchRelatedVariant.List -> ListFeed(items, onAction)
        WatchRelatedVariant.Stacked -> StackedFeed(items, viewportWidth, onAction)
        WatchRelatedVariant.SidebarCompact, WatchRelatedVariant.SidebarWide ->
            error("Sidebar related layouts render in the pane, not the main feed")
    }
}

@Composable
private fun PhoneFeed(items: List<WatchRelated>, viewportWidth: Float, onAction: (WatchAction) -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items.forEach { item ->
            Column(Modifier.fillMaxWidth().testTag("watch-related-${item.id}")) {
                Box(Modifier.fillMaxWidth().height(WatchLayout.phoneCoverHeight(viewportWidth).dp)) {
                    RelatedThumb(item, Modifier.fillMaxSize(), radius = 0.dp, onAction = onAction)
                }
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.Top) {
                    RelatedDetailsAvatar(item, onAction)
                    Spacer(Modifier.width(12.dp))
                    RelatedText(
                        item,
                        Modifier.weight(1f).padding(top = 8.dp),
                        titleBottomDp = 3f,
                        onAction = onAction,
                    )
                    RelatedKebab(item, onAction)
                }
            }
        }
    }
}

@Composable
private fun StackedFeed(items: List<WatchRelated>, viewportWidth: Float, onAction: (WatchAction) -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items.forEach { item ->
            Column(Modifier.fillMaxWidth().testTag("watch-related-${item.id}")) {
                Box(
                    Modifier.padding(horizontal = 12.dp).fillMaxWidth()
                        .height(((viewportWidth - 24f) * 9f / 16f).dp),
                ) {
                    RelatedThumb(item, Modifier.fillMaxSize(), radius = 12.dp, onAction = onAction)
                }
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.Top) {
                    RelatedDetailsAvatar(item, onAction)
                    Spacer(Modifier.width(12.dp))
                    RelatedText(
                        item,
                        Modifier.weight(1f).padding(top = 8.dp),
                        titleBottomDp = 3f,
                        onAction = onAction,
                    )
                    RelatedKebab(item, onAction)
                }
            }
        }
    }
}

@Composable
private fun ListFeed(items: List<WatchRelated>, onAction: (WatchAction) -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items.forEach { item ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                    .testTag("watch-related-${item.id}"),
                verticalAlignment = Alignment.Top,
            ) {
                Box(
                    Modifier.width(WatchLayout.ListCoverWidth.dp).height(WatchLayout.ListCoverHeight.dp),
                ) {
                    RelatedThumb(item, Modifier.fillMaxSize(), radius = 12.dp, onAction = onAction)
                }
                Spacer(Modifier.width(12.dp))
                RelatedText(item, Modifier.weight(1f), titleBottomDp = 3f, onAction = onAction)
                RelatedKebab(item, onAction)
            }
        }
    }
}

@Composable
private fun GridFeed(items: List<WatchRelated>, viewportWidth: Float, onAction: (WatchAction) -> Unit) {
    val cols = WatchLayout.gridColumns(viewportWidth)
    val cell = WatchLayout.gridCell(viewportWidth)
    Column(Modifier.fillMaxWidth()) {
        items.chunked(cols).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(start = 8.dp)) {
                row.forEach { item ->
                    Column(Modifier.width(cell.dp).testTag("watch-related-${item.id}")) {
                        Box(
                            Modifier.padding(horizontal = 8.dp).fillMaxWidth()
                                .height(((cell - 16f) * 9f / 16f).dp),
                        ) {
                            RelatedThumb(item, Modifier.fillMaxSize(), radius = 12.dp, onAction = onAction)
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.Top) {
                            RelatedDetailsAvatar(item, onAction)
                            Spacer(Modifier.width(12.dp))
                            RelatedText(
                                item,
                                Modifier.weight(1f).padding(top = 8.dp),
                                titleBottomDp = 3f,
                                onAction = onAction,
                            )
                            RelatedKebab(item, onAction)
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun RelatedThumb(
    item: WatchRelated,
    modifier: Modifier,
    radius: Dp,
    onAction: (WatchAction) -> Unit,
) {
    Box(
        modifier.clip(RoundedCornerShape(radius))
            .clickable(role = Role.Button) {
                WatchProvisionalStore.put(item.toProvisional())
                onAction(WatchAction.OpenRelated(item.id))
            }
            .appDescription(R.string.a11y_title_duration, item.title, item.duration)
            .testTag("watch-thumb-${item.id}"),
    ) {
        VideoArtwork(item.id, item.artwork, Modifier.fillMaxSize())
        item.progress?.let { progress ->
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0xFF909090))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(DesignTokens.Red))
            }
        }
        Box(
            Modifier.align(Alignment.BottomEnd).padding(8.dp).testTag("watch-badge-${item.id}")
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                .padding(horizontal = 4.dp, vertical = 1.dp),
            contentAlignment = Alignment.Center,
        ) {
            AppText(item.duration, size = 12, line = 18, weight = FontWeight.Medium, color = Color.White)
        }
    }
}

@Composable
private fun RelatedText(
    item: WatchRelated,
    modifier: Modifier,
    titleBottomDp: Float,
    onAction: (WatchAction) -> Unit,
) {
    Column(
        modifier.clickable(role = Role.Button) {
            WatchProvisionalStore.put(item.toProvisional())
            onAction(WatchAction.OpenRelated(item.id))
        },
    ) {
        AppText(
            rememberDeArrowTitle(item.id, item.title),
            Modifier.fillMaxWidth().testTag("watch-related-title-${item.id}"),
            size = 14,
            line = 18,
            weight = FontWeight.Normal,
            maxLines = 2,
            exactLineHeight = 17.5.sp,
        )
        Spacer(Modifier.height(titleBottomDp.dp))
        FlowRow(Modifier.fillMaxWidth()) {
            listOf(item.channel, item.views, item.age).filter { it.isNotBlank() }.forEachIndexed { index, text ->
                if (index > 0) BylineSeparator()
                BylineItem(text)
            }
        }
        item.badge?.let { badge ->
            Spacer(Modifier.height(4.dp))
            Box(Modifier.padding(start = 4.dp)) {
                AppText(badge, size = 12, line = 18, weight = FontWeight.Medium, color = DesignTokens.Secondary)
            }
        }
    }
}

@Composable
private fun BylineItem(text: String) {
    AppText(text, size = 12, line = 14, maxLines = 1, color = DesignTokens.Secondary)
    Spacer(Modifier.width(4.dp))
}

@Composable
private fun BylineSeparator() {
    AppText("•", size = 12, line = 14, color = DesignTokens.Secondary)
    Spacer(Modifier.width(4.dp))
}

@Composable
private fun RelatedDetailsAvatar(item: WatchRelated, onAction: (WatchAction) -> Unit) {
    Box(
        Modifier.size(40.dp).clip(CircleShape)
            .testTag("watch-avatar-${item.id}")
        .clickable(role = Role.Button) { onAction(WatchAction.OpenChannel(item.channelId)) }
            .appDescription(R.string.a11y_go_to_channel, item.channel),
    ) {
        Artwork(item.avatar.orEmpty(), Modifier.fillMaxSize())
    }
}

@Composable
private fun RelatedKebab(item: WatchRelated, onAction: (WatchAction) -> Unit) {
    AppIconButton(AppIcon.More, stringResource(R.string.a11y_more_actions_for, item.title), diameter = 48.dp) {
        onAction(WatchAction.OpenItemMenu(item.id))
    }
}

@Composable
private fun Artwork(asset: String, modifier: Modifier = Modifier) {
    com.hhst.youtubelite.ui.design.Artwork(asset, modifier)
}
