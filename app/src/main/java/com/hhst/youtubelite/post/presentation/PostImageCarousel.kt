package com.hhst.youtubelite.post.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hhst.youtubelite.post.domain.PostLayout
import com.hhst.youtubelite.ui.design.AppText
import com.hhst.youtubelite.ui.design.Artwork
import com.hhst.youtubelite.ui.design.DesignTokens
import kotlinx.coroutines.flow.distinctUntilChanged
import com.hhst.youtubelite.ui.design.appDescription
import com.hhst.youtubelite.R

@Composable
fun PostImageCarousel(
    images: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onOpen: (Int) -> Unit = {},
) {
    if (images.isEmpty()) return
    val safeIndex = selectedIndex.coerceIn(0, images.lastIndex)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val imageWidth = (maxWidth - PostLayout.ImageInset.dp).coerceAtLeast(1.dp)
        val pagerState = rememberPagerState(initialPage = safeIndex) { images.size }
        LaunchedEffect(pagerState, images.size) {
            snapshotFlow { pagerState.settledPage }.distinctUntilChanged().collect(onSelected)
        }
        LaunchedEffect(safeIndex, pagerState) {
            if (pagerState.currentPage != safeIndex) pagerState.scrollToPage(safeIndex)
        }
        Box(Modifier.fillMaxWidth().height(imageWidth)) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxWidth().testTag("post-images"),
                contentPadding = PaddingValues(horizontal = PostLayout.PageInset.dp),
                pageSpacing = PostLayout.ImageGap.dp,
                pageSize = PageSize.Fixed(imageWidth),
            ) { index ->
                Box(
                    Modifier.size(imageWidth)
                        .clip(RoundedCornerShape(PostLayout.ImageRadius.dp))
                        .clickable(role = Role.Button) { onOpen(index) }
                        .testTag("post-image-$index")
                        .appDescription(R.string.a11y_post_image_index, index + 1, images.size),
                ) {
                    Artwork(images[index], Modifier.matchParentSize())
                }
            }
            if (images.size > 1) {
                Box(
                    Modifier.align(Alignment.TopEnd)
                        .padding(end = 48.dp, top = PostLayout.CounterInset.dp)
                        .defaultMinSize(
                            minWidth = PostLayout.CounterWidth.dp,
                            minHeight = PostLayout.CounterHeight.dp,
                        )
                        .background(androidx.compose.ui.graphics.Color(0xFF0F0F0F), RoundedCornerShape(12.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                        .testTag("post-image-counter")
                        .appDescription(R.string.a11y_image_index, pagerState.currentPage + 1, images.size),
                    contentAlignment = Alignment.Center,
                ) {
                    AppText(
                        "${pagerState.currentPage + 1}/${images.size}",
                        size = 14, line = 20, color = Color.White,
                    )
                }
            }
        }
    }
}
