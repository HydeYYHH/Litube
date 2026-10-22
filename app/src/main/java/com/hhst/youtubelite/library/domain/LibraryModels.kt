package com.hhst.youtubelite.library.domain

import com.hhst.youtubelite.home.domain.FeedItem
import com.hhst.youtubelite.playlist.domain.PlaylistSummary

/** Account header shown on the library page. */
data class AccountProfile(val name: String, val handle: String, val avatar: String, val channelId: String)

data class HistoryEntry(
    val id: String,
    val videoId: String,
    val title: String,
    val author: String,
    val channelId: String,
    val artwork: String,
    val duration: String,
    val progress: Float? = null,
    val section: String,
    val views: String = "",
)

/** The signed-in library page: account header plus reusable sections. */
data class YouPage(
    val account: AccountProfile,
    val history: List<HistoryEntry>,
    val playlists: List<PlaylistSummary>,
)

data class HistoryPage(val entries: List<HistoryEntry>)

data class SignInPrompt(val title: String, val message: String, val action: String)

sealed interface LibraryContent {
    data object Loading : LibraryContent
    data class SignedOut(val prompt: SignInPrompt) : LibraryContent
    data class Ready(val page: YouPage) : LibraryContent
    data object Failed : LibraryContent
}

sealed interface HistoryContent {
    data object Loading : HistoryContent
    data class SignedOut(val prompt: SignInPrompt) : HistoryContent
    data class Ready(val page: HistoryPage) : HistoryContent
    data class Empty(val title: String = "No videos", val message: String = "Videos you watch will show up here.") : HistoryContent
    data object Failed : HistoryContent
}

sealed interface CollectionContent {
    data object Loading : CollectionContent
    data class SignedOut(val prompt: SignInPrompt) : CollectionContent
    data class Ready(val playlists: List<PlaylistSummary>) : CollectionContent
    data class Empty(val title: String = "No playlists", val message: String = "Playlists you create will show up here.") : CollectionContent
    data object Failed : CollectionContent
}

enum class LibraryRow(val label: String) {
    History("History"), Playlists("Playlists"), WatchLater("Watch later"), LikedVideos("Liked videos"),
    Downloads("Downloads");
}

sealed interface LibraryOverlay {
    data object None : LibraryOverlay
    data object Account : LibraryOverlay
    data object Search : LibraryOverlay
    data class Menu(val videoId: String) : LibraryOverlay
}

data class LibraryUiState(
    val signedIn: Boolean = false,
    val content: LibraryContent = LibraryContent.Loading,
    val overlay: LibraryOverlay = LibraryOverlay.None,
    val query: String = "",
)

data class HistoryUiState(
    val content: HistoryContent = HistoryContent.Loading,
    val overlay: LibraryOverlay = LibraryOverlay.None,
    val query: String = "",
)

data class CollectionUiState(
    val content: CollectionContent = CollectionContent.Loading,
    val overlay: LibraryOverlay = LibraryOverlay.None,
    val query: String = "",
)

enum class HistoryMenuAction(val label: String) {
    WatchLater("Save to Watch later"), SaveToPlaylist("Save to playlist"), Share("Share"),
    RemoveFromHistory("Remove from watch history");
}

sealed interface LibraryNavigationRequest {
    data class Watch(val videoId: String) : LibraryNavigationRequest
    data class Channel(val channelId: String) : LibraryNavigationRequest
    data class Playlist(val playlistId: String) : LibraryNavigationRequest
    data class Search(val query: String) : LibraryNavigationRequest
    data object OpenHistory : LibraryNavigationRequest
    data object OpenPlaylists : LibraryNavigationRequest
    data object OpenNotifications : LibraryNavigationRequest
    data object OpenDownloads : LibraryNavigationRequest
    data class ItemMenu(val videoId: String) : LibraryNavigationRequest
    data class MenuAction(val videoId: String, val action: HistoryMenuAction) : LibraryNavigationRequest
    data class Destination(val tab: com.hhst.youtubelite.ui.components.MainTab) : LibraryNavigationRequest
    data object Settings : LibraryNavigationRequest
    data object About : LibraryNavigationRequest
    data object SignIn : LibraryNavigationRequest
    data object VoiceSearch : LibraryNavigationRequest
}

sealed interface LibraryAction {
    data object Retry : LibraryAction
    data object OpenSearch : LibraryAction
    data object CloseOverlay : LibraryAction
    data class QueryChanged(val query: String) : LibraryAction
    data class SubmitSearch(val query: String) : LibraryAction
    data object SignIn : LibraryAction
    data object OpenAccountMenu : LibraryAction
    data class AccountSelected(val action: com.hhst.youtubelite.account.domain.AccountMenuAction) : LibraryAction
    data object OpenNotifications : LibraryAction
    data object OpenHistory : LibraryAction
    data object OpenPlaylists : LibraryAction
    data object OpenWatchLater : LibraryAction
    data object OpenLiked : LibraryAction
    data object OpenDownloads : LibraryAction
    data class OpenVideo(val videoId: String) : LibraryAction
    data class OpenChannel(val channelId: String) : LibraryAction
    data class OpenPlaylist(val playlistId: String) : LibraryAction
    data class OpenMenu(val videoId: String) : LibraryAction
    data class MenuSelected(val action: HistoryMenuAction) : LibraryAction
    data class Destination(val tab: com.hhst.youtubelite.ui.components.MainTab) : LibraryAction
    data object VoiceSearch : LibraryAction
}
