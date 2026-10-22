package com.hhst.youtubelite.saves.domain

/** A local personal list that can receive a fixture video through a typed request. */
data class SavePlaylist(val id: String, val title: String)

/**
 * What the save sheet hands back to its host. The host decides how the request is
 * recorded; selecting a target never mutates the local fixture or shows a saved badge.
 */
sealed interface SaveDestination {
    data object WatchLater : SaveDestination
    data class Playlist(val id: String, val title: String) : SaveDestination
}

data class NewPlaylistDraft(val title: String = "", val description: String = "")
