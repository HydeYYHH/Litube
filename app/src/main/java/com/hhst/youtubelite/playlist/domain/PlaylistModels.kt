package com.hhst.youtubelite.playlist.domain

data class PlaylistSummary(
    val id: String,
    val title: String,
    val owner: String,
    val channelId: String,
    val videoCount: Int?,
    val artwork: String,
    val views: String,
    /** Days since last update; null when unknown. Never fabricate zero. */
    val updatedDays: Int?,
    val accent: Long = 0xFF2D4F72,
    val videoCountText: String? = null,
    /** Device-stored list (Watch later on this device, local collections). */
    val local: Boolean = false,
) {
    val countLabel: String? get() = videoCountText ?: videoCount?.let { "$it videos" }
}

data class PlaylistEntry(
    val id: String,
    val videoId: String,
    val title: String,
    val channel: String,
    val channelId: String,
    val artwork: String,
    val duration: String,
    val views: String,
    val age: String,
)

data class PlaylistPage(val summary: PlaylistSummary, val description: String, val entries: List<PlaylistEntry>,
    /** Server continuation; null when the list ended or is unknown. */
    val nextKey: String? = null)

data class PlaylistAppendPage(val entries: List<PlaylistEntry>, val nextKey: String?)

object PlaylistLimits {
    /** YouTube's playlist browse cap; enumeration stops here and says so. */
    const val MAX_ENTRIES = 1000
}

/** Bounded full enumeration behind Play all/Shuffle/batch download; cancelled with its job. */
sealed interface PlaylistEnumeration {
    data object Idle : PlaylistEnumeration
    data class Running(val loaded: Int, val total: Int?) : PlaylistEnumeration
    data object Failed : PlaylistEnumeration
}

interface PlaylistRepository {
    suspend fun playlist(id: String): PlaylistPage?

    /** Further entries after [cursor]; a null cursor on the response means the list ended. */
    suspend fun playlistNext(id: String, cursor: String): PlaylistAppendPage? = null
}

sealed interface PlaylistContent {
    data object Loading : PlaylistContent
    data class Ready(val page: PlaylistPage) : PlaylistContent
    data object Unavailable : PlaylistContent
    data object Failed : PlaylistContent
}

sealed interface PlaylistOverlay {
    data object None : PlaylistOverlay
    data object Search : PlaylistOverlay
    data object Menu : PlaylistOverlay
    data class VideoMenu(val entryId: String) : PlaylistOverlay
}

data class PlaylistUiState(
    val playlistId: String,
    val content: PlaylistContent = PlaylistContent.Loading,
    /** Independent append state; a failed append keeps loaded rows. */
    val append: com.hhst.youtubelite.home.domain.AppendState = com.hhst.youtubelite.home.domain.AppendState.Idle,
    /** Full enumeration behind Play all / Shuffle / Download. */
    val enumeration: PlaylistEnumeration = PlaylistEnumeration.Idle,
    val overlay: PlaylistOverlay = PlaylistOverlay.None,
    val query: String = "",
    val descriptionExpanded: Boolean = false,
    /** Set when a read came back as a `/sorry` traffic challenge; loaded rows stay visible. */
    val verification: com.hhst.youtubelite.core.VerificationRequired? = null,
    val signedIn: Boolean = false,
) {
    val nextKey: String? get() = (content as? PlaylistContent.Ready)?.page?.nextKey
}

enum class PlaylistDestination { Home, Shorts, Subscriptions, You }

sealed interface PlaylistAction {
    data object Retry : PlaylistAction
    data object LoadNext : PlaylistAction
    data object CancelEnumeration : PlaylistAction
    data object RetryEnumeration : PlaylistAction
    data object OpenSearch : PlaylistAction
    data class QueryChanged(val query: String) : PlaylistAction
    data class Search(val query: String) : PlaylistAction
    data object OpenMenu : PlaylistAction
    data object CloseOverlay : PlaylistAction
    data object PlayAll : PlaylistAction
    data object Shuffle : PlaylistAction
    data object Save : PlaylistAction
    data object Share : PlaylistAction
    data object OpenOwner : PlaylistAction
    data object ToggleDescription : PlaylistAction
    data class OpenVideo(val entryId: String) : PlaylistAction
    data class OpenVideoMenu(val entryId: String) : PlaylistAction
    data class VideoMenuAction(val action: PlaylistVideoAction) : PlaylistAction
    data class Destination(val destination: PlaylistDestination) : PlaylistAction
    data object VoiceSearch : PlaylistAction
    data object Download : PlaylistAction
    data object DismissVerification : PlaylistAction
    data class OpenVerification(val challengeUrl: String) : PlaylistAction
}

enum class PlaylistVideoAction(val label: String) {
    WatchLater("Save to Watch later"), Save("Save to playlist"),
}

sealed interface PlaylistNavigationRequest {
    data class Watch(val videoId: String) : PlaylistNavigationRequest
    data class Channel(val channelId: String) : PlaylistNavigationRequest
    data class Search(val query: String) : PlaylistNavigationRequest
    data class Share(val playlistId: String) : PlaylistNavigationRequest
    data class Save(val playlistId: String) : PlaylistNavigationRequest
    data class Queue(val playlistId: String, val videoIds: List<String>, val shuffle: Boolean) : PlaylistNavigationRequest
    data class VideoAction(val playlistId: String, val entryId: String, val videoId: String, val action: PlaylistVideoAction) : PlaylistNavigationRequest
    data class Destination(val destination: PlaylistDestination) : PlaylistNavigationRequest
    data class OpenVerification(val challengeUrl: String) : PlaylistNavigationRequest
    data object VoiceSearch : PlaylistNavigationRequest
    data class Download(val playlistId: String) : PlaylistNavigationRequest
}

object PlaylistLayout {
    fun largeText(width: Float) = width >= 600f
    fun sideBySide(width: Float) = width >= 840f
    fun mediumText(width: Float) = width >= 1024f
    fun coverWidth(width: Float) = if (width >= 1024f) 427f else 284f
    fun coverHeight(width: Float) = if (width >= 1024f) 240f else 160f
}
