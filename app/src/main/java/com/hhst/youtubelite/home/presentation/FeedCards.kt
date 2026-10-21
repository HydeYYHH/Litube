package com.hhst.youtubelite.home.presentation

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hhst.youtubelite.R
import com.hhst.youtubelite.ui.design.*
import com.hhst.youtubelite.home.domain.*

@Composable
internal fun FeedCard(item: FeedItem, compact: Boolean, windowWidth: Float, onAction: (HomeAction) -> Unit) {
    when (item) {
        is FeedItem.Video -> VideoCard(item, compact, onAction)
        is FeedItem.Mix -> MixCard(item, compact, onAction)
        is FeedItem.Shorts -> ShortsShelf(item, windowWidth, onAction)
        is FeedItem.Post -> BoxWithConstraints {
            Box(Modifier.requiredWidth(maxWidth + if (compact) 0.dp else 32.dp)) { PostCard(item, onAction) }
        }
    }
}

@Composable
private fun Artwork(asset: String, modifier: Modifier = Modifier) {
    Image(painterResource(when (asset) { "dream" -> R.drawable.sample_dream; "sintel" -> R.drawable.sample_sintel; else -> R.drawable.sample_bunny }),
        contentDescription = null, modifier = modifier, contentScale = ContentScale.Crop)
}

@Composable
private fun Avatar(asset: String, author: String, channelId: String, onAction: (HomeAction) -> Unit, size: Dp = 36.dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(CircleShape).clickable(role = Role.Button) {
        onAction(HomeAction.Navigate(HomeNavigationRequest.Channel(channelId)))
    }.testTag("channel-$channelId").semanticsLabel("Go to channel $author")) {
        Artwork(asset, Modifier.fillMaxSize())
    }
}

private fun Modifier.semanticsLabel(label: String) = this.then(Modifier.semantics { contentDescription = label })

@Composable
private fun VideoCard(item: FeedItem.Video, compact: Boolean, onAction: (HomeAction) -> Unit) {
    Column(Modifier.testTag("feed-item-${item.id}")) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9).clip(RoundedCornerShape(if (compact) 0.dp else 12.dp))
            .clickable(role = Role.Button) { onAction(HomeAction.Navigate(HomeNavigationRequest.Watch(item.id))) }
            .semanticsLabel("${item.title}, ${item.duration}")) {
            Artwork(item.artwork, Modifier.fillMaxSize())
            Box(Modifier.align(Alignment.BottomEnd).padding(8.dp).background(if (item.live) DesignTokens.Red else Color.Black.copy(alpha = 0.75f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp)) {
                AppText(item.duration, size = 12, line = 16, weight = FontWeight.Medium, color = Color.White)
            }
            item.progress?.let { progress ->
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.Black.copy(alpha = 0.5f))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(DesignTokens.Red))
                }
            }
        }
        Row(Modifier.padding(start = if (compact) 12.dp else 0.dp, top = 8.dp), verticalAlignment = Alignment.Top) {
            Avatar(item.artwork, item.author, item.channelId, onAction, size = 36.dp)
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) {
                Column(Modifier.padding(end = if (compact) 12.dp else 0.dp).clickable(role = Role.Button) { onAction(HomeAction.Navigate(HomeNavigationRequest.Watch(item.id))) }) {
                    AppText(item.title, Modifier.padding(end = 24.dp), size = 16, line = 22, weight = FontWeight.Medium, maxLines = 2)
                    AppText("${item.author} · ${item.metadata}", Modifier.padding(top = 2.dp), size = 12, line = 18, color = DesignTokens.Secondary, maxLines = 1)
                }
                // Sampled menu anchors: phone right inset 2px, grid +10px overhang, top -6px.
                AppIconButton(AppIcon.More, "More actions for ${item.title}",
                    modifier = Modifier.align(Alignment.TopEnd).offset(x = if (compact) (-2).dp else 10.dp, y = (-6).dp),
                    diameter = 40.dp) { onAction(HomeAction.OpenMenu(item.id)) }
            }
        }
    }
}

@Composable
private fun MixCard(item: FeedItem.Mix, compact: Boolean, onAction: (HomeAction) -> Unit) {
    Column(Modifier.testTag("feed-item-${item.id}")) {
        Box(Modifier.padding(horizontal = if (compact) 12.dp else 0.dp).padding(top = if (compact) 10.dp else 0.dp).fillMaxWidth().offset(y = if (compact) 0.dp else (-2).dp)) {
            if (compact) Box(Modifier.padding(horizontal = 12.dp).fillMaxWidth().aspectRatio(16f / 9).offset(y = (-8).dp).background(Color(item.palette.stackColor).copy(alpha = 0.5f), RoundedCornerShape(12.dp)))
            Box(Modifier.padding(horizontal = 8.dp).fillMaxWidth().aspectRatio(16f / 9).offset(y = (-5).dp).background(Color(item.palette.stackColor), RoundedCornerShape(12.dp)))
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9).clip(RoundedCornerShape(12.dp))
                .clickable(role = Role.Button) { onAction(HomeAction.Navigate(HomeNavigationRequest.Watch(item.id))) }.semanticsLabel(item.title)) {
                Artwork(item.artwork, Modifier.fillMaxSize())
                Row(Modifier.align(Alignment.BottomEnd).padding(8.dp).height(24.dp).background(Color(item.palette.badgeColor), RoundedCornerShape(4.dp)).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    AppIcon(AppIcon.Mix, Modifier.size(12.dp), Color.White)
                    AppText("Mix", size = 12, line = 16, color = Color.White)
                }
            }
        }
        Row(Modifier.padding(start = if (compact) 16.dp else 4.dp, top = 12.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.weight(1f)) {
                Column(Modifier.padding(end = 40.dp)) {
                    AppText(item.title, weight = FontWeight.Medium, maxLines = 2, exactLineHeight = 17.5.sp)
                    AppText(item.authors, Modifier.padding(top = if (compact) 3.dp else 8.dp), size = 12, line = 18, color = DesignTokens.Secondary, maxLines = 2)
                }
                AppIconButton(AppIcon.More, "More actions for ${item.title}",
                    modifier = Modifier.align(Alignment.TopEnd).offset(y = (-8).dp),
                    diameter = 40.dp) { onAction(HomeAction.OpenMenu(item.id, FeedMenuKind.Mix)) }
            }
        }
    }
}

@Composable
private fun ShortsShelf(item: FeedItem.Shorts, windowWidth: Float, onAction: (HomeAction) -> Unit) {
    val columns = HomeLayout.shortsColumns(windowWidth)
    val gap = if (windowWidth < 600f) 8.dp else 16.dp
    val videos = item.videos.take(if (windowWidth < 600f) 4 else columns)
    Column(Modifier.fillMaxWidth().padding(horizontal = if (windowWidth < 528f) 16.dp else 0.dp)
        .padding(top = if (windowWidth < 528f) 12.dp else 0.dp).testTag("shorts-shelf")) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            ShortsLogo(Modifier.offset(y = (-1.667).dp))
            AppText("Shorts", Modifier.padding(start = 12.dp).weight(1f), size = 18, line = 26, weight = FontWeight.Bold)
            AppIconButton(AppIcon.More, "Shorts options", diameter = 40.dp) { onAction(HomeAction.OpenMenu(item.id, FeedMenuKind.Shorts)) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            videos.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    row.forEach { video ->
                        Box(Modifier.weight(1f).aspectRatio(2f / 3f).testTag("short-${video.id}")
                            .clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) {
                            onAction(HomeAction.Navigate(HomeNavigationRequest.Watch(video.id)))
                        }.semanticsLabel(video.title)) {
                            Artwork(video.artwork, Modifier.fillMaxSize())
                            Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.7f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.6f))))
                            if (video.isNew) AppText("New", Modifier.padding(5.dp).background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp),
                                size = 12, line = 18, weight = FontWeight.Medium, color = Color.White)
                            AppIconButton(AppIcon.More, "More actions for ${video.title}", modifier = Modifier.align(Alignment.TopEnd), diameter = 40.dp, color = Color.White) { onAction(HomeAction.OpenMenu(video.id, FeedMenuKind.Short)) }
                            AppText(video.title, Modifier.align(Alignment.BottomStart).padding(start = 8.dp, end = 8.dp, bottom = 12.dp),
                                size = 14, line = 20, color = Color.White, maxLines = 2)
                        }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun PostCard(item: FeedItem.Post, onAction: (HomeAction) -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("community-post")) {
        Row(Modifier.padding(start = 12.dp, top = 12.dp).fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Box(Modifier.size(48.dp)) { Avatar(item.artwork, item.author, item.channelId, onAction, size = 40.dp) }
            Column(Modifier.weight(1f).heightIn(min = 48.dp)) {
                AppText(item.author, size = 14, line = 20, weight = FontWeight.Medium)
                AppText(item.age, size = 14, line = 20, color = DesignTokens.Secondary)
            }
            Box(Modifier.size(width = 44.dp, height = 48.dp)) {
                AppIconButton(AppIcon.More, "Post actions", modifier = Modifier.wrapContentSize(Alignment.TopStart, unbounded = true)) { onAction(HomeAction.OpenMenu(item.id, FeedMenuKind.Post)) }
            }
        }
        AppText(item.text, Modifier.padding(12.dp).clickable(role = Role.Button) {
            onAction(HomeAction.Navigate(HomeNavigationRequest.OpenPost(item.channelId, item.postId)))
        }.testTag("post-body-${item.id}"), size = 16, line = 22, maxLines = 3)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val imageWidth = maxWidth - 48.dp
            LazyRow(Modifier.testTag("post-images-${item.id}"), contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(item.images.size) { index ->
                    Box(Modifier.size(imageWidth).clip(RoundedCornerShape(12.dp))
                        .clickable(role = Role.Button) {
                            onAction(HomeAction.Navigate(HomeNavigationRequest.OpenPost(item.channelId, item.postId)))
                        }.testTag("post-image-${item.id}-$index")) {
                        Artwork(item.images[index], Modifier.fillMaxSize())
                        if (item.images.size > 1) Box(Modifier.align(Alignment.TopEnd).padding(12.dp).size(36.dp, 24.dp)
                            .background(DesignTokens.Ink, CircleShape), contentAlignment = Alignment.Center) {
                            AppText("${index + 1}/${item.images.size}", size = 14, line = 20, color = Color.White)
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIconButton(AppIcon.Like, "Like post", diameter = 40.dp) { onAction(HomeAction.Navigate(HomeNavigationRequest.PostFeedback(item.postId, true, item.channelId))) }
            AppText(item.likes, Modifier.widthIn(min = 40.dp), size = 14, line = 20)
            AppIconButton(AppIcon.Dislike, "Dislike post", diameter = 40.dp) { onAction(HomeAction.Navigate(HomeNavigationRequest.PostFeedback(item.postId, false, item.channelId))) }
            Spacer(Modifier.weight(1f))
            Row(Modifier.clickable(role = Role.Button) { onAction(HomeAction.Navigate(HomeNavigationRequest.PostComments(item.postId, item.channelId))) }
                .padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                AppIcon(AppIcon.Comment)
                AppText(item.comments, Modifier.padding(start = 6.dp), size = 14, line = 20, weight = FontWeight.Medium)
            }
        }
    }
}
