package com.hhst.youtubelite.shorts.domain

import com.hhst.youtubelite.watch.domain.VideoRating

data class ShortItem(
    val id: String,
    val title: String,
    val author: String,
    val channelId: String,
    val artwork: String,
    val views: String,
    val likes: String,
    val comments: String,
    val avatar: String = "",
)

data class ShortsPage(val items: List<ShortItem>, val nextKey: String? = null)

fun ShortItem.fillFrom(detail: ShortItem): ShortItem = if (id != detail.id) this else copy(
    title = title.ifBlank { detail.title },
    author = author.ifBlank { detail.author },
    channelId = channelId.ifBlank { detail.channelId },
    artwork = artwork.ifBlank { detail.artwork },
    views = views.ifBlank { detail.views },
    likes = likes.ifBlank { detail.likes },
    comments = comments.ifBlank { detail.comments },
    avatar = avatar.ifBlank { detail.avatar },
)

data class ShortDetail(
    val item: ShortItem,
    val description: String? = null,
    /** Server-side subscription state of the item's channel; null when the page didn't say. */
    val subscribed: Boolean? = null,
    /** Playback-tracking endpoints for watch-time reporting; feeds carry none. */
    val tracking: com.liskovsoft.youtubeapi.videoinfo.V2.PlayerTracking? = null,
    val sessionGeneration: Long? = null,
    /** The viewer's server rating; null when this read did not expose it. */
    val rating: VideoRating? = null,
)

sealed interface ShortsContent {
    data object Loading : ShortsContent
    data class Ready(val page: ShortsPage) : ShortsContent
    data object Failed : ShortsContent
    data object Empty : ShortsContent
}

data class ShortsUiState(
    val content: ShortsContent = ShortsContent.Loading,
    val startItemId: String? = null,
    val overlay: ShortsOverlay = ShortsOverlay.None,
    val currentItemId: String? = startItemId,
    val appendLoading: Boolean = false,
    val appendFailed: Boolean = false,
    /** Uses server-backed content and actions. */
    val live: Boolean = false,
    /** Confirmed viewer state per item/channel, applied only after the server confirms. */
    val ratings: Map<String, com.hhst.youtubelite.watch.domain.VideoRating> = emptyMap(),
    val subscriptions: Map<String, Boolean> = emptyMap(),
    /** Channels with a subscription write in flight; their buttons ignore taps. */
    val subscriptionsPending: Set<String> = emptySet(),
    val ratingsPending: Set<String> = emptySet(),
    val details: Map<String, ShortDetail> = emptyMap(),
)

sealed interface ShortsOverlay {
    data object None : ShortsOverlay
    data class Comments(val itemId: String) : ShortsOverlay
    data class Menu(val itemId: String) : ShortsOverlay
}

/** Typed exits only: account-affecting actions never mutate local state. */
sealed interface ShortsNavigationRequest {
    data class Channel(val channelId: String) : ShortsNavigationRequest
    data class Share(val itemId: String) : ShortsNavigationRequest
    data class Rate(val itemId: String, val like: Boolean) : ShortsNavigationRequest
    data class Subscribe(val channelId: String) : ShortsNavigationRequest
    data class MenuAction(val itemId: String, val action: com.hhst.youtubelite.home.domain.FeedMenuAction) : ShortsNavigationRequest
}

sealed interface ShortsAction {
    data object Retry : ShortsAction
    /** Near-end prefetch; a no-op without a continuation key. */
    data object LoadMore : ShortsAction
    data object RetryMore : ShortsAction
    data class OpenChannel(val channelId: String) : ShortsAction
    data class OpenComments(val itemId: String) : ShortsAction
    data object CloseComments : ShortsAction
    data object CloseMenu : ShortsAction
    data class OpenMenu(val itemId: String) : ShortsAction
    data class MenuSelected(val action: com.hhst.youtubelite.home.domain.FeedMenuAction) : ShortsAction
    data class OpenSaveToPlaylist(val itemId: String) : ShortsAction
    data class Share(val itemId: String) : ShortsAction
    data class Like(val itemId: String) : ShortsAction
    data class Dislike(val itemId: String) : ShortsAction
    data class SelectItem(val itemId: String) : ShortsAction
    data class Subscribe(val channelId: String) : ShortsAction
}

/**
 * Production Shorts overflow rows. These are app capabilities rendered from
 * live player state, not a recorded server menu — report/feedback stay out
 * because no Shorts path carries those write capabilities.
 */
enum class ShortsMenuAction { Description, SaveToPlaylist, Captions, AudioTrack, PlaybackSpeed, PlaybackOrder, Quality, Download, Share }
