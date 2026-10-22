package com.hhst.youtubelite.shorts.domain

/** One vertical short on the browse pager. */
data class ShortItem(
    val id: String,
    val title: String,
    val author: String,
    val channelId: String,
    val artwork: String,
    val views: String,
    val likes: String,
    val comments: String,
)

data class ShortsPage(val items: List<ShortItem>)

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
    data class OpenChannel(val channelId: String) : ShortsAction
    data class OpenComments(val itemId: String) : ShortsAction
    data object CloseComments : ShortsAction
    data object CloseMenu : ShortsAction
    data class OpenMenu(val itemId: String) : ShortsAction
    data class MenuSelected(val action: com.hhst.youtubelite.home.domain.FeedMenuAction) : ShortsAction
    data class Share(val itemId: String) : ShortsAction
    data class Like(val itemId: String) : ShortsAction
    data class Dislike(val itemId: String) : ShortsAction
    data class SelectItem(val itemId: String) : ShortsAction
    data class Subscribe(val channelId: String) : ShortsAction
}
