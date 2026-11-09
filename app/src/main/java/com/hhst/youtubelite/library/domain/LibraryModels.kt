package com.hhst.youtubelite.library.domain

import com.hhst.youtubelite.home.domain.FeedItem
import com.hhst.youtubelite.playlist.domain.PlaylistSummary

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
    /** Server token that removes this entry from watch history; null when the menu carried none. */
    val removeToken: String? = null,
)

data class YouPage(
    val account: AccountProfile,
    val history: List<HistoryEntry>,
    val playlists: List<PlaylistSummary>,
    /** Optional shelves fail independently; a failed shelf never fails the whole page. */
    val historyFailed: Boolean = false,
    val playlistsFailed: Boolean = false,
)

data class HistoryPage(val entries: List<HistoryEntry>, val nextKey: String? = null)

/**
 * Undo record for one local history removal. Position recovery uses the
 * stable video ids of the removed entry's neighbours (never a full list
 * snapshot), so restoring cannot overwrite changes the user made afterwards.
 */
data class RemovedHistoryEntry(
    val entry: HistoryEntry,
    val prevVideoId: String?,
    val nextVideoId: String?,
    val index: Int,
)

data class PlaylistCollectionPage(val playlists: List<PlaylistSummary>, val nextKey: String? = null)

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
    data class Ready(val playlists: List<PlaylistSummary>, val nextKey: String? = null) : CollectionContent
    data class Empty(val title: String = "No playlists", val message: String = "Playlists you create will show up here.") : CollectionContent
    data object Failed : CollectionContent
}

enum class LibraryRow(val label: String) {
    History("History"), Playlists("Playlists"), WatchLater("Watch later"), LikedVideos("Liked videos"),
    Downloads("Downloads"), Queue("Queue");
}

sealed interface LibraryOverlay {
    data object None : LibraryOverlay
    data object Account : LibraryOverlay
    data object Search : LibraryOverlay
    data class Menu(val videoId: String) : LibraryOverlay
    /** Live-only history management (pause/resume/clear); fixtures never open it. */
    data object ManageHistory : LibraryOverlay
}

data class LibraryUiState(
    val signedIn: Boolean = false,
    val content: LibraryContent = LibraryContent.Loading,
    val overlay: LibraryOverlay = LibraryOverlay.None,
    val query: String = "",
    /** Set when a read came back as a `/sorry` traffic challenge. */
    val verification: com.hhst.youtubelite.core.VerificationRequired? = null,
    val undo: RemovedHistoryEntry? = null,
    val historyRetrying: Boolean = false,
    val playlistsRetrying: Boolean = false,
)

data class HistoryUiState(
    val content: HistoryContent = HistoryContent.Loading,
    val append: com.hhst.youtubelite.home.domain.AppendState = com.hhst.youtubelite.home.domain.AppendState.Idle,
    val overlay: LibraryOverlay = LibraryOverlay.None,
    val query: String = "",
    /** Production mode: manage-history entry and live menu writes enabled. */
    val live: Boolean = false,
    /** Two-step confirmation for the destructive clear action. */
    val confirmClear: Boolean = false,
    /** Set when a read came back as a `/sorry` traffic challenge. */
    val verification: com.hhst.youtubelite.core.VerificationRequired? = null,
    val undo: RemovedHistoryEntry? = null,
)

data class CollectionUiState(
    val content: CollectionContent = CollectionContent.Loading,
    val append: com.hhst.youtubelite.home.domain.AppendState = com.hhst.youtubelite.home.domain.AppendState.Idle,
    val overlay: LibraryOverlay = LibraryOverlay.None,
    val query: String = "",
    /** Set when a read came back as a `/sorry` traffic challenge. */
    val verification: com.hhst.youtubelite.core.VerificationRequired? = null,
)

enum class HistoryMenuAction(val label: String) {
    WatchLater("Save to Watch later"), SaveToPlaylist("Save to playlist"), Share("Share"),
    RemoveFromHistory("Remove from watch history");
}

sealed interface LibraryNavigationRequest {
    data class Watch(val videoId: String) : LibraryNavigationRequest
    data class OpenVerification(val challengeUrl: String) : LibraryNavigationRequest
    data class Channel(val channelId: String) : LibraryNavigationRequest
    data class Playlist(val playlistId: String) : LibraryNavigationRequest
    data class Search(val query: String) : LibraryNavigationRequest
    data object OpenHistory : LibraryNavigationRequest
    data object OpenPlaylists : LibraryNavigationRequest
    data object OpenQueue : LibraryNavigationRequest
    data object OpenDownloads : LibraryNavigationRequest
    data class MenuAction(val videoId: String, val action: HistoryMenuAction) : LibraryNavigationRequest
    data class Destination(val tab: com.hhst.youtubelite.ui.components.MainTab) : LibraryNavigationRequest
    data object Settings : LibraryNavigationRequest
    data object About : LibraryNavigationRequest
    data object SignIn : LibraryNavigationRequest
    /** Account switcher and sign-out are host concerns: the session store is process-wide. */
    data object SwitchAccount : LibraryNavigationRequest
    data object SignOut : LibraryNavigationRequest
    data object VoiceSearch : LibraryNavigationRequest
}

sealed interface LibraryAction {
    data object Retry : LibraryAction
    data object RetryHistoryShelf : LibraryAction
    data object RetryPlaylistsShelf : LibraryAction
    data object DismissVerification : LibraryAction
    data class OpenVerification(val challengeUrl: String) : LibraryAction
    data object LoadHistoryNext : LibraryAction
    data object LoadCollectionNext : LibraryAction
    data object OpenSearch : LibraryAction
    data object CloseOverlay : LibraryAction
    data class QueryChanged(val query: String) : LibraryAction
    data class SubmitSearch(val query: String) : LibraryAction
    data object SignIn : LibraryAction
    data object OpenAccountMenu : LibraryAction
    data class AccountSelected(val action: com.hhst.youtubelite.account.domain.AccountMenuAction) : LibraryAction
    data object OpenHistory : LibraryAction
    data object OpenPlaylists : LibraryAction
    data object OpenWatchLater : LibraryAction
    data object OpenLiked : LibraryAction
    data object OpenQueue : LibraryAction
    data object OpenDownloads : LibraryAction
    data class OpenVideo(val videoId: String) : LibraryAction
    data class OpenChannel(val channelId: String) : LibraryAction
    data class OpenPlaylist(val playlistId: String) : LibraryAction
    data class OpenMenu(val videoId: String) : LibraryAction
    data class MenuSelected(val action: HistoryMenuAction) : LibraryAction
    data object UndoHistoryRemove : LibraryAction
    data object DismissUndo : LibraryAction
    data object OpenManageHistory : LibraryAction
    data object PauseHistory : LibraryAction
    data object ResumeHistory : LibraryAction
    /** First tap arms the confirmation; the confirm dialog's action sends ConfirmClearHistory. */
    data object RequestClearHistory : LibraryAction
    data object ConfirmClearHistory : LibraryAction
    data class Destination(val tab: com.hhst.youtubelite.ui.components.MainTab) : LibraryAction
    data object VoiceSearch : LibraryAction
}
