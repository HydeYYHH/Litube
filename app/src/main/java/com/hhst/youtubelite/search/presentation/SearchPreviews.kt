package com.hhst.youtubelite.search.presentation

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.hhst.youtubelite.search.data.FixtureSearchRepository
import com.hhst.youtubelite.search.domain.*

private const val QUERY = "open movie"

@Preview(name = "Filters", widthDp = 390, heightDp = 844)
@Preview(name = "Filters · Large text", widthDp = 320, heightDp = 844, fontScale = 2f)
@Composable private fun FiltersPreview() { SearchResultsScreen(results().copy(filtersOpen = true), {}, {}) }

@Preview(name = "Video actions", widthDp = 390, heightDp = 844)
@Composable private fun VideoActionsPreview() { SearchResultsScreen(results().copy(overlay = SearchOverlay.ItemMenu("harbour")), {}, {}) }

private fun results() = SearchUiState(query = QUERY, content = SearchContent.Results(SearchPage(QUERY, FixtureSearchRepository.sampleResults())))

@Preview(name = "Results · 390", widthDp = 390, heightDp = 844)
@Preview(name = "Results · 840", widthDp = 840, heightDp = 1000)
@Preview(name = "Results · 1280", widthDp = 1280, heightDp = 800)
@Composable private fun ResultsPreview() { SearchResultsScreen(results(), {}, {}) }

@Preview(name = "Results · Large text", widthDp = 320, heightDp = 844, fontScale = 2f)
@Preview(name = "Results · Landscape", widthDp = 840, heightDp = 390)
@Composable private fun ResultsLargeTextPreview() { SearchResultsScreen(results(), {}, {}) }

@Preview(name = "Loading · 390", widthDp = 390, heightDp = 844)
@Preview(name = "Loading · 1280", widthDp = 1280, heightDp = 800)
@Composable private fun LoadingPreview() { SearchResultsScreen(SearchUiState(query = QUERY, content = SearchContent.Loading), {}, {}) }

@Preview(name = "No results · 390", widthDp = 390, heightDp = 844)
@Composable private fun NoResultsPreview() { SearchResultsScreen(SearchUiState(query = QUERY, content = SearchContent.Empty(QUERY)), {}, {}) }

@Preview(name = "Error · 390", widthDp = 390, heightDp = 844)
@Composable private fun ErrorPreview() { SearchResultsScreen(SearchUiState(query = QUERY, content = SearchContent.Failed), {}, {}) }

@Preview(name = "Anonymous navigation", widthDp = 390, heightDp = 844)
@Composable private fun AnonymousNavigationPreview() {
    SearchResultsScreen(SearchUiState(query = QUERY, content = SearchContent.Loading, signedInPreview = false), {}, {})
}
