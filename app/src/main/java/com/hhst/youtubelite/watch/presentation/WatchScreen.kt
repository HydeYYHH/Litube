@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hhst.youtubelite.watch.presentation

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
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
import com.hhst.youtubelite.saves.presentation.SaveToPlaylistSheet
import com.hhst.youtubelite.ui.design.*
import com.hhst.youtubelite.watch.domain.*

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
) {
    val mainScroll = rememberScrollState()
    val sidebarMainScroll = rememberScrollState()
    val compactRelatedScroll = rememberLazyListState()
    val wideRelatedScroll = rememberLazyListState()
    AppTheme {
        Box(modifier.fillMaxSize().background(Color.White).testTag("watch-screen")) {
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
                                showPlayer = !fullscreen,
                                playerSlot = playerSlot,
                                onAction = onAction,
                            )
                            if (pinned && !fullscreen) {
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
                        showPlayer = !fullscreen,
                        playerSlot = playerSlot,
                        onAction = onAction,
                    )
                    if (pinned && !fullscreen) {
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
                // Transparent fixed bar: content scrolls beneath it, exactly like the sampled page.
                WatchTopBar(onAction, onBack)
            }
            menuContent()
        }
    }
}

@Composable
internal fun WatchVideoMenu(state: WatchUiState, onAction: (WatchAction) -> Unit) {
    when (val overlay = state.overlay) {
        is WatchOverlay.ItemMenu -> MenuBottomSheet(
            items = WatchVideoMenuAction.entries.map {
                MenuSheetItem(it.icon(), it.label, "watch-menu-${it.name}")
            },
            onDismiss = { onAction(WatchAction.CloseOverlay) },
            onSelect = { index -> onAction(WatchAction.MenuAction(WatchVideoMenuAction.entries[index])) },
            tag = "watch-video-menu",
            paneTitle = "Video actions",
        )
        is WatchOverlay.Save -> if (overlay.newPlaylist) NewPlaylistSheet(
            draft = NewPlaylistDraft(overlay.title, overlay.description),
            onDismiss = { onAction(WatchAction.CloseOverlay) },
            onDraftChange = { onAction(WatchAction.SaveDraftChanged(it.title, it.description)) },
            onSubmit = { onAction(WatchAction.SubmitNewPlaylist) },
        ) else SaveToPlaylistSheet(
            playlists = savePlaylists,
            onDismiss = { onAction(WatchAction.CloseOverlay) },
            onSelect = { onAction(WatchAction.SaveDestinationSelected(it)) },
            onNewPlaylist = { onAction(WatchAction.OpenNewPlaylist) },
        )
        is WatchOverlay.Feedback -> FeedbackSheet(
            title = overlay.kind.title,
            reasons = overlay.kind.reasons.mapIndexed { index, reason ->
                FeedbackReason(reason, "feedback-reason-$index")
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
    WatchVideoMenuAction.WatchLater -> AppIcon.Clock
    WatchVideoMenuAction.SaveToPlaylist -> AppIcon.Bookmark
    WatchVideoMenuAction.Share -> AppIcon.Share
    WatchVideoMenuAction.NotInterested -> AppIcon.Block
    WatchVideoMenuAction.DontRecommend -> AppIcon.Close
}

@Composable
private fun WatchTopBar(onAction: (WatchAction) -> Unit, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(WatchLayout.TopBarHeight.dp).testTag("watch-topbar"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIconButton(AppIcon.Back, "Back", onClick = onBack)
        Spacer(Modifier.weight(1f))
        AppIconButton(AppIcon.Search, "Search", onClick = { onAction(WatchAction.OpenSearch) })
        AppIconButton(AppIcon.More, "More options", onClick = { onAction(WatchAction.OpenPageMenu) })
        Spacer(Modifier.width(8.dp))
    }
}

@Composable
private fun WatchMainBody(
    state: WatchUiState,
    scroll: ScrollState,
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
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .testTag("watch-scroll"),
    ) {
        Spacer(Modifier.height(WatchLayout.TopBarHeight.dp))
        if (pinned || !showPlayer) {
            Spacer(Modifier.height(playerHeight.dp))
        } else {
            // Wide-short landscape: the player scrolls away with the content.
            playerSlot(
                Modifier.fillMaxWidth()
                    .height(playerHeight.dp)
                    .testTag("watch-player-slot"),
            )
        }
        when (val content = state.content) {
            WatchContent.Loading -> WatchSkeleton(includeRelated)
            WatchContent.Failed -> WatchError(onAction)
            is WatchContent.Ready -> {
                WatchMetadata(content.page.video, state, onAction)
                WatchComments(content.page.video, onAction)
                if (includeRelated) {
                    WatchRelatedFeed(content.page.related, viewportWidth, viewportHeight, onAction)
                }
            }
        }
    }
}

@Composable
private fun WatchSkeleton(includeRelated: Boolean) {
    Column(Modifier.fillMaxWidth().testTag("watch-skeleton")) {
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
        Row(
            Modifier.fillMaxWidth().height(WatchLayout.ActionBarHeight.dp)
                .padding(start = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(32.dp).clip(CircleShape).background(DesignTokens.Ghost))
            Spacer(Modifier.width(16.dp))
            Box(
                Modifier.size(78.dp, 32.dp).clip(RoundedCornerShape(16.dp))
                    .background(DesignTokens.Ghost),
            )
            Spacer(Modifier.width(12.dp))
            Box(
                Modifier.size(135.dp, 48.dp).clip(RoundedCornerShape(24.dp))
                    .background(DesignTokens.Ghost),
            )
            Spacer(Modifier.width(1.dp))
            Box(
                Modifier.size(102.dp, 48.dp).clip(RoundedCornerShape(24.dp))
                    .background(DesignTokens.Ghost),
            )
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
        AppText("Something went wrong", size = 20, line = 28, weight = FontWeight.Bold)
        AppText(
            "Please try again.",
            Modifier.padding(top = 8.dp, bottom = 20.dp),
            color = DesignTokens.Secondary,
        )
        Box(
            Modifier.clip(CircleShape).background(DesignTokens.Surface)
                .clickable(role = Role.Button) { onAction(WatchAction.Retry) }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            AppText("Retry", weight = FontWeight.Medium)
        }
    }
}

@Composable
private fun WatchMetadata(video: WatchVideo, state: WatchUiState, onAction: (WatchAction) -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("watch-metadata")) {
        Column(Modifier.padding(horizontal = 3.dp)) {
            Column(
                Modifier.padding(start = 13.dp, end = 13.dp, top = 12.dp, bottom = 5.dp),
            ) {
                WatchTitle(video.title)
                Spacer(Modifier.height(6.dp))
                WatchMetaRow(video, state, onAction)
                if (state.descriptionExpanded) {
                    Spacer(Modifier.height(12.dp))
                    AppText(
                        video.description,
                        Modifier.fillMaxWidth().testTag("watch-description"),
                        size = 14,
                        line = 20,
                        color = DesignTokens.Secondary,
                    )
                }
            }
            Spacer(Modifier.height(3.dp))
        }
        WatchActionBar(video, onAction)
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
            // The live badge replaces the age text for currently-live entries.
            Box(Modifier.padding(end = 8.dp).background(DesignTokens.Red, RoundedCornerShape(2.dp)).padding(horizontal = 4.dp).testTag("watch-live-badge")) {
                AppText("LIVE", size = 12, line = 18, weight = FontWeight.Medium, color = Color.White)
            }
        }
        AppText(video.handle, Modifier.padding(end = 8.dp), size = 12, line = 18, weight = FontWeight.Medium)
        AppText(video.likes, Modifier.padding(end = 8.dp), size = 12, line = 18, color = DesignTokens.Secondary)
        AppText(video.views, Modifier.padding(end = 8.dp), size = 12, line = 18, color = DesignTokens.Secondary)
        if (!video.live) AppText(video.age, Modifier.padding(end = 8.dp), size = 12, line = 18, color = DesignTokens.Secondary)
        AppText(
            "more",
            Modifier.testTag("watch-more").semantics {
                contentDescription = if (state.descriptionExpanded) "Show less" else "Show more"
            }.clickable(role = Role.Button) { onAction(WatchAction.ToggleDescription) },
            size = 12,
            line = 18,
            weight = FontWeight.Medium,
        )
    }
}

@Composable
private fun WatchActionBar(video: WatchVideo, onAction: (WatchAction) -> Unit) {
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(WatchLayout.ActionBarHeight.dp).testTag("watch-actions"),
    ) {
        val available = maxWidth.value
        val growth = (available - WatchLayout.ActionRowNaturalEnd - WatchLayout.ActionGrowEndGap)
            .coerceAtLeast(0f)

        Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
            Row(
                Modifier.fillMaxHeight()
                    .padding(start = WatchLayout.ActionRowPadStart.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(48.dp).testTag("watch-action-avatar")
                        .clickable(role = Role.Button) { onAction(WatchAction.OpenChannel(video.channelId)) }
                        .semantics { contentDescription = "Go to channel ${video.channel}" },
                    contentAlignment = Alignment.Center,
                ) {
                    Artwork(video.artwork, Modifier.size(32.dp).clip(CircleShape))
                }
                Box(
                    Modifier.widthIn(min = 78.dp).heightIn(min = 32.dp)
                        .clip(RoundedCornerShape(16.dp)).background(DesignTokens.Ink)
                        .clickable(role = Role.Button) { onAction(WatchAction.Subscribe) }
                        .testTag("watch-action-subscribe")
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    AppText(
                        "Subscribe",
                        size = 12,
                        line = 16,
                        weight = FontWeight.Medium,
                        color = Color(0xFFF1F1F1),
                    )
                }
                Spacer(Modifier.width((12f + growth).dp))
                Box(Modifier.width(356.dp).height(WatchLayout.ActionBarHeight.dp)) {
                    listOf(
                        Triple(AppIcon.Like, "Like this video", WatchAction.Like),
                        Triple(AppIcon.Dislike, "Dislike this video", WatchAction.Dislike),
                        Triple(AppIcon.Download, "Download", WatchAction.Download),
                        Triple(AppIcon.QueueAdd, "Add to queue", WatchAction.Queue),
                        Triple(AppIcon.OpenWith, "Open with", WatchAction.OpenWith),
                        Triple(AppIcon.Share, "Share", WatchAction.Share),
                        Triple(AppIcon.Bookmark, "Save", WatchAction.Save),
                        Triple(AppIcon.Report, "Report", WatchAction.Report),
                    ).forEachIndexed { index, (icon, description, action) ->
                        val offsetX = when {
                            index < 2 -> index * 41f
                            index < 5 -> 82f + (index - 2) * 48f
                            else -> 226f + (index - 5) * 41f
                        }
                        WatchSlotButton(
                            icon = icon, description = description,
                            sizeWidth = 48f, sizeHeight = 48f, radius = 24f,
                            offsetX = offsetX, offsetY = 0f,
                            compactSurface = index in 2..4,
                            testTag = "watch-action-${listOf("like", "dislike", "download", "queue", "open-with", "share", "save", "report")[index]}",
                            onClick = { onAction(action) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WatchSlotButton(
    icon: AppIcon,
    description: String,
    sizeWidth: Float,
    sizeHeight: Float,
    radius: Float,
    offsetX: Float,
    offsetY: Float,
    testTag: String,
    onClick: () -> Unit,
    compactSurface: Boolean = false,
) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    Box(
        Modifier.offset(x = offsetX.dp, y = offsetY.dp)
            .size(sizeWidth.dp, sizeHeight.dp)
            .clip(RoundedCornerShape(radius.dp))
            .background(if (pressed && !compactSurface) DesignTokens.Surface else Color.Transparent)
            .clickable(interactionSource = interactions, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        if (compactSurface) {
            Box(Modifier.size(40.dp, 36.dp).clip(RoundedCornerShape(18.dp))
                .background(if (pressed) DesignTokens.Surface else Color.Transparent)
                .testTag("$testTag-surface"), contentAlignment = Alignment.Center) { AppIcon(icon) }
        } else AppIcon(icon)
    }
}

@Composable
private fun WatchComments(video: WatchVideo, onAction: (WatchAction) -> Unit) {
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
                .clickable(role = Role.Button) { onAction(WatchAction.OpenComments(video.id)) }
                .padding(12.dp),
        ) {
            Column {
                Row(Modifier.heightIn(min = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppText("Comments", size = 14, line = 20, weight = FontWeight.Medium)
                    Spacer(Modifier.width(4.dp))
                    AppText(video.commentCount, size = 14, line = 20, color = DesignTokens.Secondary)
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                    Artwork(video.topComment.artwork, Modifier.size(24.dp).clip(CircleShape))
                    Spacer(Modifier.width(12.dp))
                    AppText(
                        video.topComment.text,
                        Modifier.weight(1f),
                        size = 14,
                        line = 20,
                        maxLines = 2,
                    )
                }
            }
        }
    }
}

@Composable
private fun WatchSidebarPane(state: WatchUiState, viewportWidth: Float, scroll: LazyListState, onAction: (WatchAction) -> Unit) {
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
                if (wide) {
                    LazyColumn(
                        Modifier.fillMaxWidth().padding(start = 8.dp),
                        state = scroll,
                        contentPadding = PaddingValues(bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        items(related.chunked(2), key = { row -> row.joinToString("-") { it.id } }) { row ->
                            Row(Modifier.fillMaxWidth()) {
                                row.forEach { SidebarCell(it, 246f, 238f, onAction) }
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 4.dp),
                        state = scroll,
                        contentPadding = PaddingValues(bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        items(related, key = { it.id }) { item ->
                            SidebarCell(item, 240f, 240f, onAction)
                        }
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
        Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 20.dp),
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
        Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 20.dp),
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
        Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 20.dp),
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
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp)) {
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
            .clickable(role = Role.Button) { onAction(WatchAction.OpenRelated(item.id)) }
            .semantics { contentDescription = "${item.title}, ${item.duration}" }
            .testTag("watch-thumb-${item.id}"),
    ) {
        Artwork(item.artwork, Modifier.fillMaxSize())
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
        modifier.clickable(role = Role.Button) { onAction(WatchAction.OpenRelated(item.id)) },
    ) {
        AppText(
            item.title,
            Modifier.fillMaxWidth().testTag("watch-related-title-${item.id}"),
            size = 14,
            line = 18,
            weight = FontWeight.Normal,
            maxLines = 2,
            exactLineHeight = 17.5.sp,
        )
        Spacer(Modifier.height(titleBottomDp.dp))
        FlowRow(Modifier.fillMaxWidth()) {
            BylineItem(item.channel)
            BylineSeparator()
            BylineItem(item.views)
            BylineSeparator()
            BylineItem(item.age)
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
            .semantics { contentDescription = "Go to channel ${item.channel}" },
    ) {
        Artwork(item.artwork, Modifier.fillMaxSize())
    }
}

@Composable
private fun RelatedKebab(item: WatchRelated, onAction: (WatchAction) -> Unit) {
    AppIconButton(AppIcon.More, "More actions for ${item.title}", diameter = 48.dp) {
        onAction(WatchAction.OpenItemMenu(item.id))
    }
}

@Composable
private fun Artwork(asset: String, modifier: Modifier = Modifier) {
    Image(
        painterResource(when (asset) {
            "dream" -> R.drawable.sample_dream
            "sintel" -> R.drawable.sample_sintel
            else -> R.drawable.sample_bunny
        }),
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.Crop,
    )
}
