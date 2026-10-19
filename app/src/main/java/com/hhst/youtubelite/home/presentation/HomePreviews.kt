package com.hhst.youtubelite.home.presentation

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.hhst.youtubelite.home.data.HomeScenario
import com.hhst.youtubelite.home.domain.*
import com.hhst.youtubelite.home.data.FixtureHomeRepository

@Preview(name = "First visit · 390", widthDp = 390, heightDp = 844)
@Composable private fun FirstVisitPreview() { HomeScreen(HomeUiState(), {}) }

@Preview(name = "Feed · 390", widthDp = 390, heightDp = 844)
@Preview(name = "Feed · 840", widthDp = 840, heightDp = 1000)
@Preview(name = "Feed · 1280", widthDp = 1280, heightDp = 800)
@Composable private fun FeedPreview() { HomeScreen(HomeUiState(content = HomeScenario.Feed.initialContent(), signedInPreview = true), {}) }

@Preview(name = "Skeleton · 390", widthDp = 390, heightDp = 844)
@Preview(name = "Skeleton · 1280", widthDp = 1280, heightDp = 800)
@Composable private fun LoadingPreview() { HomeScreen(HomeUiState(content = HomeScenario.Loading.initialContent()), {}) }

@Preview(name = "Search · 390", widthDp = 390, heightDp = 844)
@Preview(name = "Search · 1280", widthDp = 1280, heightDp = 800)
@Composable private fun SearchPreview() { HomeScreen(HomeUiState(overlay = HomeOverlay.Search, query = "nature"), {}) }

@Preview(name = "Search empty", widthDp = 390, heightDp = 844)
@Composable private fun EmptySearchPreview() { HomeScreen(HomeUiState(overlay = HomeOverlay.Search), {}) }

@Preview(name = "Video menu · 390", widthDp = 390, heightDp = 844)
@Preview(name = "Video menu · 1280", widthDp = 1280, heightDp = 800)
@Preview(name = "Video menu · Large text", widthDp = 320, heightDp = 240, fontScale = 2f)
@Composable private fun MenuPreview() { HomeScreen(HomeUiState(content = HomeScenario.Feed.initialContent(), signedInPreview = true, overlay = HomeOverlay.Menu("bunny")), {}) }

@Preview(name = "Page error", widthDp = 390, heightDp = 844)
@Composable private fun ErrorPreview() { HomeScreen(HomeUiState(content = HomeScenario.Error.initialContent()), {}) }

@Preview(name = "Shorts · 390", widthDp = 390, heightDp = 844)
@Preview(name = "Shorts · 600", widthDp = 600, heightDp = 960)
@Preview(name = "Shorts · 1280", widthDp = 1280, heightDp = 800)
@Composable private fun ShortsPreview() {
    HomeScreen(HomeUiState(content = HomeContent.Feed(HomePage(FixtureHomeRepository.samplePage().items.filterIsInstance<FeedItem.Shorts>()))), {})
}

@Preview(name = "Post · 390", widthDp = 390, heightDp = 844)
@Preview(name = "Post · 1280", widthDp = 1280, heightDp = 800)
@Composable private fun PostPreview() {
    HomeScreen(HomeUiState(content = HomeContent.Feed(HomePage(FixtureHomeRepository.samplePage().items.filterIsInstance<FeedItem.Post>()))), {})
}

@Preview(name = "Short menu", widthDp = 390, heightDp = 844)
@Composable private fun ShortMenuPreview() { HomeScreen(HomeUiState(overlay = HomeOverlay.Menu("short", FeedMenuKind.Short)), {}) }

@Preview(name = "Post menu", widthDp = 390, heightDp = 844)
@Composable private fun PostMenuPreview() { HomeScreen(HomeUiState(overlay = HomeOverlay.Menu("post", FeedMenuKind.Post)), {}) }

@Preview(name = "Mix menu", widthDp = 390, heightDp = 844)
@Preview(name = "Mix menu · Large text", widthDp = 320, heightDp = 240, fontScale = 2f)
@Composable private fun MixMenuPreview() { HomeScreen(HomeUiState(overlay = HomeOverlay.Menu("mix", FeedMenuKind.Mix)), {}) }
