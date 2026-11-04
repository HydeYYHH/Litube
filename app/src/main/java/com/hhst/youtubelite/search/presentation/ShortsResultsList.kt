package com.hhst.youtubelite.search.presentation

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hhst.youtubelite.R
import com.hhst.youtubelite.search.domain.SearchAction
import com.hhst.youtubelite.search.domain.SearchResult
import com.hhst.youtubelite.ui.design.AppText
import com.hhst.youtubelite.ui.design.DesignTokens
import com.hhst.youtubelite.dearrow.rememberDeArrowTitle
import com.hhst.youtubelite.dearrow.VideoArtwork

/** Shorts filter results; reuses the shorts shelf card geometry — no duration badge, the length shows in the title metadata. */
@Composable
fun ShortsResultsList(shorts: List<SearchResult>, state: androidx.compose.foundation.lazy.grid.LazyGridState, onAction: (SearchAction) -> Unit, footer: (@Composable () -> Unit)? = null) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            state = state,
            modifier = Modifier.widthIn(max = 840.dp).fillMaxSize().testTag("search-shorts-list"),
            contentPadding = PaddingValues(top = 12.dp, start = 12.dp, end = 12.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(shorts, key = { it.id }) { short ->
                val displayTitle = rememberDeArrowTitle(short.id, short.title)
                Column(Modifier.testTag("search-short-result-${short.id}")
                    .clickable(role = Role.Button) { onAction(SearchAction.OpenResult(short.id)) }) {
                    Box(Modifier.fillMaxWidth().aspectRatio(9f / 16f).clip(RoundedCornerShape(8.dp))
                        .semantics { contentDescription = displayTitle }) {
                        VideoArtwork(short.id, short.artwork, Modifier.fillMaxSize())
                        Box(Modifier.matchParentSize().background(
                            Brush.verticalGradient(0.7f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.6f))))
                        AppText(displayTitle, Modifier.align(Alignment.BottomStart).padding(8.dp),
                            size = 14, line = 20, color = Color.White, maxLines = 2)
                    }
                    // Only non-empty fields join: no orphan separators on missing data.
                    AppText(listOf(short.views, short.duration).filter(String::isNotBlank)
                        .joinToString(" · "), Modifier.padding(top = 6.dp),
                        size = 12, line = 18, color = DesignTokens.Secondary, maxLines = 1)
                }
            }
            if (footer != null) item(key = "pagination", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) { footer() }
        }
    }
}
