package com.hhst.youtubelite.shorts.presentation

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hhst.youtubelite.R
import com.hhst.youtubelite.library.presentation.LibraryError
import com.hhst.youtubelite.shorts.domain.ShortItem
import com.hhst.youtubelite.shorts.domain.ShortsAction
import com.hhst.youtubelite.shorts.domain.ShortsContent
import com.hhst.youtubelite.shorts.domain.ShortsUiState
import com.hhst.youtubelite.ui.design.AppIconButton
import com.hhst.youtubelite.ui.design.AppIcon
import com.hhst.youtubelite.ui.design.AppText
import com.hhst.youtubelite.ui.design.DesignTokens
import androidx.compose.ui.res.stringResource
import com.hhst.youtubelite.ui.design.appDescription
import com.hhst.youtubelite.dearrow.rememberDeArrowTitle
import com.hhst.youtubelite.dearrow.VideoArtwork

@Composable
fun ShortsScreen(state: ShortsUiState, onAction: (ShortsAction) -> Unit, modifier: Modifier = Modifier,
    videoContent: (@Composable (String) -> Unit)? = null,
    isVideoReady: (String) -> Boolean = { false },
    playbackControls: (@Composable (String) -> Unit)? = null,
    onSwipeStart: (() -> Unit)? = null, onSwipeCancel: ((String) -> Unit)? = null) {
    Box(modifier.fillMaxSize().background(Color.Black).testTag("shorts-screen")) {
        when (val content = state.content) {
            ShortsContent.Loading -> ShortsSkeleton()
            ShortsContent.Failed -> Box(Modifier.fillMaxSize().background(DesignTokens.Background)) { LibraryError("shorts-error") { onAction(ShortsAction.Retry) } }
            ShortsContent.Empty -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                AppText("No Shorts available", color = Color.White, size = 16, line = 22)
            }
            is ShortsContent.Ready -> ShortsPager(content.page.items, state.currentItemId, onAction, videoContent, isVideoReady, playbackControls, state, onSwipeStart, onSwipeCancel)
        }
    }
}

@Composable
private fun ShortsSkeleton() {
    val ghost = Color(0xFF272727)
    Box(Modifier.fillMaxSize().background(Color.Black).testTag("shorts-skeleton").clearAndSetSemantics {}
        .windowInsetsPadding(WindowInsets.safeDrawing)) {
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 16.dp, end = 80.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(36.dp).background(ghost, CircleShape))
                Box(Modifier.width(112.dp).height(16.dp).background(ghost, RoundedCornerShape(4.dp)))
            }
            Box(Modifier.fillMaxWidth().height(16.dp).background(ghost, RoundedCornerShape(4.dp)))
            Box(Modifier.fillMaxWidth(0.65f).height(16.dp).background(ghost, RoundedCornerShape(4.dp)))
        }
        Column(Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)) {
            repeat(4) { Box(Modifier.size(40.dp).background(ghost, CircleShape)) }
        }
    }
}

@Composable
private fun ShortsPager(items: List<ShortItem>, startItemId: String?, onAction: (ShortsAction) -> Unit, videoContent: (@Composable (String) -> Unit)?,
    isVideoReady: (String) -> Boolean,
    playbackControls: (@Composable (String) -> Unit)?,
    state: ShortsUiState, onSwipeStart: (() -> Unit)?, onSwipeCancel: ((String) -> Unit)?) {
    val pagerState = rememberPagerState(initialPage = items.indexOfFirst { it.id == startItemId }.coerceAtLeast(0)) { items.size }
    val currentAction by rememberUpdatedState(onAction)
    val currentItems by rememberUpdatedState(items)
    val currentSwipeStart by rememberUpdatedState(onSwipeStart)
    val currentSwipeCancel by rememberUpdatedState(onSwipeCancel)
    val currentSelectedId by rememberUpdatedState(state.currentItemId)
    // Keyed on the pager only: hydration and appends mutate `items`, and a
    // restart here would replay SelectItem for the settled page.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            currentItems.getOrNull(page)?.let { currentAction(ShortsAction.SelectItem(it.id)) }
            // Prefetch the continuation before the pager runs out of pages.
            if (page >= currentItems.size - 3) currentAction(ShortsAction.LoadMore)
        }
    }
    // A swipe silences the settled short while it is in progress; one that
    // settles back onto the short it started on resumes it.
    LaunchedEffect(pagerState) {
        var holding = false
        snapshotFlow { pagerState.isScrollInProgress }.collect { scrolling ->
            when {
                scrolling && !holding -> { holding = true; currentSwipeStart?.invoke() }
                !scrolling && holding -> {
                    holding = false
                    val item = currentItems.getOrNull(pagerState.settledPage)
                    if (item != null && item.id == currentSelectedId) currentSwipeCancel?.invoke(item.id)
                }
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
    VerticalPager(state = pagerState, key = { items[it].id }, modifier = Modifier.fillMaxSize().testTag("shorts-pager")) { page ->
        ShortPage(items[page], onAction, if (page == pagerState.settledPage) videoContent else null,
            videoReady = page == pagerState.settledPage && isVideoReady(items[page].id),
            playbackControls = if (page == pagerState.settledPage) playbackControls else null,
            rating = state.ratings[items[page].id] ?: com.hhst.youtubelite.watch.domain.VideoRating.None,
            subscribed = state.subscriptions[items[page].channelId] == true,
            menuVisible = !state.live)
    }
    if (state.appendFailed) AppText(androidx.compose.ui.res.stringResource(R.string.retry),
        Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(bottom = 16.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xFF272727))
            .clickable(role = Role.Button) { onAction(ShortsAction.RetryMore) }
            .heightIn(min = 48.dp).padding(horizontal = 20.dp, vertical = 12.dp)
            .testTag("shorts-append-retry"), color = Color.White)
    }
}

@Composable
private fun ShortPage(item: ShortItem, onAction: (ShortsAction) -> Unit, videoContent: (@Composable (String) -> Unit)?,
    videoReady: Boolean = false,
    playbackControls: (@Composable (String) -> Unit)? = null,
    rating: com.hhst.youtubelite.watch.domain.VideoRating = com.hhst.youtubelite.watch.domain.VideoRating.None,
    subscribed: Boolean = false, menuVisible: Boolean = true) {
    Box(Modifier.fillMaxSize().testTag("short-page-${item.id}")) {
        videoContent?.invoke(item.id)
        if (!videoReady) VideoArtwork(item.id, item.artwork, Modifier.fillMaxSize().testTag("short-cover-${item.id}").semantics { contentDescription = "Play ${item.title}" })
        Box(Modifier.matchParentSize().background(
            Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.6f))))
        playbackControls?.invoke(item.id)
        BoxWithConstraints(Modifier.matchParentSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        if (menuVisible) AppIconButton(AppIcon.More, stringResource(R.string.a11y_more_actions_for, item.title),
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 8.dp), diameter = 40.dp, color = Color.White) {
            onAction(ShortsAction.OpenMenu(item.id))
        }
        val railWidth = if (LocalDensity.current.fontScale > 1.5f) 80.dp else 56.dp
        Column(Modifier.align(Alignment.BottomEnd).width(railWidth)
            .padding(end = 8.dp, bottom = minOf(76.dp, maxHeight / 5))
            .heightIn(max = (maxHeight - 48.dp).coerceAtLeast(0.dp)).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            RailAction(AppIcon.Like, "Like ${item.title}", item.likes,
                tinted = rating == com.hhst.youtubelite.watch.domain.VideoRating.Like,
                onClick = { onAction(ShortsAction.Like(item.id)) })
            Spacer(Modifier.height(12.dp))
            RailAction(AppIcon.Comment, "Comments for ${item.title}", item.comments, onClick = {
                onAction(ShortsAction.OpenComments(item.id))
            })
            Spacer(Modifier.height(12.dp))
            RailAction(AppIcon.Share, "Share ${item.title}", "Share", onClick = { onAction(ShortsAction.Share(item.id)) })
        }
        Column(Modifier.align(Alignment.BottomStart).padding(start = 16.dp, end = railWidth + 8.dp, bottom = 16.dp)
            .heightIn(max = (maxHeight - 48.dp).coerceAtLeast(0.dp)).verticalScroll(rememberScrollState())) {
            Row(Modifier.heightIn(min = 40.dp).testTag("short-channel-${item.id}"),
                verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f, fill = false).clickable(role = Role.Button) {
                    onAction(ShortsAction.OpenChannel(item.channelId))
                }, verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(32.dp).clip(CircleShape).background(Color(0xFF272727))
                        .appDescription(R.string.a11y_go_to_channel, item.author), contentAlignment = Alignment.Center) {
                        if (item.avatar.isNotBlank()) Artwork(item.avatar, Modifier.fillMaxSize())
                        else AppText(item.author.take(1).uppercase(), size = 16, line = 20, color = Color.White)
                    }
                    AppText(item.author, Modifier.padding(start = 8.dp), size = 14, line = 20, color = Color.White, maxLines = 1)
                }
                AppText(if (subscribed) "Subscribed" else "Subscribe", Modifier.padding(start = 8.dp).clip(RoundedCornerShape(20.dp))
                    .background(if (subscribed) Color.White.copy(alpha = 0.25f) else Color(0xFF0F0F0F)).clickable(role = Role.Button) { onAction(ShortsAction.Subscribe(item.channelId)) }
                    .padding(horizontal = 16.dp).heightIn(min = 40.dp),
                    size = 14, line = 40, weight = FontWeight.Medium, color = Color.White, maxLines = 1)
            }
            AppText(rememberDeArrowTitle(item.id, item.title), Modifier.padding(top = 8.dp).testTag("short-title-${item.id}"),
                size = 14, line = 20, color = Color.White, maxLines = 3)
        }
        }
    }
}

@Composable
private fun RailAction(icon: AppIcon, description: String, label: String, onClick: () -> Unit, tinted: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        AppIconButton(icon, description, diameter = 40.dp, color = if (tinted) DesignTokens.Red else Color.White, onClick = onClick)
        if (label.isNotEmpty()) {
            AppText(label, Modifier.testTag("rail-label-$description"), size = 12, line = 16,
                weight = FontWeight.Medium, color = Color.White, maxLines = 1)
        } else {
            // Keep the 60dp slot even when an action has no count label.
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun Image(asset: String, modifier: Modifier, description: String) {
    com.hhst.youtubelite.ui.design.Artwork(asset, modifier.semantics { contentDescription = description })
}

@Composable
private fun Artwork(asset: String, modifier: Modifier) {
    com.hhst.youtubelite.ui.design.Artwork(asset, modifier)
}
