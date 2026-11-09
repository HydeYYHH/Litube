package com.hhst.youtubelite.data.media

import com.liskovsoft.youtubeapi.videoinfo.V2.SearchHistory
import com.liskovsoft.youtubeapi.videoinfo.V2.SearchHistoryStore
import com.liskovsoft.youtubeapi.videoinfo.V2.loadSearchSuggestions
import com.hhst.youtubelite.core.PreferenceStore
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestContext
import kotlinx.coroutines.CancellationException

class PreferenceSearchHistoryStore(private val kv: PreferenceStore) : SearchHistoryStore {
    private fun key(account: String) = "search_history:$account"
    @Synchronized override fun read(account: String): List<String> =
        kv.readString(key(account))?.split('\n')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
    @Synchronized override fun write(account: String, entries: List<String>) {
        if (entries.isEmpty()) kv.remove(key(account))
        else kv.putString(key(account), entries.joinToString("\n"))
    }
}

/** One rendered search-panel row set: locally-stored history matches first, then server suggestions. */
data class SearchRowSet(val history: List<String>, val suggestions: List<String>)

internal fun mergeSearchRows(history: List<String>, remote: List<String>, query: String, cap: Int = 10): SearchRowSet {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return SearchRowSet(history.take(cap), emptyList())
    val matching = history.filter { it.startsWith(trimmed, ignoreCase = true) }.distinct().take(3)
    val rest = remote.filter { candidate -> matching.none { it.equals(candidate, true) } }
        .distinct().take((cap - matching.size).coerceAtLeast(0))
    return SearchRowSet(matching, rest)
}

/** Best-effort server suggestions; failures degrade to an empty remote list — the panel never errors over them. */
class MediaSearchSuggestions(private val client: MediaClient, kv: PreferenceStore,
    private val privateBrowsing: () -> Boolean = { false },
    private val identity: () -> RequestContext = MediaSession::snapshot) {
    private val history = SearchHistory(PreferenceSearchHistoryStore(kv))

    /** Local rows are ready while the debounced remote request is still pending. */
    fun localRows(query: String): SearchRowSet {
        val captured = identity()
        val rows = mergeSearchRows(history.read(captured), emptyList(), query)
        return if (isCurrent(captured)) rows else SearchRowSet(emptyList(), emptyList())
    }

    suspend fun rows(query: String): SearchRowSet {
        val captured = identity()
        val local = history.read(captured)
        val trimmed = query.trim()
        val remote = if (trimmed.isEmpty()) emptyList() else try {
            client.read { context, http ->
                if (context.generation != captured.generation || context.accountId != captured.accountId ||
                    context.onBehalfOfUser != captured.onBehalfOfUser || context.signedIn != captured.signedIn)
                    throw CancellationException("Session changed")
                loadSearchSuggestions(trimmed, context, http).value.orEmpty()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) { emptyList() }
        if (!isCurrent(captured)) throw CancellationException("Session changed")
        return mergeSearchRows(local, remote, trimmed)
    }

    private fun isCurrent(captured: RequestContext): Boolean {
        val current = identity()
        return current.generation == captured.generation && current.accountId == captured.accountId &&
            current.onBehalfOfUser == captured.onBehalfOfUser && current.signedIn == captured.signedIn
    }

    fun record(query: String) {
        // Read the live switch at the write boundary; existing history remains usable.
        if (query.isBlank() || privateBrowsing()) return
        history.record(query, identity())
    }

    fun remove(entry: String) {
        history.remove(entry, identity())
    }
}
