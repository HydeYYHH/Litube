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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hhst.youtubelite.R
import com.hhst.youtubelite.ui.design.*
import com.hhst.youtubelite.home.domain.*

@Composable
internal fun FeedCard(item: FeedItem, compact: Boolean, onAction: (HomeAction) -> Unit) {
    when (item) {
        is FeedItem.Video -> VideoCard(item, compact, onAction)
        is FeedItem.Mix -> MixCard(item, compact, onAction)
        is FeedItem.Shorts -> ShortsShelf(item, onAction)
        is FeedItem.Post -> PostCard(item, onAction)
    }
}

@Composable
private fun Artwork(asset: String, modifier: Modifier = Modifier) {
    Image(painterResource(when (asset) { "dream" -> R.drawable.sample_dream; "sintel" -> R.drawable.sample_sintel; else -> R.drawable.sample_bunny }),
        contentDescription = null, modifier = modifier, contentScale = ContentScale.Crop)
}

@Composable
private fun Avatar(asset: String, author: String, channelId: String, onAction: (HomeAction) -> Unit) {
    Box(Modifier.size(32.dp).clip(CircleShape).clickable(role = Role.Button) {
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
        Row(Modifier.padding(start = if (compact) 12.dp else 0.dp, top = 8.dp, end = if (compact) 12.dp else 0.dp), verticalAlignment = Alignment.Top) {
            Avatar(item.artwork, item.author, item.channelId, onAction)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).clickable(role = Role.Button) { onAction(HomeAction.Navigate(HomeNavigationRequest.Watch(item.id))) }) {
                AppText(item.title, size = 16, line = 22, weight = FontWeight.Medium, maxLines = 2)
                AppText("${item.author} · ${item.metadata}", Modifier.padding(top = 2.dp), size = 12, line = 18, color = DesignTokens.Secondary, maxLines = 2)
            }
            AppIconButton(AppIcon.More, "More actions for ${item.title}", diameter = 24.dp) { onAction(HomeAction.OpenMenu(item.id)) }
        }
    }
}

@Composable
private fun MixCard(item: FeedItem.Mix, compact: Boolean, onAction: (HomeAction) -> Unit) {
    Column(Modifier.testTag("feed-item-${item.id}")) {
        Box(Modifier.fillMaxWidth()) {
            Box(Modifier.padding(horizontal = 12.dp).fillMaxWidth().aspectRatio(16f / 9).offset(y = (-8).dp).background(Color(0xFFD5C1BD), RoundedCornerShape(12.dp)))
            Box(Modifier.padding(horizontal = 6.dp).fillMaxWidth().aspectRatio(16f / 9).offset(y = (-4).dp).background(Color(0xFF9C8580), RoundedCornerShape(12.dp)))
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9).clip(RoundedCornerShape(if (compact) 0.dp else 12.dp))
                .clickable(role = Role.Button) { onAction(HomeAction.Navigate(HomeNavigationRequest.Watch(item.id))) }.semanticsLabel(item.title)) {
                Artwork(item.artwork, Modifier.fillMaxSize())
                Box(Modifier.align(Alignment.BottomEnd).padding(8.dp).background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 2.dp)) {
                    AppText("≋ Mix", size = 12, line = 16, weight = FontWeight.Medium, color = Color.White)
                }
            }
        }
        Row(Modifier.padding(start = if (compact) 12.dp else 4.dp, top = 12.dp, end = 4.dp)) {
            Column(Modifier.weight(1f)) {
                AppText(item.title, weight = FontWeight.Medium, maxLines = 2, exactLineHeight = 17.5.sp)
                AppText(item.authors, Modifier.padding(top = 8.dp), size = 12, line = 18, color = DesignTokens.Secondary, maxLines = 2)
            }
            AppIconButton(AppIcon.More, "More actions for ${item.title}", diameter = 32.dp) { onAction(HomeAction.OpenMenu(item.id)) }
        }
    }
}

@Composable
private fun ShortsShelf(item: FeedItem.Shorts, onAction: (HomeAction) -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("shorts-shelf")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(AppIcon.Shorts, color = DesignTokens.Red)
            AppText("Shorts", Modifier.padding(start = 8.dp).weight(1f), size = 20, line = 28, weight = FontWeight.Bold)
            AppIconButton(AppIcon.More, "Shorts options") { onAction(HomeAction.OpenMenu(item.id)) }
        }
        BoxWithConstraints {
            val cardWidth = ((maxWidth - 36.dp) / 2).coerceAtMost(200.dp)
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(item.videos, key = { it.id }) { video ->
                    Column(Modifier.width(cardWidth)) {
                        Box(Modifier.fillMaxWidth().aspectRatio(9f / 16).clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) {
                            onAction(HomeAction.Navigate(HomeNavigationRequest.Watch(video.id)))
                        }.semanticsLabel(video.title)) { Artwork(video.artwork, Modifier.fillMaxSize()) }
                        Row(Modifier.padding(top = 8.dp)) {
                            Column(Modifier.weight(1f)) {
                                AppText(video.title, weight = FontWeight.Medium, maxLines = 2)
                                AppText(video.metadata, size = 12, line = 18, color = DesignTokens.Secondary)
                            }
                            AppIconButton(AppIcon.More, "More actions for ${video.title}", diameter = 24.dp) { onAction(HomeAction.OpenMenu(video.id)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PostCard(item: FeedItem.Post, onAction: (HomeAction) -> Unit) {
    Column(Modifier.padding(horizontal = 12.dp).testTag("community-post")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(item.artwork, item.author, "studio", onAction)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                AppText(item.author, size = 13, line = 18, weight = FontWeight.Medium)
                AppText(item.age, size = 12, line = 18, color = DesignTokens.Secondary)
            }
            AppIconButton(AppIcon.More, "Post actions", diameter = 32.dp) { onAction(HomeAction.OpenMenu(item.id)) }
        }
        AppText(item.text, Modifier.padding(vertical = 12.dp), maxLines = 3)
        Artwork(item.artwork, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)))
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIconButton(AppIcon.Like, "Like post", diameter = 40.dp) { onAction(HomeAction.Navigate(HomeNavigationRequest.PostFeedback(item.id, true))) }
            AppText("1.2K", size = 12, line = 18)
            AppIconButton(AppIcon.Dislike, "Dislike post", diameter = 40.dp) { onAction(HomeAction.Navigate(HomeNavigationRequest.PostFeedback(item.id, false))) }
            Spacer(Modifier.weight(1f))
            Row(Modifier.clickable(role = Role.Button) { onAction(HomeAction.Navigate(HomeNavigationRequest.PostComments(item.id))) }.padding(8.dp)) {
                AppIcon(AppIcon.Comment)
                AppText("42", Modifier.padding(start = 6.dp), size = 12, line = 18)
            }
        }
    }
}
