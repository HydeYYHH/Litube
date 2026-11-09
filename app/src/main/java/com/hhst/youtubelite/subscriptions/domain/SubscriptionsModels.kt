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
    /** The server's FEsubscriptions channel filter, independent of channel-home navigation. */
    val subscriptionParams: String? = null,
)

data class ChannelDirectoryPage(val channels: List<SubscribedChannel>, val nextKey: String? = null)

sealed interface SubscriptionsContent {
    data object Loading : SubscriptionsContent
    data class SignedOut(val prompt: com.hhst.youtubelite.library.domain.SignInPrompt) : SubscriptionsContent
    /**
     * A filtered page can be empty while the server still has more; [emptyAutoLoads]
     * bounds the automatic continuations — past the bound the screen offers Load more.
     */
    data class Feed(
        val page: SubscriptionsPage,
        val append: com.hhst.youtubelite.home.domain.AppendState = com.hhst.youtubelite.home.domain.AppendState.Idle,
        val emptyAutoLoads: Int = 0,
    ) : SubscriptionsContent
    data object Failed : SubscriptionsContent
}

/** The subscribed-channel list is its own read: it loads and fails independently of the feed. */
sealed interface SubscriptionsChannels {
    data object Loading : SubscriptionsChannels
    data class Ready(
        val channels: List<SubscribedChannel>,
        /** Continuation for the directory; null when every subscribed channel is present. */
        val nextKey: String? = null,
        val append: com.hhst.youtubelite.home.domain.AppendState = com.hhst.youtubelite.home.domain.AppendState.Idle,
    ) : SubscriptionsChannels
    data object Failed : SubscriptionsChannels
}

sealed interface SubscriptionsOverlay {
    data object None : SubscriptionsOverlay
    data object Search : SubscriptionsOverlay
    data object Channels : SubscriptionsOverlay
    data class Menu(val videoId: String) : SubscriptionsOverlay
}

data class SubscriptionsUiState(
    val signedIn: Boolean = false,
    val content: SubscriptionsContent = SubscriptionsContent.Loading,
    /** Subscribed channels for the overlay and the phone avatar strip; separate from the feed read. */
    val channels: SubscriptionsChannels = SubscriptionsChannels.Loading,
    val overlay: SubscriptionsOverlay = SubscriptionsOverlay.None,
    val query: String = "",
    val selectedChip: SubscriptionsChip = SubscriptionsChip.All,
    val selectedChannel: SubscribedChannel? = null,
    /** Chips the backend can serve; production hides the lanes the server rejects. */
    val chips: List<SubscriptionsChip> = SubscriptionsChip.entries,
    /** Production mode: the item menu writes through the save sheet and feedback rows hide. */
    val live: Boolean = false,
    /** Set while the open menu's deferred get_panel read is in flight. */
    val menuLoading: Boolean = false,
    /** Set when that read failed; the sheet offers one retry row. */
    val menuFailed: Boolean = false,
    /** Set when a read came back as a `/sorry` traffic challenge; loaded rows stay visible. */
    val verification: com.hhst.youtubelite.core.VerificationRequired? = null,
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
    data class OpenVerification(val challengeUrl: String) : SubscriptionsNavigationRequest
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
    data object RetryChannels : SubscriptionsAction
    data object LoadMoreChannels : SubscriptionsAction
    data class OpenVideo(val videoId: String) : SubscriptionsAction
    data class OpenChannel(val channelId: String) : SubscriptionsAction
    /** null returns to the combined subscriptions feed. */
    data class SelectChannel(val channelId: String?) : SubscriptionsAction
    data class OpenMenu(val videoId: String) : SubscriptionsAction
    data class MenuSelected(val action: com.hhst.youtubelite.home.domain.FeedMenuAction) : SubscriptionsAction
    data class Destination(val tab: com.hhst.youtubelite.ui.components.MainTab) : SubscriptionsAction
    data object SignIn : SubscriptionsAction
    data object Manage : SubscriptionsAction
    data object VoiceSearch : SubscriptionsAction
    data object DismissVerification : SubscriptionsAction
    data class OpenVerification(val challengeUrl: String) : SubscriptionsAction
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
