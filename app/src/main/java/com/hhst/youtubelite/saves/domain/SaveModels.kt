package com.hhst.youtubelite.saves.domain

/** A local personal list that can receive a fixture video through a typed request. */
data class SavePlaylist(val id: String, val title: String)

/**
 * What the save sheet hands back to its host. The host decides how the request is recorded;
 * selecting a target never mutates the local fixture or shows a saved badge.
 */
sealed interface SaveDestination {
    data object WatchLater : SaveDestination
    data class Playlist(val id: String, val title: String) : SaveDestination
}

data class NewPlaylistDraft(val title: String = "", val description: String = "")

/** Server-backed playlist entry for the live save sheet; [contains] is the server's own flag. */
data class SaveChoice(val id: String, val title: String, val contains: Boolean)

/** Collection reads and confirmed writes for the current storage scope. */
interface SaveRepository {
    suspend fun choices(videoId: String): List<SaveChoice>
    suspend fun setContaining(playlistId: String, videoId: String, contains: Boolean)
    suspend fun saveWatchLater(videoId: String)
    suspend fun create(title: String, description: String, videoId: String): SaveChoice

    /** Saves a playlist itself to the signed-in library (server "like" on the playlist). */
    suspend fun savePlaylistToLibrary(playlistId: String)
}
