package com.hhst.youtubelite.post.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
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
import com.hhst.youtubelite.post.domain.PostDetailAction
import com.hhst.youtubelite.post.domain.PostDetailContent
import com.hhst.youtubelite.post.domain.PostDetailOverlay
import com.hhst.youtubelite.post.domain.PostDetailUiState
import com.hhst.youtubelite.post.domain.PostLayout
import com.hhst.youtubelite.post.domain.PostMenuAction
import com.hhst.youtubelite.post.domain.PostDestination
import com.hhst.youtubelite.comments.domain.CommentAction
import com.hhst.youtubelite.comments.domain.CommentsUiState
import com.hhst.youtubelite.comments.presentation.CommentsPanel
import com.hhst.youtubelite.ui.design.AppIcon
import com.hhst.youtubelite.ui.design.AppIconButton
import com.hhst.youtubelite.ui.design.AppText
import com.hhst.youtubelite.ui.design.AppTheme
import com.hhst.youtubelite.ui.design.Artwork
import com.hhst.youtubelite.ui.design.DesignTokens
import com.hhst.youtubelite.ui.design.MenuBottomSheet
import com.hhst.youtubelite.ui.design.MenuSheetItem
import com.hhst.youtubelite.ui.design.BrowseNavigationBar

@Composable
fun PostDetailScreen(
    state: PostDetailUiState,
    onAction: (PostDetailAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    commentsState: CommentsUiState = CommentsUiState(),
    onCommentsAction: (CommentAction) -> Unit = {},
) {
    val commentsListState = rememberLazyListState()
    AppTheme {
        Box(modifier.fillMaxSize().background(Color.White).testTag("post-detail-page")) {
            Column(Modifier.fillMaxSize().then(
                if (state.overlay != PostDetailOverlay.None || state.commentsOpen) Modifier.clearAndSetSemantics {} else Modifier,
            )) {
                PostDetailTopBar(state, onAction, onBack)
                when (val content = state.content) {
                    PostDetailContent.Loading -> Box(
                        Modifier.weight(1f).fillMaxWidth().testTag("post-detail-loading"),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 2.5.dp, color = DesignTokens.Secondary)
                    }
                    PostDetailContent.Failed -> Box(Modifier.weight(1f)) { PostDetailFailed(onAction) }
                    is PostDetailContent.Ready -> Box(Modifier.weight(1f)) {
                        PostDetailContentColumn(state, onAction)
                    }
                }
                BrowseNavigationBar("post-detail") { onAction(PostDetailAction.SelectDestination(PostDestination.valueOf(it.name))) }
            }
            when (val overlay = state.overlay) {
                PostDetailOverlay.None -> Unit
                PostDetailOverlay.Menu -> MenuBottomSheet(
                    items = PostMenuAction.entries.map { action ->
                        MenuSheetItem(
                            icon = AppIcon.Report,
                            label = action.label,
                            tag = "post-detail-menu-${action.name.lowercase()}",
                        )
                    },
                    onDismiss = { onAction(PostDetailAction.CloseOverlay) },
                    onSelect = { index -> onAction(PostDetailAction.MenuSelected(PostMenuAction.entries[index])) },
                    tag = "post-detail-menu",
                    paneTitle = "Post actions",
                )
                is PostDetailOverlay.Image -> PostImageViewer(
                    images = state.post?.images.orEmpty(),
                    initialIndex = overlay.index,
                    onClose = { onAction(PostDetailAction.CloseOverlay) },
                    onPageSelected = { onAction(PostDetailAction.SelectImage(it)) },
                )
            }
            if (state.commentsOpen) {
                CommentsPanel(
                    state = commentsState,
                    commentCount = commentsState.total,
                    onAction = onCommentsAction,
                    onClose = { onAction(PostDetailAction.CloseComments) },
                    modifier = Modifier.fillMaxSize(),
                    listState = commentsListState,
                    sheetTopOverride = PostLayout.DetailToolbar,
                )
            }
        }
    }
}

@Composable
private fun PostDetailTopBar(
    state: PostDetailUiState,
    onAction: (PostDetailAction) -> Unit,
    onBack: () -> Unit,
) {
    val title = state.post?.text?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() } ?: "Post"
    Row(
        Modifier.fillMaxWidth().heightIn(min = PostLayout.DetailToolbar.dp).background(Color.White)
            .testTag("post-detail-topbar"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(53.dp), contentAlignment = Alignment.CenterStart) {
            AppIconButton(AppIcon.Back, "Close post", diameter = 48.dp) { onBack() }
        }
        AppText(
            title,
            Modifier.weight(1f).padding(end = 4.dp).testTag("post-detail-title"),
            size = 20, line = 28, weight = FontWeight.Bold, maxLines = 1,
        )
        if (!state.live) AppIconButton(
            AppIcon.More, "Post actions",
            modifier = Modifier.testTag("post-detail-more"),
            diameter = 48.dp,
        ) { onAction(PostDetailAction.OpenMenu) }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(DesignTokens.Ghost))
}

@Composable
private fun PostDetailContentColumn(
    state: PostDetailUiState,
    onAction: (PostDetailAction) -> Unit,
) {
    val post = state.post ?: return
    LazyColumn(
        Modifier.fillMaxSize().testTag("post-detail-list"),
        contentPadding = PaddingValues(bottom = PostLayout.CardBottom.dp),
    ) {
        item("author") {
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, top = 12.dp, end = 12.dp)
                    .heightIn(min = 43.dp).testTag("post-detail-author"),
                verticalAlignment = Alignment.Top,
            ) {
                Box(
                    Modifier.size(width = 48.dp, height = 40.dp)
                        .clickable(role = Role.Button) { onAction(PostDetailAction.OpenChannel) }
                        .semantics { contentDescription = "Go to channel ${post.author.name}" },
                    contentAlignment = Alignment.TopStart,
                ) {
                    Box(Modifier.size(40.dp).clip(CircleShape)) {
                        Artwork(post.author.avatar, Modifier.fillMaxSize())
                    }
                }
                Column(Modifier.weight(1f)) {
                    AppText(post.author.name, Modifier.testTag("post-detail-author-name"), size = 14, line = 20, weight = FontWeight.Medium)
                    AppText(post.age, size = 14, line = 20, color = DesignTokens.Secondary)
                }
            }
        }
        item("body") {
            AppText(
                post.text,
                Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 12.dp)
                    .testTag("post-detail-body"),
                size = 16, line = 22,
            )
        }
        if (post.images.isNotEmpty()) {
            item("images") {
                Spacer(Modifier.height(12.dp))
                PostImageCarousel(
                    images = post.images,
                    selectedIndex = state.selectedImage,
                    onSelected = { onAction(PostDetailAction.SelectImage(it)) },
                    onOpen = { onAction(PostDetailAction.OpenImage(it)) },
                    modifier = Modifier.testTag("post-detail-images"),
                )
            }
        }
        item("feedback") {
            PostFeedbackRow(
                likes = post.likes,
                comments = post.comments,
                onAction = onAction,
            )
        }
    }
}

@Composable
private fun PostFeedbackRow(
    likes: String,
    comments: String,
    onAction: (PostDetailAction) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 16.dp)
            .heightIn(min = PostLayout.FeedbackHeight.dp).testTag("post-detail-feedback"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIconButton(
            AppIcon.Like, "Like post", modifier = Modifier.testTag("post-detail-like"),
            diameter = 40.dp,
        ) { onAction(PostDetailAction.Like) }
        AppText(likes, Modifier.widthIn(min = 40.dp), size = 14, line = 20)
        AppIconButton(
            AppIcon.Dislike, "Dislike post", modifier = Modifier.testTag("post-detail-dislike"),
            diameter = 40.dp,
        ) { onAction(PostDetailAction.Dislike) }
        Spacer(Modifier.weight(1f))
        Row(
            Modifier.clickable(role = Role.Button) { onAction(PostDetailAction.Comments) }
                .padding(horizontal = 16.dp, vertical = 8.dp).testTag("post-detail-comments"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(AppIcon.Comment)
            AppText(comments, Modifier.padding(start = 6.dp), size = 14, line = 20, weight = FontWeight.Medium)
        }
    }
}

@Composable
private fun PostDetailFailed(onAction: (PostDetailAction) -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp).testTag("post-detail-failed"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        AppText("This post could not be loaded.", size = 16, line = 22, color = DesignTokens.Secondary)
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier.heightIn(min = 36.dp).clip(RoundedCornerShape(18.dp))
                .background(DesignTokens.Ink)
                .clickable(role = Role.Button) { onAction(PostDetailAction.Retry) }
                .padding(horizontal = 20.dp).testTag("post-detail-retry"),
            contentAlignment = Alignment.Center,
        ) {
            AppText("Retry", size = 14, line = 20, weight = FontWeight.Medium, color = Color.White)
        }
    }
}
