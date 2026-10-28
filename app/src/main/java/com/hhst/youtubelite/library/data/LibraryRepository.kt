package com.hhst.youtubelite.library.data

import com.hhst.youtubelite.library.domain.*

interface LibraryRepository {
    val supportsLocal: Boolean get() = false
    suspend fun you(): YouPage
    suspend fun history(): HistoryPage
    suspend fun playlists(): PlaylistCollectionPage

    /** Further history entries after [cursor]; the fixture history is finite. */
    suspend fun historyNext(cursor: String): HistoryPage = HistoryPage(emptyList())

    /** Further playlist rows after [cursor]; the fixture collection is finite. */
    suspend fun playlistsNext(cursor: String): PlaylistCollectionPage = PlaylistCollectionPage(emptyList())

    /** Live-only removal write; fixtures never reach it because the menu stays a typed exit there.
     *  Returns the undo record when the backing store can restore the entry (device lane), else null. */
    suspend fun removeFromHistory(feedbackToken: String): RemovedHistoryEntry? =
        throw UnsupportedOperationException("remove_from_history_unsupported")

    /** Reverts one [removeFromHistory] via its record. Only the device lane supports it. */
    suspend fun restoreHistoryEntry(removed: RemovedHistoryEntry): Unit =
        throw UnsupportedOperationException("history_restore_unsupported")

    /** Live-only history management writes; the manage sheet only exists in production. */
    suspend fun setHistoryPaused(paused: Boolean): Unit = throw UnsupportedOperationException("history_pause_unsupported")
    suspend fun clearHistory(): Unit = throw UnsupportedOperationException("history_clear_unsupported")
}
