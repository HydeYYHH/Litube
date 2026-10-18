package com.hhst.youtubelite.home.presentation

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.hhst.youtubelite.home.data.HomeScenario
import com.hhst.youtubelite.home.domain.HomeOverlay
import com.hhst.youtubelite.home.domain.HomeUiState

@Preview(name = "First visit · 390", widthDp = 390, heightDp = 844)
@Composable private fun FirstVisitPreview() { HomeScreen(HomeUiState(), {}) }

@Preview(name = "Feed · 390", widthDp = 390, heightDp = 844)
@Preview(name = "Feed · 840", widthDp = 840, heightDp = 1000)
@Preview(name = "Feed · 1280", widthDp = 1280, heightDp = 800)
@Composable private fun FeedPreview() { HomeScreen(HomeUiState(content = HomeScenario.Feed.initialContent(), signedInPreview = true), {}) }

@Preview(name = "Skeleton · 390", widthDp = 390, heightDp = 844)
@Composable private fun LoadingPreview() { HomeScreen(HomeUiState(content = HomeScenario.Loading.initialContent()), {}) }

@Preview(name = "Search", widthDp = 390, heightDp = 844)
@Composable private fun SearchPreview() { HomeScreen(HomeUiState(overlay = HomeOverlay.Search, query = "nature"), {}) }

@Preview(name = "Video menu", widthDp = 390, heightDp = 844)
@Composable private fun MenuPreview() { HomeScreen(HomeUiState(content = HomeScenario.Feed.initialContent(), overlay = HomeOverlay.Menu("bunny")), {}) }

@Preview(name = "Page error", widthDp = 390, heightDp = 844)
@Composable private fun ErrorPreview() { HomeScreen(HomeUiState(content = HomeScenario.Error.initialContent()), {}) }
