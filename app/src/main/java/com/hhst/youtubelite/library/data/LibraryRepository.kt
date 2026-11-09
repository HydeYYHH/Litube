package com.hhst.youtubelite.library.data

import com.hhst.youtubelite.library.domain.*

interface LibraryRepository {
    val supportsLocal: Boolean get() = false
    val hasSavedAccounts: Boolean get() = false
    suspend fun you(): YouPage
    suspend fun history(): HistoryPage
    suspend fun playlists(): PlaylistCollectionPage

    suspend fun historyNext(cursor: String): HistoryPage = HistoryPage(emptyList())

    suspend fun playlistsNext(cursor: String): PlaylistCollectionPage = PlaylistCollectionPage(emptyList())

    /** Live-only removal write; fixtures stay on typed exits. Returns the undo record when the store can restore (device lane), else null. */
    suspend fun removeFromHistory(feedbackToken: String): RemovedHistoryEntry? =
        throw UnsupportedOperationException("remove_from_history_unsupported")

    /** Reverts one [removeFromHistory]; only the device lane supports it. */
    suspend fun restoreHistoryEntry(removed: RemovedHistoryEntry): Unit =
        throw UnsupportedOperationException("history_restore_unsupported")

    /** Live-only history management; the manage sheet only exists in production. */
    suspend fun setHistoryPaused(paused: Boolean): Unit = throw UnsupportedOperationException("history_pause_unsupported")
    suspend fun clearHistory(): Unit = throw UnsupportedOperationException("history_clear_unsupported")
}
