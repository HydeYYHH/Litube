package com.hhst.youtubelite.explore.domain

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
)

/** One server shelf rendered as a carousel row. */
data class ExploreSection(val title: String?, val videos: List<FeedItem.Video>)

sealed interface ExploreContent {
    data object Loading : ExploreContent
    data class Ready(val page: ExplorePage) : ExploreContent
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
    data class OpenVerification(val challengeUrl: String) : ExploreNavigationRequest
}

sealed interface ExploreAction {
    data object Retry : ExploreAction
    data object DismissVerification : ExploreAction
    data class OpenVerification(val challengeUrl: String) : ExploreAction
    data class SelectCategory(val category: ExploreCategory) : ExploreAction
    data class OpenVideo(val videoId: String) : ExploreAction
    data class OpenChannel(val channelId: String) : ExploreAction
}
