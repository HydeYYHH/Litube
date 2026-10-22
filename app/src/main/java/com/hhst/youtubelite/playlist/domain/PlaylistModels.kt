package com.hhst.youtubelite.playlist.domain

data class PlaylistSummary(
    val id: String,
    val title: String,
    val owner: String,
    val channelId: String,
    val videoCount: Int,
    val artwork: String,
    val views: String,
    val updatedDays: Int,
    val accent: Long = 0xFF2D4F72,
)

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

data class PlaylistPage(val summary: PlaylistSummary, val description: String, val entries: List<PlaylistEntry>)

interface PlaylistRepository { suspend fun playlist(id: String): PlaylistPage? }

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
    val overlay: PlaylistOverlay = PlaylistOverlay.None,
    val query: String = "",
    val descriptionExpanded: Boolean = false,
)

enum class PlaylistDestination { Home, Shorts, You }

sealed interface PlaylistAction {
    data object Retry : PlaylistAction
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
    data object VoiceSearch : PlaylistNavigationRequest
}

object PlaylistLayout {
    fun largeText(width: Float) = width >= 600f
    fun sideBySide(width: Float) = width >= 840f
    fun mediumText(width: Float) = width >= 1024f
    fun coverWidth(width: Float) = if (width >= 1024f) 427f else 284f
    fun coverHeight(width: Float) = if (width >= 1024f) 240f else 160f
}
