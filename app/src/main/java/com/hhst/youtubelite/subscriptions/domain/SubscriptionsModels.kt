package com.hhst.youtubelite.subscriptions.domain

import com.hhst.youtubelite.home.domain.FeedItem

data class SubscriptionsPage(
    val items: List<FeedItem.Video>,
    val channels: List<SubscribedChannel>,
    val nextKey: String? = null,
)

data class SubscribedChannel(
    val channelId: String,
    val name: String,
    val avatar: String,
    val metadata: String,
    val hasNewContent: Boolean = false,
)

sealed interface SubscriptionsContent {
    data object Loading : SubscriptionsContent
    data class SignedOut(val prompt: com.hhst.youtubelite.library.domain.SignInPrompt) : SubscriptionsContent
    data class Feed(val page: SubscriptionsPage, val append: com.hhst.youtubelite.home.domain.AppendState = com.hhst.youtubelite.home.domain.AppendState.Idle) : SubscriptionsContent
    data object Failed : SubscriptionsContent
}

sealed interface SubscriptionsTab { data object FeedTab : SubscriptionsTab; data object ChannelsTab : SubscriptionsTab }

sealed interface SubscriptionsOverlay {
    data object None : SubscriptionsOverlay
    data object Search : SubscriptionsOverlay
    data object Channels : SubscriptionsOverlay
    data class Menu(val videoId: String) : SubscriptionsOverlay
}

data class SubscriptionsUiState(
    val signedIn: Boolean = false,
    val content: SubscriptionsContent = SubscriptionsContent.Loading,
    val overlay: SubscriptionsOverlay = SubscriptionsOverlay.None,
    val query: String = "",
    val selectedChip: SubscriptionsChip = SubscriptionsChip.All,
    /** Chips the backend can serve; production hides the lanes the server rejects. */
    val chips: List<SubscriptionsChip> = SubscriptionsChip.entries,
    /** Production mode: the item menu writes through the save sheet and feedback rows hide. */
    val live: Boolean = false,
)

enum class SubscriptionsChip(val label: String) {
    All("All"), Today("Today"), ContinueWatching("Continue watching"),
    Unwatched("Unwatched"), Live("Live"), Posts("Posts");
}

sealed interface SubscriptionsNavigationRequest {
    data class Watch(val videoId: String) : SubscriptionsNavigationRequest
    data class Channel(val channelId: String) : SubscriptionsNavigationRequest
    data class Search(val query: String) : SubscriptionsNavigationRequest
    data class MenuAction(val videoId: String, val action: com.hhst.youtubelite.home.domain.FeedMenuAction) : SubscriptionsNavigationRequest
    data class Destination(val tab: com.hhst.youtubelite.ui.components.MainTab) : SubscriptionsNavigationRequest
    data object Manage : SubscriptionsNavigationRequest
    data object SignIn : SubscriptionsNavigationRequest
    data object VoiceSearch : SubscriptionsNavigationRequest
}

sealed interface SubscriptionsAction {
    data object Retry : SubscriptionsAction
    data object LoadNext : SubscriptionsAction
    data object OpenSearch : SubscriptionsAction
    data object CloseOverlay : SubscriptionsAction
    data class QueryChanged(val query: String) : SubscriptionsAction
    data class SubmitSearch(val query: String) : SubscriptionsAction
    data class SelectChip(val chip: SubscriptionsChip) : SubscriptionsAction
    data object ShowChannels : SubscriptionsAction
    data object CloseChannels : SubscriptionsAction
    data class OpenVideo(val videoId: String) : SubscriptionsAction
    data class OpenChannel(val channelId: String) : SubscriptionsAction
    data class OpenMenu(val videoId: String) : SubscriptionsAction
    data class MenuSelected(val action: com.hhst.youtubelite.home.domain.FeedMenuAction) : SubscriptionsAction
    data class Destination(val tab: com.hhst.youtubelite.ui.components.MainTab) : SubscriptionsAction
    data object SignIn : SubscriptionsAction
    data object Manage : SubscriptionsAction
    data object VoiceSearch : SubscriptionsAction
}

object SubscriptionsLayout {
    fun columns(width: Float): Int = when {
        width < 528f -> 1
        width < 994f -> 2
        width < 1320f -> 3
        width < 1646f -> 4
        width < 1972f -> 5
        else -> 6
    }
    fun outerPadding(width: Float): Float = if (width < 528f) 0f else 16f
}
