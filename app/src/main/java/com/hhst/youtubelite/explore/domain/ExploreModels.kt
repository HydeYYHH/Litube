package com.hhst.youtubelite.explore.domain

import com.hhst.youtubelite.home.domain.AppendState
import com.hhst.youtubelite.home.domain.FeedItem

/** The four category containers the explore entry hosts; no other categories exist. */
enum class ExploreCategory(val label: String) {
    Music("Music"), Gaming("Gaming"), News("News"), Sports("Sports");

    companion object { fun parse(value: String?) = entries.firstOrNull { it.name.equals(value, true) } ?: Music }
}

data class ExplorePage(
    val category: ExploreCategory,
    val videos: List<FeedItem.Video>,
    /** Server shelves, in order; empty for fixture feeds. */
    val sections: List<ExploreSection> = emptyList(),
    /** The page-level continuation; null ends the category. */
    val nextKey: String? = null,
    val playlists: List<com.hhst.youtubelite.playlist.domain.PlaylistSummary> = emptyList(),
)

/** One server shelf rendered as a carousel row; [nextKey] continues the shelf itself. */
data class ExploreSection(
    val title: String?,
    val videos: List<FeedItem.Video>,
    val nextKey: String? = null,
    val append: AppendState = AppendState.Idle,
    val playlists: List<com.hhst.youtubelite.playlist.domain.PlaylistSummary> = emptyList(),
    val emptyAutoLoads: Int = 0,
) {
    val automaticPagingPaused: Boolean get() = emptyAutoLoads >= 3
}

sealed interface ExploreContent {
    data object Loading : ExploreContent
    /** [append] tracks the page tail; each shelf tracks its own on [ExploreSection.append]. */
    data class Ready(val page: ExplorePage, val append: AppendState = AppendState.Idle,
        val emptyAutoLoads: Int = 0) : ExploreContent {
        val automaticPagingPaused: Boolean get() = emptyAutoLoads >= 3
    }
    data object Failed : ExploreContent
}

data class ExploreUiState(
    val category: ExploreCategory = ExploreCategory.Music,
    val content: ExploreContent = ExploreContent.Loading,
    /** Set when a read came back as a `/sorry` traffic challenge. */
    val verification: com.hhst.youtubelite.core.VerificationRequired? = null,
)

sealed interface ExploreNavigationRequest {
    data class Watch(val videoId: String) : ExploreNavigationRequest
    data class Channel(val channelId: String) : ExploreNavigationRequest
    data class Playlist(val playlistId: String) : ExploreNavigationRequest
    data class OpenVerification(val challengeUrl: String) : ExploreNavigationRequest
}

sealed interface ExploreAction {
    data object Retry : ExploreAction
    data object DismissVerification : ExploreAction
    data class OpenVerification(val challengeUrl: String) : ExploreAction
    data class SelectCategory(val category: ExploreCategory) : ExploreAction
    data class OpenVideo(val videoId: String) : ExploreAction
    data class OpenChannel(val channelId: String) : ExploreAction
    data class OpenPlaylist(val playlistId: String) : ExploreAction
    /** Appends the next page of the current category; also retries a failed tail. */
    data object LoadMore : ExploreAction
    data object AutomaticLoadMore : ExploreAction
    /** Appends the next items of the shelf at [index]; also retries a failed shelf append. */
    data class LoadShelf(val index: Int) : ExploreAction
    data class AutomaticLoadShelf(val index: Int) : ExploreAction
}
