package com.hhst.youtubelite.comments.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import com.hhst.youtubelite.R
import com.hhst.youtubelite.comments.domain.CommentAction
import com.hhst.youtubelite.comments.domain.CommentAuthor
import com.hhst.youtubelite.comments.domain.CommentReply
import com.hhst.youtubelite.comments.domain.CommentSort
import com.hhst.youtubelite.comments.domain.CommentThread
import com.hhst.youtubelite.comments.domain.CommentsContent
import com.hhst.youtubelite.comments.domain.CommentsLayout
import com.hhst.youtubelite.comments.domain.CommentsUiState
import com.hhst.youtubelite.ui.design.AppIcon
import com.hhst.youtubelite.ui.design.AppIconButton
import com.hhst.youtubelite.ui.design.AppText
import com.hhst.youtubelite.ui.design.DesignTokens
import com.hhst.youtubelite.ui.design.messageCardShadow
import com.hhst.youtubelite.watch.domain.WatchLayout
import androidx.compose.runtime.snapshotFlow

/** Comments sheet and docked panel. */
@Composable
fun CommentsPanel(
    state: CommentsUiState,
    commentCount: String,
    onAction: (CommentAction) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val width = maxWidth.value
        val height = maxHeight.value
        if (WatchLayout.sidebar(width, height)) {
            Row(Modifier.fillMaxSize().testTag("comments-rail")) {
                // The rail is docked: the page to its left remains interactive, as sampled.
                Spacer(Modifier.weight(1f).fillMaxHeight())
                CommentsSurface(
                    state = state,
                    commentCount = commentCount,
                    onAction = onAction,
                    onClose = onClose,
                    panelWidth = WatchLayout.sidebarWidth(width),
                    compact = false,
                    listState = listState,
                    modifier = Modifier.width(WatchLayout.sidebarWidth(width).dp).fillMaxHeight()
                        .padding(top = WatchLayout.TopBarHeight.dp),
                )
            }
        } else {
            // The page rounds the fractional 16:9 player; the sheet must start on the same pixel.
            val top = CommentsLayout.sheetTop(
                topBar = WatchLayout.TopBarHeight,
                playerHeight = WatchLayout.playerHeight(width, height),
                windowHeight = height,
            ).roundToInt().toFloat()
            Box(Modifier.fillMaxSize()) {
                // The sheet has no tinted scrim in the sample; the exposed page area still dismisses.
                Box(
                    Modifier.fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onClose() }
                        .semantics { contentDescription = "Close comments" }
                        .testTag("comments-scrim"),
                )
                CommentsSurface(
                    state = state,
                    commentCount = commentCount,
                    onAction = onAction,
                    onClose = onClose,
                    panelWidth = width,
                    compact = true,
                    listState = listState,
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .height((height - top).coerceAtLeast(0f).dp),
                )
            }
        }
    }
}

@Composable
private fun CommentsSurface(
    state: CommentsUiState,
    commentCount: String,
    onAction: (CommentAction) -> Unit,
    onClose: () -> Unit,
    panelWidth: Float,
    compact: Boolean,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    var sortMenuOpen by rememberSaveable(state.videoId) { mutableStateOf(false) }
    BackHandler(enabled = sortMenuOpen) { sortMenuOpen = false }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    DisposableEffect(Unit) { onDispose { focusManager.clearFocus() } }

    val shape = if (compact) RoundedCornerShape(topStart = CommentsLayout.SheetRadius.dp, topEnd = CommentsLayout.SheetRadius.dp)
    else RectangleShape
    BoxWithConstraints(modifier) {
        Column(
            Modifier.fillMaxSize().background(Color.White, shape)
                .focusRequester(focus).focusable()
                .semantics { paneTitle = "Comments" }
                .testTag("comments-panel"),
        ) {
            if (compact) HandleStrip() else Spacer(Modifier.height(CommentsLayout.HeaderTopGap.dp))
            CommentsHeader(
                sort = state.sort,
                count = commentCount,
                panelWidth = panelWidth,
                docked = !compact,
                showSort = state.content !is CommentsContent.Disabled,
                onSort = { sortMenuOpen = true },
                onClose = onClose,
            )
            CommentsBody(state = state, listState = listState, onAction = onAction)
        }
        if (sortMenuOpen) {
            val menuTop = minOf(81.dp, (maxHeight - 112.dp).coerceAtLeast(0.dp))
            Box(
                Modifier.fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { sortMenuOpen = false }
                    .testTag("comments-sort-dismiss"),
            )
            SortMenu(
                selected = state.sort,
                onSelect = {
                    sortMenuOpen = false
                    onAction(CommentAction.SelectSort(it))
                },
                modifier = Modifier.align(Alignment.TopEnd)
                    .padding(top = menuTop, end = 8.dp)
                    .heightIn(max = maxHeight - menuTop),
            )
        }
    }
}

@Composable
private fun HandleStrip() {
    Box(
        Modifier.fillMaxWidth().height(CommentsLayout.HandleStripHeight.dp).testTag("comments-handle"),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(
            Modifier.width(CommentsLayout.HandleWidth.dp).height(CommentsLayout.HandleHeight.dp)
                .background(Color(0xFFDBDBDB), RoundedCornerShape(2.dp))
                .testTag("comments-handle-bar"),
        )
    }
}

@Composable
private fun CommentsHeader(
    sort: CommentSort, count: String, panelWidth: Float, docked: Boolean, showSort: Boolean,
    onSort: () -> Unit, onClose: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val measurer = rememberTextMeasurer()
        val titleWidth = measurer.measure("Comments", DesignTokens.text(
            CommentsLayout.titleSize(panelWidth, docked).toInt(), CommentsLayout.titleLine(panelWidth, docked).toInt(), FontWeight.Bold,
        )).size.width
        val label = if (sort == CommentSort.Top) "Top" else "Newest"
        val sortWidth = measurer.measure(label, DesignTokens.text(14, 20, FontWeight.Medium)).size.width
        val stacked = showSort && titleWidth + sortWidth + 2 + with(density) { 100.dp.roundToPx() } > constraints.maxWidth
        Box(Modifier.fillMaxWidth().height((CommentsLayout.HeaderHeight + if (stacked) 48f else 0f).dp).testTag("comments-header")) {
            Row(
                Modifier.fillMaxWidth().height(CommentsLayout.HeaderHeight.dp)
                    .padding(start = CommentsLayout.HeaderPaddingStart.dp, end = CommentsLayout.CloseEndInset.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TitleBlock(count, panelWidth, docked, Modifier.weight(1f))
                if (showSort && !stacked) {
                    SortControl(label, onSort)
                    Spacer(Modifier.width(8.dp))
                }
                AppIconButton(AppIcon.Close, "Close comments", diameter = CommentsLayout.CloseTarget.dp,
                    modifier = Modifier.testTag("comments-close"), onClick = onClose)
            }
            if (stacked) Box(Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 8.dp)) {
                SortControl(label, onSort)
            }
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(1.dp)
                .background(Color.Black.copy(alpha = CommentsLayout.DividerAlpha)))
        }
    }
}

@Composable
private fun SortControl(label: String, onClick: () -> Unit) {
    Box(
        Modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp).testTag("comments-sort").semantics { contentDescription = "Sort comments" },
        contentAlignment = Alignment.Center,
    ) { AppText(label, size = 14, line = 20, weight = FontWeight.Medium) }
}

@Composable
private fun TitleBlock(count: String, panelWidth: Float, docked: Boolean, modifier: Modifier = Modifier) {
    val titleSize = CommentsLayout.titleSize(panelWidth, docked).toInt()
    val titleLine = CommentsLayout.titleLine(panelWidth, docked).toInt()
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val titleStyle = DesignTokens.text(titleSize, titleLine, FontWeight.Bold)
    val countStyle = DesignTokens.text(CommentsLayout.CountSize.toInt(), CommentsLayout.CountLine.toInt())
    BoxWithConstraints(modifier, contentAlignment = Alignment.CenterStart) {
        val titleWidth = measurer.measure("Comments", titleStyle).size.width + 1
        val countWidth = measurer.measure(count, countStyle).size.width + 1
        val gapWidth = with(density) { CommentsLayout.TitleCountGap.dp.roundToPx() }
        // The count is dropped before the scaled-up title is allowed to ellipsize.
        val showCount = count.isNotEmpty() && titleWidth + gapWidth + countWidth <= constraints.maxWidth
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppText(
                "Comments",
                Modifier.widthIn(min = with(density) { titleWidth.toDp() }).testTag("comments-title"),
                size = titleSize,
                line = titleLine,
                weight = FontWeight.Bold,
                maxLines = 1,
            )
            if (showCount) {
                Spacer(Modifier.width(CommentsLayout.TitleCountGap.dp))
                AppText(
                    count,
                    Modifier.widthIn(min = with(density) { countWidth.toDp() }).testTag("comments-count"),
                    size = CommentsLayout.CountSize.toInt(),
                    line = CommentsLayout.CountLine.toInt(),
                    color = DesignTokens.Secondary,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun SortMenu(selected: CommentSort, onSelect: (CommentSort) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.width(200.dp).messageCardShadow().background(Color.White, RoundedCornerShape(12.dp))
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp).testTag("comments-sort-menu")
            .semantics { paneTitle = "Sort comments" },
    ) {
        SortMenuRow("Top comments", selected == CommentSort.Top, "comments-sort-top") { onSelect(CommentSort.Top) }
        SortMenuRow("Newest first", selected == CommentSort.Newest, "comments-sort-newest") { onSelect(CommentSort.Newest) }
    }
}

@Composable
private fun SortMenuRow(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppText(
            label, size = 14, line = 20,
            weight = if (selected) FontWeight.Medium else FontWeight.Normal,
        )
    }
}

@Composable
private fun ColumnScope.CommentsBody(state: CommentsUiState, listState: LazyListState, onAction: (CommentAction) -> Unit) {
    val content = state.content
    if (content is CommentsContent.Ready) {
        ThreadList(content, state.expanded, listState, onAction)
    } else BoxWithConstraints(Modifier.fillMaxSize()) {
        val bodyHeight = maxHeight
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight)) {
            if (content != CommentsContent.Disabled) Simplebox(onAction)
            val messageMinHeight = (bodyHeight - if (content == CommentsContent.Disabled) 0.dp else 89.dp).coerceAtLeast(48.dp)
            when (content) {
                CommentsContent.Loading -> Box(
                    Modifier.fillMaxWidth().heightIn(min = messageMinHeight), contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp).testTag("comments-loading"),
                        color = DesignTokens.Secondary, strokeWidth = 2.dp)
                }
                CommentsContent.Failed -> MessageBlock("Something went wrong while loading comments.", messageMinHeight, onRetry = { onAction(CommentAction.Retry) })
                CommentsContent.Empty -> MessageBlock("No comments yet.", messageMinHeight)
                CommentsContent.Disabled -> MessageBlock("Comments are turned off.", messageMinHeight)
                is CommentsContent.Ready -> Unit
            }
        }
    }
}

@Composable
private fun MessageBlock(message: String, minHeight: Dp, onRetry: (() -> Unit)? = null) {
    Box(Modifier.fillMaxWidth().heightIn(min = minHeight), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            AppText(
                message,
                Modifier.testTag(if (onRetry != null) "comments-error" else "comments-message"),
                size = 14, line = 20, color = DesignTokens.Secondary,
            )
            if (onRetry != null) {
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(20.dp)).clickable(role = Role.Button, onClick = onRetry)
                        .padding(horizontal = 16.dp).testTag("comments-retry"),
                    contentAlignment = Alignment.Center,
                ) {
                    AppText("Retry", size = 14, line = 20, weight = FontWeight.Medium, color = Color(0xFF065FD4))
                }
            }
        }
    }
}

@Composable
private fun Simplebox(onAction: (CommentAction) -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("comments-simplebox")) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = CommentsLayout.SimpleboxHeight.dp)
                .padding(start = CommentsLayout.CommentStartInset.dp, end = CommentsLayout.InputEndInset.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DefaultAvatar(CommentsLayout.AvatarLarge.dp)
            Spacer(Modifier.width(CommentsLayout.AvatarGap.dp))
            Box(
                Modifier.weight(1f).heightIn(min = CommentsLayout.InputHeight.dp)
                    .clip(RoundedCornerShape(CommentsLayout.InputRadius.dp))
                    .border(1.dp, Color.Black.copy(alpha = CommentsLayout.DividerAlpha), RoundedCornerShape(CommentsLayout.InputRadius.dp))
                    .clickable(role = Role.Button) { onAction(CommentAction.Compose) }
                    .testTag("comments-input")
                    .semantics { contentDescription = "Add a comment" }
                    .padding(start = CommentsLayout.InputTextInset.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                AppText("Add a comment...", size = 14, line = 20, color = DesignTokens.Secondary)
            }
        }
        Divider()
    }
}

@Composable
private fun ThreadList(content: CommentsContent.Ready, expanded: Set<String>, listState: LazyListState, onAction: (CommentAction) -> Unit) {
    LaunchedEffect(listState, content.threads.size, content.nextCursor, content.loadingMore, content.loadMoreFailed) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { lastVisible ->
                if (content.nextCursor == null || content.loadingMore || content.loadMoreFailed) return@collect
                if (lastVisible >= content.threads.size) onAction(CommentAction.LoadMore)
            }
    }
    LazyColumn(Modifier.fillMaxSize().testTag("comments-list"), state = listState) {
        item(key = "simplebox") { Simplebox(onAction) }
        items(content.threads, key = { it.id }) { thread ->
            ThreadItem(
                thread = thread,
                expanded = thread.id in expanded,
                onAction = onAction,
            )
        }
        item(key = "footer") { ListFooter(content, onAction) }
    }
}

@Composable
private fun ListFooter(content: CommentsContent.Ready, onAction: (CommentAction) -> Unit) {
    when {
        content.loadingMore -> Box(
            Modifier.fillMaxWidth().height(56.dp).testTag("comments-footer-loading"),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                Modifier.size(20.dp), color = DesignTokens.Secondary, strokeWidth = 2.dp,
            )
        }
        content.loadMoreFailed -> Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp).testTag("comments-footer-error"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppText("Couldn't load more comments", Modifier.weight(1f), size = 14, line = 20, color = DesignTokens.Secondary)
            Box(
                Modifier.height(40.dp).clip(RoundedCornerShape(20.dp))
                    .clickable(role = Role.Button) { onAction(CommentAction.LoadMore) }
                    .padding(horizontal = 16.dp).testTag("comments-footer-retry"),
                contentAlignment = Alignment.Center,
            ) { AppText("Retry", size = 14, line = 20, weight = FontWeight.Medium) }
        }
        else -> Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ThreadItem(thread: CommentThread, expanded: Boolean, onAction: (CommentAction) -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("comment-${thread.id}")) {
        Spacer(Modifier.height(CommentsLayout.ThreadTopPad.dp))
        ThreadContent(thread, onAction)
        if (thread.replies.isNotEmpty()) {
            Spacer(Modifier.height(CommentsLayout.ReplyToggleTopGap.dp))
            if (expanded) {
                Column(
                    Modifier.fillMaxWidth().padding(start = CommentsLayout.ReplyToggleStart.dp, end = 20.dp)
                        .testTag("comment-${thread.id}-replies"),
                ) {
                    thread.replies.forEach { reply ->
                        ReplyContent(thread.id, reply, onAction)
                        Spacer(Modifier.height(CommentsLayout.ReplyRowGap.dp))
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
            RepliesToggle(
                label = if (expanded) "Hide replies" else "Show more replies",
                tag = "comment-${thread.id}-reply-toggle",
                onClick = { onAction(CommentAction.ToggleReplies(thread.id)) },
            )
        }
        Spacer(Modifier.height(CommentsLayout.ThreadBottomGap.dp))
    }
}

@Composable
private fun ThreadContent(thread: CommentThread, onAction: (CommentAction) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .padding(start = CommentsLayout.CommentStartInset.dp, end = 20.dp),
    ) {
        CommentAvatar(thread.author, CommentsLayout.AvatarLarge.dp, thread.authorChannelId, onAction)
        Spacer(Modifier.width(CommentsLayout.AvatarGap.dp))
        Column(Modifier.weight(1f).padding(top = CommentsLayout.AuthorTopPad.dp)) {
            CommentAuthorRow(thread.author, thread.age, thread.id, thread.authorChannelId, onAction)
            Spacer(Modifier.height(CommentsLayout.AuthorBodyGap.dp))
            AppText(
                thread.body,
                Modifier.testTag("comment-${thread.id}-body"),
                size = CommentsLayout.BodySize.toInt(),
                line = CommentsLayout.BodyLine.toInt(),
                exactLineHeight = CommentsLayout.BodyLine.sp,
            )
            Spacer(Modifier.height(3.dp))
            CommentActions(
                likes = thread.likes,
                replyCount = thread.replyCount,
                description = "comment by ${thread.author.handle}",
                tagPrefix = "comment-${thread.id}",
                onLike = { onAction(CommentAction.LikeThread(thread.id)) },
                onDislike = { onAction(CommentAction.DislikeThread(thread.id)) },
                onReply = { onAction(CommentAction.Reply(thread.id)) },
                onMore = { onAction(CommentAction.OpenThreadMenu(thread.id)) },
            )
            Spacer(Modifier.height(5.dp))
        }
    }
}

@Composable
private fun ReplyContent(threadId: String, reply: CommentReply, onAction: (CommentAction) -> Unit) {
    Row(Modifier.fillMaxWidth().testTag("reply-${reply.id}")) {
        CommentAvatar(reply.author, CommentsLayout.ReplyAvatar.dp, reply.authorChannelId, onAction)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f).padding(top = 4.dp)) {
            CommentAuthorRow(reply.author, reply.age, reply.id, reply.authorChannelId, onAction)
            Spacer(Modifier.height(CommentsLayout.AuthorBodyGap.dp))
            AppText(
                reply.body,
                Modifier.testTag("reply-${reply.id}-body"),
                size = CommentsLayout.BodySize.toInt(),
                line = CommentsLayout.BodyLine.toInt(),
                exactLineHeight = CommentsLayout.BodyLine.sp,
            )
            Spacer(Modifier.height(3.dp))
            CommentActions(
                likes = reply.likes,
                replyCount = null,
                description = "reply by ${reply.author.handle}",
                tagPrefix = "reply-${reply.id}",
                onLike = { onAction(CommentAction.LikeReply(threadId, reply.id)) },
                onDislike = { onAction(CommentAction.DislikeReply(threadId, reply.id)) },
                onReply = { onAction(CommentAction.Reply(threadId, reply.id)) },
                onMore = { onAction(CommentAction.OpenReplyMenu(threadId, reply.id)) },
            )
            Spacer(Modifier.height(1.dp))
        }
    }
}

@Composable
private fun CommentAuthorRow(
    author: CommentAuthor,
    age: String,
    testTagSuffix: String,
    channelId: String,
    onAction: (CommentAction) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(end = 4.dp).semantics {
            contentDescription = "Open channel ${author.handle}"
        }.clickable(role = Role.Button) {
            if (channelId.isNotEmpty()) onAction(CommentAction.OpenAuthor(channelId))
        }.testTag("comment-$testTagSuffix-open-author"),
    ) {
        AppText(
            author.handle,
            Modifier.weight(1f).testTag("comment-$testTagSuffix-author"),
            size = CommentsLayout.AuthorSize.toInt(),
            line = CommentsLayout.AuthorLine.toInt(),
            maxLines = 1,
        )
        AppText(
            age,
            size = CommentsLayout.AgeSize.toInt(),
            line = CommentsLayout.AgeLine.toInt(),
            maxLines = 1,
        )
    }
}

@Composable
private fun CommentActions(
    likes: String,
    replyCount: Int?,
    description: String,
    tagPrefix: String,
    onLike: () -> Unit,
    onDislike: () -> Unit,
    onReply: () -> Unit,
    onMore: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(CommentsLayout.ActionRowHeight.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).offset(x = (-6).dp).horizontalScroll(rememberScrollState())) {
            CountedAction(AppIcon.Like, likes, "Like $description", "$tagPrefix-like", onLike)
            Spacer(Modifier.width(7.dp))
            PlainAction(AppIcon.Dislike, "Dislike $description", "$tagPrefix-dislike", onDislike)
            Spacer(Modifier.width(7.dp))
            if (replyCount != null) {
                CountedAction(AppIcon.Comment, replyCount.toString(), "Reply to $description", "$tagPrefix-reply", onReply)
            } else {
                PlainAction(AppIcon.Comment, "Reply to $description", "$tagPrefix-reply", onReply)
            }
        }
        Box(
            Modifier.size(CommentsLayout.KebabTarget.dp).clip(CircleShape)
                .clickable(role = Role.Button, onClick = onMore)
                .testTag("$tagPrefix-more")
                .semantics { contentDescription = "More actions for $description" },
            contentAlignment = Alignment.Center,
        ) { AppIcon(AppIcon.More, Modifier.size(CommentsLayout.KebabIcon.dp)) }
    }
}

@Composable
private fun CountedAction(icon: AppIcon, count: String, description: String, tag: String, onClick: () -> Unit) {
    Row(
        Modifier.height(CommentsLayout.ActionRowHeight.dp).clickable(role = Role.Button, onClick = onClick)
            .testTag(tag).semantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            AppIcon(icon, Modifier.size(CommentsLayout.ActionIcon.dp))
        }
        Spacer(Modifier.width(1.dp))
        AppText(count, size = CommentsLayout.ActionCountSize.toInt(), line = 13, color = DesignTokens.Secondary)
    }
}

@Composable
private fun PlainAction(icon: AppIcon, description: String, tag: String, onClick: () -> Unit) {
    Box(
        Modifier.height(CommentsLayout.ActionRowHeight.dp).clickable(role = Role.Button, onClick = onClick)
            .testTag(tag).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            AppIcon(icon, Modifier.size(CommentsLayout.ActionIcon.dp))
        }
    }
}

@Composable
private fun RepliesToggle(label: String, tag: String, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().padding(start = CommentsLayout.ReplyToggleStart.dp, end = 12.dp)
            .heightIn(min = CommentsLayout.ReplyToggleHeight.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag(tag),
        contentAlignment = Alignment.CenterStart,
    ) {
        AppText(label, size = 14, line = 20, weight = FontWeight.Medium)
    }
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Color.Black.copy(alpha = CommentsLayout.DividerAlpha)))
}

@Composable
private fun DefaultAvatar(size: Dp) {
    Image(painterResource(R.drawable.comment_avatar), contentDescription = null,
        modifier = Modifier.size(size).clip(CircleShape), contentScale = ContentScale.Crop)
}

@Composable
private fun CommentAvatar(author: CommentAuthor, size: Dp, channelId: String, onAction: (CommentAction) -> Unit) {
    Image(
        painterResource(
            when (author.artwork) {
                "dream" -> R.drawable.sample_dream
                "sintel" -> R.drawable.sample_sintel
                else -> R.drawable.sample_bunny
            },
        ),
        contentDescription = null,
        modifier = Modifier.size(size).clip(CircleShape).then(if (channelId.isBlank()) Modifier else
            Modifier.clickable(role = Role.Button) { onAction(CommentAction.OpenAuthor(channelId)) }
                .semantics { contentDescription = "Open channel ${author.handle}" }),
        contentScale = ContentScale.Crop,
    )
}
