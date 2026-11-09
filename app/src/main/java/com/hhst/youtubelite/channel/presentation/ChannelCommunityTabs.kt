package com.hhst.youtubelite.channel.presentation

import com.hhst.youtubelite.ui.design.labelResource
import androidx.compose.ui.res.stringResource
import com.hhst.youtubelite.R
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import com.hhst.youtubelite.post.domain.PostDetailAction
import com.hhst.youtubelite.post.presentation.PostCardInteractions
import com.hhst.youtubelite.watch.domain.VideoRating
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hhst.youtubelite.channel.domain.*
import com.hhst.youtubelite.post.domain.PostIdentity
import com.hhst.youtubelite.post.domain.PostMenuAction
import com.hhst.youtubelite.post.domain.PostSummary
import com.hhst.youtubelite.post.presentation.PostImageCarousel
import com.hhst.youtubelite.ui.design.AppIcon
import com.hhst.youtubelite.ui.design.AppIconButton
import com.hhst.youtubelite.ui.design.AppText
import com.hhst.youtubelite.ui.design.Artwork
import com.hhst.youtubelite.ui.design.DesignTokens
import com.hhst.youtubelite.ui.design.MenuBottomSheet
import com.hhst.youtubelite.ui.design.MenuSheetItem

@Composable
fun ChannelShortsTab(
    page: ChannelPage,
    section: ChannelSection<List<ChannelShort>>,
    sort: ChannelSortOption,
    width: Float,
    state: LazyListState,
    onAction: (ChannelAction) -> Unit,
    production: Boolean = false,
    canPage: Boolean = false,
    append: com.hhst.youtubelite.home.domain.AppendState = com.hhst.youtubelite.home.domain.AppendState.Idle,
    subscribePending: Boolean = false,
) {
    val viewport = rememberUpdatedState(width)
    val columns = ChannelLayout.shortsColumns(width)
    val rows = remember(section, sort, columns) {
        val shorts = (section as? ChannelSection.Ready)?.value.orEmpty()
        when (sort) {
            ChannelSortOption.Latest -> shorts.sortedBy { it.order }
            ChannelSortOption.Popular -> shorts.sortedByDescending { it.popularity }
            ChannelSortOption.Oldest -> shorts.sortedByDescending { it.order }
        }.chunked(columns)
    }
    ObserveChannelPagination(state, canPage, append) { onAction(ChannelAction.LoadShortsNext) }
    LazyColumn(
        state = state,
        modifier = Modifier.fillMaxSize().testTag("channel-shorts-list"),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item { ChannelHeader(page, viewport.value, onAction, subscribePending) }
        stickyHeader("channel-tabbar") { ChannelTabBar(ChannelTab.Shorts, onAction) }
        item {
            ChannelSortChips(ChannelTab.Shorts, sort) { onAction(ChannelAction.SortShorts(it)) }
        }
        when (section) {
            ChannelSection.Loading -> item { SectionStatus(ChannelTab.Shorts, section, onAction) }
            ChannelSection.Empty -> item { SectionStatus(ChannelTab.Shorts, section, onAction) }
            ChannelSection.Failed -> item { SectionStatus(ChannelTab.Shorts, section, onAction) }
            is ChannelSection.Ready -> {
                val cardWidth = ChannelLayout.shortsCardWidth(width).dp
                val cardHeight = (cardWidth.value * 1.5f).dp
                items(rows.size, key = { "shorts-${rows[it].first().id}" }, contentType = { "shorts-row" }) { rowIndex ->
                    val row = rows[rowIndex]
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = ChannelLayout.ShortsOuterInset.dp, vertical = 0.dp)
                            .padding(bottom = ChannelLayout.ShortsRowGap.dp),
                        horizontalArrangement = Arrangement.spacedBy(ChannelLayout.ShortsGap.dp),
                    ) {
                        row.forEach { short ->
                            ShortCard(short, cardWidth, cardHeight, onAction, production)
                        }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        if (canPage || append != com.hhst.youtubelite.home.domain.AppendState.Idle) item(key = "channel-shorts-append") {
            Box(Modifier.fillMaxWidth().padding(16.dp).testTag("channel-shorts-append"), contentAlignment = Alignment.Center) {
                when (append) {
                    com.hhst.youtubelite.home.domain.AppendState.Loading ->
                        AppText(stringResource(R.string.ui_loading), color = DesignTokens.Secondary)
                    com.hhst.youtubelite.home.domain.AppendState.Failed ->
                        AppText(stringResource(R.string.ui_couldn_t_load_more_shorts_tap_to_retry),
                            Modifier.clip(RoundedCornerShape(8.dp)).clickable { onAction(ChannelAction.LoadShortsNext) }.padding(8.dp),
                            color = DesignTokens.Secondary)
                    else -> Unit
                }
            }
        }
    }
}

@Composable
private fun ShortCard(
    short: ChannelShort,
    cardWidth: androidx.compose.ui.unit.Dp,
    cardHeight: androidx.compose.ui.unit.Dp,
    onAction: (ChannelAction) -> Unit,
    production: Boolean = false,
) {
    Box(
        Modifier.width(cardWidth).height(cardHeight)
            .clip(RoundedCornerShape(ChannelLayout.ShortsCardRadius.dp))
            .clickable(role = Role.Button) { onAction(ChannelAction.OpenShort(short.id)) }
            .testTag("channel-short-${short.id}"),
    ) {
        Artwork(short.artwork, Modifier.matchParentSize())
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.6f)),
            ),
        )
        if (!production) AppIconButton(
            AppIcon.More,
            stringResource(R.string.a11y_more_actions_for , short.title),
            modifier = Modifier.align(Alignment.TopEnd).testTag("channel-short-menu-${short.id}"),
            diameter = 40.dp,
            color = Color.White,
        ) { onAction(ChannelAction.OpenShortMenu(short.id)) }
        Column(Modifier.align(Alignment.BottomStart).padding(8.dp)) {
            AppText(
                short.title,
                size = 14, line = 20, color = Color.White, maxLines = 3,
            )
            Spacer(Modifier.height(2.dp))
            AppText(short.views, size = 12, line = 18, color = Color.White, maxLines = 1)
        }
    }
}

@Composable
fun ChannelLiveTab(
    page: ChannelPage,
    section: ChannelSection<ChannelLivePage>,
    sort: ChannelSortOption,
    width: Float,
    state: LazyListState,
    onAction: (ChannelAction) -> Unit,
    canPage: Boolean = false,
    append: com.hhst.youtubelite.home.domain.AppendState = com.hhst.youtubelite.home.domain.AppendState.Idle,
    subscribePending: Boolean = false,
) {
    val viewport = rememberUpdatedState(width)
    val columns = ChannelLayout.liveColumns(width)
    val rows = remember(section, sort, columns) {
        val live = (section as? ChannelSection.Ready)?.value
        val past = when (sort) {
            ChannelSortOption.Latest -> live?.past.orEmpty().sortedBy { it.order }
            ChannelSortOption.Popular -> live?.past.orEmpty().sortedByDescending { it.popularity }
            ChannelSortOption.Oldest -> live?.past.orEmpty().sortedByDescending { it.order }
        }
        (live?.current.orEmpty() + past).chunked(columns)
    }
    ObserveChannelPagination(state, canPage, append) { onAction(ChannelAction.LoadLiveNext) }
    LazyColumn(
        state = state,
        modifier = Modifier.fillMaxSize().testTag("channel-live-list"),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item { ChannelHeader(page, viewport.value, onAction, subscribePending) }
        stickyHeader("channel-tabbar") { ChannelTabBar(ChannelTab.Live, onAction) }
        item { ChannelSortChips(ChannelTab.Live, sort) { onAction(ChannelAction.SortLive(it)) } }
        when (section) {
            ChannelSection.Loading -> item { SectionStatus(ChannelTab.Live, section, onAction) }
            ChannelSection.Empty -> item { SectionStatus(ChannelTab.Live, section, onAction) }
            ChannelSection.Failed -> item { SectionStatus(ChannelTab.Live, section, onAction) }
            is ChannelSection.Ready -> {
                val tile = ChannelLayout.liveTile(width)
                val outer = if (columns == 1) 0.dp else ChannelLayout.LiveInset.dp
                val duo = if (columns == 1) ChannelLayout.LiveInset.dp else 0.dp
                items(rows.size, key = { "live-${rows[it].first().id}" }, contentType = { "live-row" }) { rowIndex ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = outer),
                        horizontalArrangement = Arrangement.spacedBy(ChannelLayout.LiveGap.dp),
                    ) {
                        rows[rowIndex].forEach { video ->
                            Box(Modifier.weight(1f).padding(horizontal = duo)) {
                                LiveCard(video, tile, onAction)
                            }
                        }
                        repeat(columns - rows[rowIndex].size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        if (canPage || append != com.hhst.youtubelite.home.domain.AppendState.Idle) item(key = "channel-live-append") {
            Box(Modifier.fillMaxWidth().padding(16.dp).testTag("channel-live-append"), contentAlignment = Alignment.Center) {
                when (append) {
                    com.hhst.youtubelite.home.domain.AppendState.Loading ->
                        AppText(stringResource(R.string.ui_loading), color = DesignTokens.Secondary)
                    com.hhst.youtubelite.home.domain.AppendState.Failed ->
                        AppText(stringResource(R.string.ui_couldn_t_load_more_streams_tap_to_retry),
                            Modifier.clip(RoundedCornerShape(8.dp)).clickable { onAction(ChannelAction.LoadLiveNext) }.padding(8.dp),
                            color = DesignTokens.Secondary)
                    else -> Unit
                }
            }
        }
    }
}

@Composable
private fun LiveCard(video: ChannelLiveVideo, tile: Boolean, onAction: (ChannelAction) -> Unit) {
    if (tile) {
        Column(
            Modifier.fillMaxWidth().clickable(role = Role.Button) { onAction(ChannelAction.OpenLive(video.id)) }
                .testTag("channel-live-${video.id}"),
        ) {
            LiveThumbnail(video, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            AppText(video.title, Modifier.padding(top = 8.dp), size = 14, line = 20, maxLines = 2)
            AppText(
                if (video.liveNow) video.views else listOf(video.views, video.time).filter { it.isNotBlank() }.joinToString(" · "),
                Modifier.padding(top = 2.dp, bottom = 8.dp),
                size = 12, line = 18, color = DesignTokens.Secondary, maxLines = 2,
            )
        }
    } else {
        Row(
            Modifier.fillMaxWidth().heightIn(min = ChannelLayout.LiveRowHeight.dp)
                .clickable(role = Role.Button) { onAction(ChannelAction.OpenLive(video.id)) }
                .testTag("channel-live-${video.id}"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LiveThumbnail(
                video,
                Modifier.width(ChannelLayout.LiveThumbnailWidth.dp)
                    .height(ChannelLayout.LiveThumbnailHeight.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                AppText(video.title, size = 14, line = 20, maxLines = 2)
                AppText(
                    if (video.liveNow) video.views else listOf(video.views, video.time).filter { it.isNotBlank() }.joinToString(" · "),
                    Modifier.padding(top = 2.dp),
                    size = 12, line = 18, color = DesignTokens.Secondary, maxLines = 2,
                )
            }
        }
    }
}

@Composable
private fun LiveThumbnail(video: ChannelLiveVideo, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(8.dp))) {
        Artwork(video.artwork, Modifier.matchParentSize())
        if (video.liveNow) {
            Box(
                Modifier.align(Alignment.BottomStart).padding(8.dp)
                    .background(DesignTokens.Red, RoundedCornerShape(3.dp))
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            ) {
                AppText(stringResource(R.string.player_live), size = 12, line = 16, weight = FontWeight.Medium, color = Color.White)
            }
        } else {
            Box(
                Modifier.align(Alignment.BottomEnd).padding(4.dp)
                    .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(2.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            ) {
                AppText(video.duration, size = 12, line = 16, color = Color.White)
            }
        }
    }
}

@Composable
fun ChannelPostsTab(
    page: ChannelPage,
    section: ChannelSection<List<PostSummary>>,
    width: Float,
    state: LazyListState,
    expandedPosts: Set<String>,
    onAction: (ChannelAction) -> Unit,
    production: Boolean = false,
    canPage: Boolean = false,
    append: com.hhst.youtubelite.home.domain.AppendState = com.hhst.youtubelite.home.domain.AppendState.Idle,
    subscribePending: Boolean = false,
    postInteractions: PostCardInteractions? = null,
) {
    val viewport = rememberUpdatedState(width)
    ObserveChannelPagination(state, canPage, append) { onAction(ChannelAction.LoadPostsNext) }
    LazyColumn(
        state = state,
        modifier = Modifier.fillMaxSize().testTag("channel-posts-list"),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item { ChannelHeader(page, viewport.value, onAction, subscribePending) }
        stickyHeader("channel-tabbar") { ChannelTabBar(ChannelTab.Posts, onAction) }
        when (section) {
            ChannelSection.Loading -> item { SectionStatus(ChannelTab.Posts, section, onAction) }
            ChannelSection.Empty -> item { SectionStatus(ChannelTab.Posts, section, onAction) }
            ChannelSection.Failed -> item { SectionStatus(ChannelTab.Posts, section, onAction) }
            is ChannelSection.Ready -> items(section.value.size, key = { section.value[it].identity.postId }, contentType = { "post" }) { index ->
                val post = section.value[index]
                PostCard(
                    post = post,
                    expanded = post.identity.postId in expandedPosts,
                    onAction = onAction,
                    production = production,
                    interactions = postInteractions,
                )
            }
        }
        if (canPage || append != com.hhst.youtubelite.home.domain.AppendState.Idle) item(key = "channel-posts-append") {
            Box(Modifier.fillMaxWidth().padding(16.dp).testTag("channel-posts-append"), contentAlignment = Alignment.Center) {
                when (append) {
                    com.hhst.youtubelite.home.domain.AppendState.Loading ->
                        AppText(stringResource(R.string.ui_loading), color = DesignTokens.Secondary)
                    com.hhst.youtubelite.home.domain.AppendState.Failed ->
                        AppText(stringResource(R.string.ui_couldn_t_load_more_posts_tap_to_retry),
                            Modifier.clip(RoundedCornerShape(8.dp)).clickable { onAction(ChannelAction.LoadPostsNext) }.padding(8.dp),
                            color = DesignTokens.Secondary)
                    else -> Unit
                }
            }
        }
    }
}

@Composable
private fun PostCard(
    post: PostSummary,
    expanded: Boolean,
    onAction: (ChannelAction) -> Unit,
    production: Boolean = false,
    interactions: PostCardInteractions? = null,
) {
    val interaction = interactions?.posts?.get(post.identity.postId)
    val rating = interaction?.post?.rating ?: VideoRating.None
    val pending = interaction?.pendingAction
    fun rate(liked: Boolean) {
        if (interactions != null) interactions.perform(post.identity, if (liked) PostDetailAction.Like else PostDetailAction.Dislike)
        else onAction(ChannelAction.PostFeedback(post.identity, liked))
    }
    val open = { onAction(ChannelAction.OpenPost(post.identity)) }
    Column(Modifier.fillMaxWidth().testTag("channel-post-${post.identity.postId}")) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, top = 12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(Modifier.size(width = 48.dp, height = 40.dp), contentAlignment = Alignment.TopStart) {
                Box(Modifier.size(40.dp).clip(CircleShape).testTag("channel-post-avatar-${post.identity.postId}")) {
                    Artwork(post.author.avatar, Modifier.fillMaxSize())
                }
            }
            Column(
                Modifier.weight(1f).heightIn(min = 43.dp).clickable(role = Role.Button) { open() },
            ) {
                AppText(post.author.name, Modifier.testTag("channel-post-author-${post.identity.postId}"), size = 14, line = 20, weight = FontWeight.Medium)
                AppText(post.age, size = 14, line = 20, color = DesignTokens.Secondary)
            }
            Box(Modifier.size(width = 44.dp, height = 43.dp)) {
                if (!production) AppIconButton(
                    AppIcon.More, stringResource(R.string.a11y_post_actions),
                    modifier = Modifier.wrapContentSize(Alignment.TopStart, unbounded = true)
                        .testTag("channel-post-menu-${post.identity.postId}"),
                ) { onAction(ChannelAction.OpenPostMenu(post.identity)) }
            }
        }
        var overflow by remember(post.identity) { mutableStateOf(false) }
        Box(
            Modifier.fillMaxWidth().animateContentSize(tween(180)).padding(horizontal = 12.dp).padding(top = 12.dp)
                .testTag("channel-post-body-${post.identity.postId}"),
        ) {
            BasicText(
                post.text,
                Modifier.fillMaxWidth().clickable(role = Role.Button) { open() },
                style = DesignTokens.text(16, 22),
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { overflow = it.hasVisualOverflow },
            )
            if (!expanded && overflow) {
                Row(
                    Modifier.align(Alignment.BottomEnd).background(DesignTokens.Background)
                        .clickable(role = Role.Button) {
                            onAction(ChannelAction.TogglePostExpanded(post.identity))
                        }.testTag("channel-post-read-more-${post.identity.postId}"),
                ) {
                    AppText("...", size = 16, line = 22, color = DesignTokens.Secondary)
                    AppText(
                        stringResource(R.string.ui_read_more),
                        size = 16, line = 22, weight = FontWeight.Medium,
                        color = DesignTokens.Secondary,
                    )
                }
            }
        }
        if (expanded) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 2.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                AppText(
                    stringResource(R.string.ui_show_less),
                    Modifier.clickable(role = Role.Button) {
                        onAction(ChannelAction.TogglePostExpanded(post.identity))
                    }.testTag("channel-post-show-less-${post.identity.postId}"),
                    size = 14, line = 20, weight = FontWeight.Medium, color = DesignTokens.Secondary,
                )
            }
        }
        (interaction?.post?.poll ?: post.poll)?.let { poll ->
            com.hhst.youtubelite.post.presentation.PostPollChoices(poll, pending != null, Modifier.padding(start = 48.dp, top = 12.dp),
                pendingOption = (pending as? PostDetailAction.Vote)?.option) {
                if (interactions != null) interactions.perform(post.identity, PostDetailAction.Vote(it, !poll.options[it].selected))
                else onAction(ChannelAction.OpenPost(post.identity))
            }
        }
        if (post.images.isNotEmpty()) {
            var imageIndex by rememberSaveable(post.identity.postId) { mutableIntStateOf(0) }
            Spacer(Modifier.height(12.dp))
            PostImageCarousel(
                images = post.images,
                selectedIndex = imageIndex,
                onSelected = { imageIndex = it },
                onOpen = { index ->
                    if (interactions?.openImages != null) interactions.openImages.invoke(post.images, index)
                    else open()
                },
                modifier = Modifier.testTag("channel-post-images-${post.identity.postId}"),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIconButton(
                AppIcon.Like, stringResource(R.string.a11y_like_post), modifier = Modifier.testTag("channel-post-like-${post.identity.postId}").semantics { selected = rating == VideoRating.Like },
                diameter = 40.dp,
                enabled = pending == null, pending = pending == PostDetailAction.Like,
                background = if (rating == VideoRating.Like) DesignTokens.Red.copy(alpha = 0.12f) else Color.Transparent,
                color = if (rating == VideoRating.Like) DesignTokens.Red else DesignTokens.Ink,
            ) { rate(true) }
            AppText(interaction?.post?.likes ?: post.likes, Modifier.widthIn(min = 40.dp), size = 14, line = 20)
            AppIconButton(
                AppIcon.Dislike, stringResource(R.string.a11y_dislike_post),
                modifier = Modifier.testTag("channel-post-dislike-${post.identity.postId}").semantics { selected = rating == VideoRating.Dislike },
                diameter = 40.dp,
                enabled = pending == null, pending = pending == PostDetailAction.Dislike,
                background = if (rating == VideoRating.Dislike) DesignTokens.Red.copy(alpha = 0.12f) else Color.Transparent,
                color = if (rating == VideoRating.Dislike) DesignTokens.Red else DesignTokens.Ink,
            ) { rate(false) }
            Spacer(Modifier.weight(1f))
            Row(
                Modifier.clickable(role = Role.Button) {
                    onAction(ChannelAction.PostComments(post.identity))
                }.padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag("channel-post-comments-${post.identity.postId}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppIcon(AppIcon.Comment)
                AppText(post.comments, Modifier.padding(start = 6.dp), size = 14, line = 20, weight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun ChannelSortChips(
    tab: ChannelTab,
    selected: ChannelSortOption,
    onSelect: (ChannelSortOption) -> Unit,
) {
    Column {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp)
                .horizontalScroll(rememberScrollState()).testTag("channel-${tab.name.lowercase()}-sort"),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ChannelSortOption.entries.forEach { option ->
                Box(
                    Modifier.defaultMinSize(minHeight = ChannelLayout.FilterChipHeight.dp)
                        .clip(RoundedCornerShape(ChannelLayout.FilterChipRadius.dp))
                        .background(if (option == selected) DesignTokens.Ink else DesignTokens.Surface)
                        .clickable(role = Role.RadioButton) { onSelect(option) }
                        .padding(horizontal = 12.dp)
                        .testTag("channel-${tab.name.lowercase()}-sort-${option.name.lowercase()}"),
                    contentAlignment = Alignment.Center,
                ) {
                    AppText(
                        stringResource(option.labelResource), size = 14, line = 20, weight = FontWeight.Medium,
                        color = if (option == selected) DesignTokens.Background else DesignTokens.Ink,
                    )
                }
            }
        }
        // No server sort lane exists for shorts/live: say so instead of implying a global order.
        if (selected != ChannelSortOption.Latest) {
            AppText(
                stringResource(R.string.ui_sorted_within_loaded_items_only),
                Modifier.padding(start = 16.dp, top = 6.dp)
                    .testTag("channel-${tab.name.lowercase()}-sort-scope"),
                size = 12, line = 16, color = DesignTokens.Secondary,
            )
        }
    }
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun SectionStatus(
    tab: ChannelTab,
    section: ChannelSection<*>,
    onAction: (ChannelAction) -> Unit,
) {
    val key = tab.name.lowercase()
    Box(
        Modifier.fillMaxWidth().height(240.dp).testTag(
            when (section) {
                ChannelSection.Loading -> "channel-$key-loading"
                ChannelSection.Empty -> "channel-$key-empty"
                else -> "channel-$key-failed"
            },
        ),
        contentAlignment = Alignment.Center,
    ) {
        when (section) {
            ChannelSection.Loading -> CircularProgressIndicator(
                Modifier.size(32.dp), strokeWidth = 2.5.dp, color = DesignTokens.Secondary,
            )
            ChannelSection.Empty -> AppText(
                when (tab) {
                    ChannelTab.Shorts -> stringResource(R.string.ui_no_shorts_yet)
                    ChannelTab.Live -> stringResource(R.string.ui_no_live_streams_yet)
                    else -> stringResource(R.string.ui_no_posts_yet)
                },
                size = 16, line = 22, color = DesignTokens.Secondary,
            )
            ChannelSection.Failed -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                AppText(
                    when (tab) {
                        ChannelTab.Shorts -> stringResource(R.string.ui_shorts_could_not_be_loaded)
                        ChannelTab.Live -> stringResource(R.string.ui_live_streams_could_not_be_loaded)
                        else -> stringResource(R.string.ui_posts_could_not_be_loaded)
                    },
                    size = 16, line = 22, color = DesignTokens.Secondary,
                )
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier.heightIn(min = 36.dp).clip(RoundedCornerShape(18.dp)).background(DesignTokens.Ink)
                        .clickable(role = Role.Button) { onAction(ChannelAction.RetrySection(tab)) }
                        .padding(horizontal = 20.dp).testTag("channel-$key-retry"),
                    contentAlignment = Alignment.Center,
                ) {
                    AppText(stringResource(R.string.retry), size = 14, line = 20, weight = FontWeight.Medium, color = DesignTokens.Background)
                }
            }
            is ChannelSection.Ready -> Unit
        }
    }
}

@Composable
fun ChannelCommunityMenuSheets(
    overlay: ChannelOverlay,
    onAction: (ChannelAction) -> Unit,
) {
    when (overlay) {
        is ChannelOverlay.ShortMenu -> MenuBottomSheet(
            items = ChannelShortMenuAction.entries.map {
                MenuSheetItem(
                    icon = if (it == ChannelShortMenuAction.NotInterested) AppIcon.Block else AppIcon.Feedback,
                    label = stringResource(it.labelResource),
                    tag = "channel-short-menu-${it.name.lowercase()}",
                )
            },
            onDismiss = { onAction(ChannelAction.CloseOverlay) },
            onSelect = { index -> onAction(ChannelAction.SelectShortMenu(ChannelShortMenuAction.entries[index])) },
            tag = "channel-short-menu",
            paneTitle = stringResource(R.string.ui_short_actions),
        )
        is ChannelOverlay.PostMenu -> MenuBottomSheet(
            items = PostMenuAction.entries.map {
                MenuSheetItem(icon = AppIcon.Report, label = stringResource(it.labelResource), tag = "channel-post-menu-${it.name.lowercase()}")
            },
            onDismiss = { onAction(ChannelAction.CloseOverlay) },
            onSelect = { index -> onAction(ChannelAction.SelectPostMenu(PostMenuAction.entries[index])) },
            tag = "channel-post-menu-sheet",
            paneTitle = stringResource(R.string.a11y_post_actions),
        )
        else -> Unit
    }
}
