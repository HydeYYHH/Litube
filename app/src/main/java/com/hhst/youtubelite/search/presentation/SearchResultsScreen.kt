package com.hhst.youtubelite.search.presentation

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hhst.youtubelite.R
import com.hhst.youtubelite.search.domain.*
import com.hhst.youtubelite.ui.design.*

@Composable
fun SearchResultsScreen(
    state: SearchUiState,
    onAction: (SearchAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AppTheme {
        BoxWithConstraints(modifier.fillMaxSize().background(Color.White).testTag("search-results")) {
            val width = maxWidth.value
            val height = maxHeight.value
            Column(Modifier.fillMaxSize().then(if (state.searchOpen) Modifier.clearAndSetSemantics {} else Modifier)) {
                SearchTopBar(state.query, onAction, onBack)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (val content = state.content) {
                        SearchContent.Loading -> SearchSkeleton(width, height)
                        SearchContent.Failed -> SearchFailed(onAction)
                        is SearchContent.Empty -> SearchNoResults(content.query)
                        is SearchContent.Results -> SearchList(content.page.results, width, height, onAction)
                    }
                }
                SearchNavigationBar(state.signedInPreview, onAction)
            }
            if (state.searchOpen) SearchPanel(
                query = state.draftQuery,
                suggestions = if (state.draftQuery.isBlank()) emptyList() else listOf(
                    state.draftQuery.trim(), "${state.draftQuery.trim()} documentary",
                    "${state.draftQuery.trim()} music", "${state.draftQuery.trim()} live",
                ).distinct(),
                onQueryChanged = { onAction(SearchAction.QueryChanged(it)) },
                onSubmit = { onAction(SearchAction.SubmitSearch(it)) },
                onClose = { onAction(SearchAction.CloseSearch) },
                onVoiceSearch = { onAction(SearchAction.VoiceSearch) },
            )
        }
    }
}

@Composable
private fun SearchTopBar(query: String, onAction: (SearchAction) -> Unit, onBack: () -> Unit) {
    val fieldHeight = maxOf(32.dp, with(LocalDensity.current) { 22.sp.toDp() })
    val barHeight = maxOf(SearchResultsLayout.TopBarHeight.dp, fieldHeight)
    Row(Modifier.fillMaxWidth().height(barHeight).background(Color.White)
        .testTag("search-topbar"), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(53.dp), contentAlignment = Alignment.CenterStart) {
            AppIconButton(AppIcon.Back, "Back", onClick = onBack)
        }
        Box(Modifier.weight(1f).fillMaxHeight()) {
            Box(Modifier.align(Alignment.CenterStart).fillMaxWidth().height(fieldHeight)
                .clip(RoundedCornerShape(32.dp)).background(DesignTokens.Surface).testTag("search-field"))
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).fillMaxHeight().testTag("search-query").clickable(role = Role.Button) { onAction(SearchAction.OpenSearch) },
                    contentAlignment = Alignment.CenterStart) {
                    AppText(query, Modifier.padding(start = 12.dp).testTag("search-query-label"),
                        size = 15, line = 22, maxLines = 1)
                }
                AppIconButton(AppIcon.Filter, "Search filters", diameter = 48.dp) { onAction(SearchAction.OpenFilters) }
            }
        }
        Spacer(Modifier.width(12.dp))
        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
            AppIconButton(AppIcon.Voice, "Search with your voice",
                modifier = Modifier.wrapContentSize(unbounded = true), diameter = 40.dp) { onAction(SearchAction.VoiceSearch) }
        }
        AppIconButton(AppIcon.More, "More options", diameter = 48.dp) { onAction(SearchAction.OpenPageMenu) }
    }
}

@Composable
private fun SearchNavigationBar(signedIn: Boolean, onAction: (SearchAction) -> Unit) {
    val destinations = if (signedIn) SearchDestination.entries else listOf(
        SearchDestination.Home, SearchDestination.Shorts, SearchDestination.You,
    )
    Column(Modifier.fillMaxWidth().testTag("search-navigation")) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(DesignTokens.NavigationDivider))
        Row(Modifier.fillMaxWidth().height(SearchResultsLayout.NavigationHeight.dp)) {
            destinations.forEach { destination ->
                Column(Modifier.weight(1f).fillMaxHeight()
                    .clickable(role = Role.Tab) { onAction(SearchAction.SelectDestination(destination)) }
                    .testTag("search-nav-${destination.name}"),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    if (signedIn && destination == SearchDestination.You) {
                        Image(painterResource(R.drawable.sample_bunny), contentDescription = null,
                            modifier = Modifier.size(24.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                    } else AppIcon(when (destination) {
                        SearchDestination.Home -> AppIcon.HomeOutline
                        SearchDestination.Shorts -> AppIcon.Shorts
                        SearchDestination.Subscriptions -> AppIcon.Subscriptions
                        SearchDestination.You -> AppIcon.You
                    })
                    AppText(destination.label, size = 11, line = 14, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun SearchList(
    results: List<SearchResult>,
    width: Float,
    height: Float,
    onAction: (SearchAction) -> Unit,
) {
    val viewport = rememberUpdatedState(width to height)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            Modifier.widthIn(max = SearchResultsLayout.ColumnMaxWidth.dp).fillMaxSize().testTag("search-results-list"),
            contentPadding = PaddingValues(
                start = 0.dp, end = 0.dp,
                top = SearchResultsLayout.listTopInset(width).dp,
                bottom = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(SearchResultsLayout.itemSpacing(width, height).dp),
        ) {
            items(results, key = { it.id }) { result ->
                val (itemWidth, itemHeight) = viewport.value
                if (SearchResultsLayout.listLockup(itemWidth, itemHeight)) {
                    ListResultRow(result, itemWidth, itemHeight, onAction)
                } else StackedResultRow(result, itemWidth, itemHeight, onAction)
            }
        }
    }
}

@Composable
private fun ListResultRow(result: SearchResult, width: Float, height: Float, onAction: (SearchAction) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = SearchResultsLayout.ListThumbnailHeight.dp)
        .testTag("search-result-${result.id}").padding(horizontal = 12.dp)) {
        ResultThumbnail(result,
            Modifier.width(SearchResultsLayout.ListThumbnailWidth.dp).height(SearchResultsLayout.ListThumbnailHeight.dp),
            radius = 12.dp, badgeInset = SearchResultsLayout.ListBadgeInset.dp, onAction)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Row(Modifier.fillMaxWidth()) {
                ResultText(result, Modifier.weight(1f), onAction)
                ResultMenu(result, SearchResultsLayout.menuSize(width, height).dp, onAction)
            }
            ChannelAvatar(result, Modifier, onAction)
        }
    }
}

@Composable
private fun StackedResultRow(result: SearchResult, width: Float, height: Float, onAction: (SearchAction) -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("search-result-${result.id}")) {
        ResultThumbnail(result,
            Modifier.fillMaxWidth().padding(horizontal = SearchResultsLayout.stackedThumbnailInset(width, height).dp)
                .height(SearchResultsLayout.stackedThumbnailHeight(width, height).dp),
            radius = SearchResultsLayout.stackedThumbnailRadius(width, height).dp,
            badgeInset = SearchResultsLayout.BadgeInset.dp, onAction)
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth().heightIn(min = SearchResultsLayout.DetailsMinHeight.dp)
            .padding(start = SearchResultsLayout.detailsStart(width).dp, end = SearchResultsLayout.detailsEnd(width, height).dp),
            verticalAlignment = Alignment.Top) {
            ChannelAvatar(result, Modifier.padding(top = 8.dp), onAction)
            Spacer(Modifier.width(12.dp))
            ResultText(result, Modifier.weight(1f).padding(top = 8.dp), onAction)
            ResultMenu(result, SearchResultsLayout.stackedMenuSize(width, height).dp, onAction)
        }
    }
}

@Composable
private fun ResultThumbnail(
    result: SearchResult,
    modifier: Modifier,
    radius: Dp,
    badgeInset: Dp,
    onAction: (SearchAction) -> Unit,
) {
    Box(modifier.testTag("search-thumb-${result.id}").clip(RoundedCornerShape(radius))
        .clickable(role = Role.Button) { onAction(SearchAction.OpenResult(result.id)) }
        .semantics { contentDescription = "${result.title}, ${result.duration}" }) {
        Artwork(result.artwork, Modifier.fillMaxSize())
        result.progress?.let { progress ->
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0xFF909090))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(DesignTokens.Red))
            }
        }
        Box(Modifier.align(Alignment.BottomEnd).padding(badgeInset).testTag("search-badge-${result.id}")
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp), contentAlignment = Alignment.Center) {
            AppText(result.duration, size = 12, line = 18, weight = FontWeight.Medium, color = Color.White)
        }
    }
}

@Composable
private fun ResultText(result: SearchResult, modifier: Modifier, onAction: (SearchAction) -> Unit) {
    Column(modifier.testTag("search-text-${result.id}").clickable(role = Role.Button) {
        onAction(SearchAction.OpenResult(result.id))
    }) {
        AppText(result.title, Modifier.fillMaxWidth().testTag("search-title-${result.id}"),
            size = 14, line = 18, weight = FontWeight.Normal, maxLines = 2, exactLineHeight = 17.5.sp)
        Spacer(Modifier.height(3.dp))
        Row(Modifier.fillMaxWidth().testTag("search-byline-${result.id}")) {
            BylineItem(result.channel)
            BylineSeparator()
            BylineItem(result.views)
            BylineSeparator()
            BylineItem(result.age)
        }
        result.badge?.let { badge ->
            Spacer(Modifier.height(4.dp))
            Box(Modifier.padding(start = 4.dp)) {
                AppText(badge, size = 12, line = 18, weight = FontWeight.Medium, color = DesignTokens.Secondary)
            }
        }
    }
}

@Composable
private fun BylineItem(text: String) {
    AppText(text, size = 12, line = 14, maxLines = 1)
    Spacer(Modifier.width(4.dp))
}

@Composable
private fun BylineSeparator() {
    AppText("•", size = 12, line = 14)
    Spacer(Modifier.width(4.dp))
}

@Composable
private fun ChannelAvatar(result: SearchResult, modifier: Modifier, onAction: (SearchAction) -> Unit) {
    Box(modifier.size(40.dp).clip(CircleShape).testTag("search-channel-${result.id}")
        .clickable(role = Role.Button) { onAction(SearchAction.OpenChannel(result.channelId)) }
        .semantics { contentDescription = "Go to channel ${result.channel}" }) {
        Artwork(result.artwork, Modifier.fillMaxSize())
    }
}

@Composable
private fun ResultMenu(result: SearchResult, size: Dp, onAction: (SearchAction) -> Unit) {
    AppIconButton(AppIcon.More, "More actions for ${result.title}", diameter = size) {
        onAction(SearchAction.OpenItemMenu(result.id))
    }
}

@Composable
private fun Artwork(asset: String, modifier: Modifier = Modifier) {
    Image(painterResource(when (asset) {
        "dream" -> R.drawable.sample_dream
        "sintel" -> R.drawable.sample_sintel
        else -> R.drawable.sample_bunny
    }), contentDescription = null, modifier = modifier, contentScale = ContentScale.Crop)
}

@Composable
private fun SearchSkeleton(width: Float, height: Float) {
    val viewport = rememberUpdatedState(width to height)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            Modifier.widthIn(max = SearchResultsLayout.ColumnMaxWidth.dp).fillMaxSize().testTag("search-skeleton"),
            contentPadding = PaddingValues(
                start = 0.dp, end = 0.dp,
                top = SearchResultsLayout.listTopInset(width).dp, bottom = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(6) { index ->
                val (width, height) = viewport.value
                val list = SearchResultsLayout.listLockup(width, height)
                val thumbnail: @Composable () -> Unit = {
                    Box(Modifier
                        .width(if (list) SearchResultsLayout.ListThumbnailWidth.dp else SearchResultsLayout.stackedThumbnailWidth(width, height).dp)
                        .height(if (list) SearchResultsLayout.ListThumbnailHeight.dp else SearchResultsLayout.stackedThumbnailHeight(width, height).dp)
                        .clip(RoundedCornerShape(if (list) 12.dp else SearchResultsLayout.stackedThumbnailRadius(width, height).dp))
                        .background(DesignTokens.Ghost).testTag("skeleton-thumb-$index"))
                }
                val text: @Composable () -> Unit = {
                    Column(Modifier.fillMaxWidth()) {
                        Box(Modifier.widthIn(max = 300.dp).fillMaxWidth().height(16.dp)
                            .background(DesignTokens.Ghost, RoundedCornerShape(2.dp)).testTag("skeleton-title-$index"))
                        Spacer(Modifier.height(8.dp))
                        Box(Modifier.width(120.dp).height(16.dp)
                            .background(DesignTokens.Ghost, RoundedCornerShape(2.dp)).testTag("skeleton-byline-$index"))
                    }
                }
                if (list) Row(Modifier.fillMaxWidth().testTag("skeleton-card-$index").padding(horizontal = 12.dp)) {
                    thumbnail()
                    Spacer(Modifier.width(12.dp))
                    Box(Modifier.weight(1f).padding(end = 12.dp)) { text() }
                } else Column(Modifier.fillMaxWidth().testTag("skeleton-card-$index")) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = SearchResultsLayout.stackedThumbnailInset(width, height).dp)) { thumbnail() }
                    Box(Modifier.fillMaxWidth().padding(start = 12.dp, top = 12.dp, end = 12.dp)) { text() }
                }
            }
        }
    }
}

@Composable
private fun SearchMessage(tag: String, content: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .heightIn(min = maxHeight).padding(24.dp).testTag(tag),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            content = content)
    }
}

@Composable
private fun SearchNoResults(query: String) {
    SearchMessage("search-empty") {
        AppText("No results found", size = 20, line = 28, weight = FontWeight.Bold)
        AppText("Your search for \"$query\" did not match any videos.", Modifier.padding(top = 8.dp),
            size = 14, line = 20, color = DesignTokens.Secondary, align = TextAlign.Center)
    }
}

@Composable
private fun SearchFailed(onAction: (SearchAction) -> Unit) {
    SearchMessage("search-error") {
        AppText("Something went wrong", size = 20, line = 28, weight = FontWeight.Bold)
        AppText("Please try again.", Modifier.padding(top = 8.dp, bottom = 20.dp), color = DesignTokens.Secondary)
        Box(Modifier.clip(CircleShape).background(DesignTokens.Surface)
            .clickable(role = Role.Button) { onAction(SearchAction.Retry) }
            .padding(horizontal = 16.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
            AppText("Retry", weight = FontWeight.Medium)
        }
    }
}
