package com.hhst.youtubelite.post.presentation

import androidx.compose.ui.res.stringResource
import com.hhst.youtubelite.R
import com.hhst.youtubelite.ui.design.appPaneTitle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hhst.youtubelite.ui.design.AppIcon
import com.hhst.youtubelite.ui.design.AppIconButton
import com.hhst.youtubelite.ui.design.AppText
import com.hhst.youtubelite.ui.design.Artwork
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun PostImageViewer(
    images: List<String>,
    initialIndex: Int,
    onClose: () -> Unit,
    onPageSelected: (Int) -> Unit,
) {
    if (images.isEmpty()) return
    val safeIndex = initialIndex.coerceIn(0, images.lastIndex)
    val pagerState = rememberPagerState(initialPage = safeIndex) { images.size }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.distinctUntilChanged().collect(onPageSelected)
    }
    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .testTag("post-image-viewer").appPaneTitle(R.string.ui_post_image),
    ) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { index ->
            Box(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 56.dp),
                contentAlignment = Alignment.Center) {
                Artwork(images[index], Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
        }
        AppText(
            "${pagerState.currentPage + 1}/${images.size}",
            Modifier.align(Alignment.TopCenter).padding(top = 16.dp)
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(horizontal = 8.dp, vertical = 2.dp)
                .testTag("post-image-viewer-counter"),
            size = 14, line = 20, color = Color.White,
        )
        AppIconButton(
            AppIcon.Close, stringResource(R.string.ui_close_image_viewer), color = Color.White,
            diameter = 48.dp,
            modifier = Modifier.align(Alignment.TopEnd).padding(end = 4.dp, top = 4.dp)
                .testTag("post-image-viewer-close"),
            onClick = onClose,
        )
    }
}
