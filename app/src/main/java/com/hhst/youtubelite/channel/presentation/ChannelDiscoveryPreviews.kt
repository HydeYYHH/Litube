package com.hhst.youtubelite.channel.presentation

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.hhst.youtubelite.channel.data.FixtureChannelRepository
import com.hhst.youtubelite.channel.domain.*
import com.hhst.youtubelite.search.data.FixtureSearchRepository
import com.hhst.youtubelite.search.domain.*
import com.hhst.youtubelite.search.presentation.SearchResultsScreen

@Preview(widthDp = 390, heightDp = 844, showBackground = true)
@Composable
private fun ChannelResultsPreview() {
    SearchResultsScreen(SearchUiState("blender", SearchContent.Results(SearchPage("blender", emptyList(),
        channels = FixtureSearchRepository.sampleChannels())), signedInPreview = false,
        filters = SearchFilters(type = SearchResultType.Channels)), {}, {})
}

@Preview(widthDp = 390, heightDp = 844, showBackground = true)
@Preview(widthDp = 1280, heightDp = 800, showBackground = true)
@Composable
private fun ChannelAboutPreview() {
    ChannelScreen(ChannelUiState(content = ChannelContent.Ready(ChannelPage(FixtureChannelRepository.profile(),
        FixtureChannelRepository.sampleVideos())), overlay = ChannelOverlay.About), {}, {})
}

@Preview(widthDp = 390, heightDp = 844, showBackground = true)
@Composable
private fun ChannelSearchPreview() {
    ChannelScreen(ChannelUiState(content = ChannelContent.Ready(ChannelPage(FixtureChannelRepository.profile(),
        FixtureChannelRepository.sampleVideos())), selectedTab = ChannelTab.Search, searchQuery = "bunny", submittedQuery = "bunny"), {}, {})
}
