package com.hhst.youtubelite.explore.presentation

import com.hhst.youtubelite.ui.components.mediaActions
import com.hhst.youtubelite.ui.components.MediaActionItem
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items as listItems
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hhst.youtubelite.R
import com.hhst.youtubelite.explore.domain.ExploreAction
import com.hhst.youtubelite.explore.domain.ExploreCategory
import com.hhst.youtubelite.explore.domain.ExploreContent
import com.hhst.youtubelite.explore.domain.ExploreUiState
import com.hhst.youtubelite.home.domain.FeedItem
import com.hhst.youtubelite.library.presentation.LibraryError
import com.hhst.youtubelite.library.presentation.LibrarySkeleton
import com.hhst.youtubelite.ui.design.AppIconButton
import com.hhst.youtubelite.ui.design.AppIcon
import com.hhst.youtubelite.ui.design.AppText
import com.hhst.youtubelite.ui.design.DesignTokens

@Composable
fun ExploreScreen(state: ExploreUiState, onAction: (ExploreAction) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxSize().background(DesignTokens.Background).testTag("explore-screen")) {
        val width = maxWidth.value
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().heightIn(min = DesignTokens.TopBarHeight).testTag("explore-topbar"), verticalAlignment = Alignment.CenterVertically) {
                AppIconButton(AppIcon.Back, "Back", diameter = 48.dp) { onBack() }
                AppText("Explore", Modifier.padding(end = 16.dp), size = 20, line = 28, weight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
            }
            CategoryChips(state.category, onAction)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (val content = state.content) {
                    ExploreContent.Loading -> LibrarySkeleton("explore-skeleton")
                    ExploreContent.Failed -> LibraryError("explore-error") { onAction(ExploreAction.Retry) }
                    is ExploreContent.Ready -> CategoryContainer(content.page.videos, width, onAction)
                }
            }
        }
    }
}

@Composable
private fun CategoryChips(selected: ExploreCategory, onAction: (ExploreAction) -> Unit) {
    LazyRow(Modifier.fillMaxWidth().height(48.dp).testTag("explore-chips"), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(horizontal = 16.dp)) {
        listItems(ExploreCategory.entries) { category ->
            Box(Modifier.clip(RoundedCornerShape(8.dp))
                .background(if (category == selected) DesignTokens.Ink else DesignTokens.Surface)
                .clickable(role = Role.Tab) { onAction(ExploreAction.SelectCategory(category)) }
                .testTag("explore-chip-${category.name}")
                .padding(horizontal = 12.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
                AppText(category.label, size = 14, line = 20, weight = FontWeight.Medium,
                    color = if (category == selected) DesignTokens.Background else DesignTokens.Ink)
            }
        }
    }
}

/** The one container template every category renders through. */
@Composable
private fun CategoryContainer(videos: List<FeedItem.Video>, width: Float, onAction: (ExploreAction) -> Unit) {
    val columns = when {
        width < 528f -> 1
        width < 994f -> 2
        width < 1320f -> 3
        else -> 4
    }
    val compact = width < 528f
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = Modifier.fillMaxSize().testTag("explore-list"),
        contentPadding = PaddingValues(
            start = if (compact) 0.dp else 16.dp,
            end = if (compact) 0.dp else 16.dp,
            bottom = 24.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(DesignTokens.GridGap),
    ) {
        gridItems(videos, key = { it.id }, contentType = { "video" }) { item ->
            Box(Modifier.padding(bottom = if (compact) 0.dp else 16.dp)) {
                ExploreVideoCard(item, compact, onAction)
            }
        }
    }
}

@Composable
private fun ExploreVideoCard(item: FeedItem.Video, compact: Boolean, onAction: (ExploreAction) -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("explore-item-${item.id}")) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9).clip(RoundedCornerShape(if (compact) 0.dp else 12.dp))
            .mediaActions(MediaActionItem(item.id, item.title, item.author, item.artwork)) { onAction(ExploreAction.OpenVideo(item.id)) }
            .semantics { contentDescription = "${item.title}, ${item.duration}" }) {
            Artwork(item.artwork, Modifier.fillMaxSize())
            Box(Modifier.align(Alignment.BottomEnd).padding(8.dp)
                .background(if (item.live) DesignTokens.Red else Color.Black.copy(alpha = 0.75f), RoundedCornerShape(4.dp))
                .padding(horizontal = 4.dp, vertical = 1.dp)) {
                AppText(item.duration, size = 12, line = 16, weight = FontWeight.Medium, color = Color.White)
            }
        }
        Row(Modifier.padding(start = if (compact) 12.dp else 0.dp, top = 8.dp, end = if (compact) 12.dp else 16.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.size(36.dp).clip(CircleShape).clickable(role = Role.Button) {
                onAction(ExploreAction.OpenChannel(item.channelId))
            }.semantics { contentDescription = "Go to channel ${item.author}" }) {
                Artwork(item.artwork, Modifier.fillMaxSize())
            }
            Spacer(Modifier.width(8.dp))
            Column {
                AppText(item.title, size = 16, line = 22, weight = FontWeight.Medium, maxLines = 2)
                AppText("${item.author} · ${item.metadata}", Modifier.padding(top = 2.dp), size = 12, line = 18,
                    color = DesignTokens.Secondary, maxLines = 1)
            }
        }
    }
}

@Composable
private fun Artwork(asset: String, modifier: Modifier) {
    com.hhst.youtubelite.ui.design.Artwork(asset, modifier)
}
