package com.hhst.youtubelite.data.media

import com.liskovsoft.youtubeapi.videoinfo.V2.SearchHistory
import com.liskovsoft.youtubeapi.videoinfo.V2.SearchHistoryStore
import com.liskovsoft.youtubeapi.videoinfo.V2.loadSearchSuggestions
import com.tencent.mmkv.MMKV

/** Local search history, keyed per account. */
class MmkvSearchHistoryStore(private val kv: MMKV) : SearchHistoryStore {
    private fun key(account: String) = "search_history:$account"
    @Synchronized override fun read(account: String): List<String> =
        kv.decodeString(key(account), null)?.split('\n')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
    @Synchronized override fun write(account: String, entries: List<String>) {
        if (entries.isEmpty()) kv.removeValueForKey(key(account))
        else kv.encode(key(account), entries.joinToString("\n"))
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

/**
 * Live search-panel rows: local per-account history plus best-effort server suggestions.
 * Suggestion failures degrade to an empty remote list — the panel never errors over them.
 */
class MediaSearchSuggestions(private val client: MediaClient, kv: MMKV) {
    private val history = SearchHistory(MmkvSearchHistoryStore(kv))

    suspend fun rows(query: String): SearchRowSet {
        val local = history.read(MediaSession.snapshot())
        val trimmed = query.trim()
        val remote = if (trimmed.isEmpty()) emptyList() else try {
            client.read { context, http -> loadSearchSuggestions(trimmed, context, http).value.orEmpty() }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) { emptyList() }
        return mergeSearchRows(local, remote, trimmed)
    }

    fun record(query: String) {
        if (query.isBlank()) return
        history.record(query, MediaSession.snapshot())
    }

    fun remove(entry: String) {
        history.remove(entry, MediaSession.snapshot())
    }
}
