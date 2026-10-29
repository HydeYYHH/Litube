package com.hhst.youtubelite.player

import com.google.gson.Gson
import com.hhst.youtubelite.diagnostics.AppLog
import com.google.gson.reflect.TypeToken
import com.hhst.youtubelite.core.JsonCache
import com.hhst.youtubelite.extractor.VideoId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Queue entry for up-next playback. */
data class QueueItem(
    val videoId: String = "",
    val url: String = "",
    val title: String = "",
    val author: String? = null,
    val thumbnailUrl: String? = null,
)

data class QueueState(
    val enabled: Boolean = false,
    val items: List<QueueItem> = emptyList(),
)

/**
 * Queue persistence + navigation. Backed by [JsonCache] with a long (but finite)
 * TTL — `Long.MAX_VALUE` would overflow `now + ttl` and expire immediately.
 */
class QueueRepository(
    private val cache: JsonCache,
    private val gson: Gson = Gson(),
) {
    private val _state = MutableStateFlow(load())
    val state: StateFlow<QueueState> = _state.asStateFlow()

    @Volatile
    private var playingVideoId: String? = null

    fun setEnabled(enabled: Boolean) {
        _state.update { it.copy(enabled = enabled) }
        save()
    }

    /** Appends [item]; a video already queued is left untouched. */
    fun add(item: QueueItem) {
        val clean = sanitize(item)?.let {
            it.copy(title = it.title.ifBlank { it.videoId })
        } ?: return
        _state.update { s ->
            if (s.items.any { it.videoId == clean.videoId }) s
            else s.copy(items = evictOverflow(s.items + clean, keepVideoId = playingVideoId))
        }
        save()
    }

    /**
     * "Play next": insert [item] directly after the playing entry, or move it
     * there when it is already queued. The playing item itself is never moved;
     * with no playing entry in the list the target lands at the head, which is
     * where [nextStrict] resumes an unknown current. The plain [add] path keeps
     * its append semantics.
     */
    fun addNext(item: QueueItem) {
        val clean = sanitize(item)?.let {
            it.copy(title = it.title.ifBlank { it.videoId })
        } ?: return
        val playing = playingVideoId
        if (playing == clean.videoId) return
        _state.update { s ->
            val without = s.items.filterNot { it.videoId == clean.videoId }
            val after = without.indexOfFirst { it.videoId == playing }
            val at = if (after >= 0) after + 1 else 0
            val items = without.toMutableList().apply { add(at.coerceAtMost(size), clean) }
            // Insert-after-current orders targets newest-first, so overflow must
            // drop from the tail (oldest targets) — the head-first eviction that
            // fits append order would evict the target the user just chose.
            s.copy(items = evictOverflow(items, keepVideoId = playingVideoId, dropFromHead = false))
        }
        save()
    }

    fun remove(videoId: String) {
        _state.update { s -> s.copy(items = s.items.filterNot { it.videoId == videoId }) }
        save()
    }

    fun clear() {
        _state.update { it.copy(items = emptyList()) }
        save()
    }

    fun move(fromIndex: Int, toIndex: Int) {
        _state.update { s ->
            if (fromIndex !in s.items.indices || toIndex !in s.items.indices) return@update s
            val items = s.items.toMutableList()
            items.add(toIndex, items.removeAt(fromIndex))
            s.copy(items = items)
        }
        save()
    }

    /**
     * Insert or refresh the playing item. New videos append; the list is capped
     * so a long session cannot grow without bound.
     */
    fun upsertPlaying(item: QueueItem) {
        val clean = sanitize(item) ?: return
        playingVideoId = clean.videoId
        _state.update { s ->
            val items = s.items.toMutableList()
            val existing = items.indexOfFirst { it.videoId == clean.videoId }
            if (existing >= 0) {
                val old = items[existing]
                items[existing] = old.copy(
                    url = clean.url,
                    title = clean.title.ifBlank { old.title },
                    author = clean.author ?: old.author,
                    thumbnailUrl = clean.thumbnailUrl,
                )
            } else {
                items += clean
            }
            s.copy(items = evictOverflow(items, keepVideoId = clean.videoId))
        }
        save()
    }

    /** Caps [items] at [MAX_ITEMS], dropping entries other than [keepVideoId] first. */
    private fun evictOverflow(items: List<QueueItem>, keepVideoId: String?, dropFromHead: Boolean = true): List<QueueItem> {
        if (items.size <= MAX_ITEMS) return items
        val kept = items.toMutableList()
        while (kept.size > MAX_ITEMS) {
            val drop = (if (dropFromHead) kept.indexOfFirst { it.videoId != keepVideoId }
                else kept.indexOfLast { it.videoId != keepVideoId })
            if (drop < 0) break
            kept.removeAt(drop)
        }
        return kept
    }

    /**
     * Next item WITHOUT wrapping; null at the tail and when the current video
     * is not queued. Both manual and automatic queue navigation stop at the
     * tail — there is no whole-queue loop mode.
     */
    fun nextStrict(currentVideoId: String?): QueueItem? {
        val items = _state.value.items
        if (items.isEmpty()) return null
        val idx = items.indexOfFirst { it.videoId == currentVideoId }
        if (idx < 0) return items.first()
        return items.getOrNull(idx + 1)
    }

    /** Previous item; null at head (no wrap). */
    fun previous(currentVideoId: String?): QueueItem? {
        val items = _state.value.items
        val idx = items.indexOfFirst { it.videoId == currentVideoId }
        return if (idx > 0) items[idx - 1] else null
    }

    /** Random item excluding the current one. */
    fun random(currentVideoId: String?): QueueItem? {
        val candidates = _state.value.items.filter { it.videoId != currentVideoId }
        return candidates.randomOrNull()
    }

    private fun load(): QueueState {
        val itemsJson = cache.get(KEY_ITEMS, String::class.java)
        val enabled = cache.get(KEY_ENABLED, String::class.java)?.toBooleanStrictOrNull() == true
        val items = if (itemsJson == null) {
            emptyList()
        } else {
            runCatching {
                val type = object : TypeToken<List<QueueItem?>>() {}.type
                gson.fromJson<List<QueueItem?>>(itemsJson, type).orEmpty()
            }.onFailure { AppLog.event(AppLog.Category.STORAGE, "queue_cache_corrupt", failure = it) }.getOrNull().orEmpty()
                .mapNotNull { raw -> raw?.let(::sanitize) }
                .distinctBy { it.videoId }
                .map { it.copy(title = it.title.ifBlank { it.videoId }) }
                .takeLast(MAX_ITEMS)
        }
        return QueueState(enabled = enabled, items = items)
    }

    /**
     * Page JS and persisted cache are untrusted: keep a real video id, a
     * canonical watch URL, bounded text, and an allowed thumbnail host.
     */
    private fun sanitize(item: QueueItem): QueueItem? {
        val id = VideoId.parse(item.url) ?: VideoId.parse(item.videoId)
            ?: return null
        return QueueItem(
            videoId = id,
            url = VideoId.watchUrl(id),
            title = item.title.orEmpty().trim().take(MAX_TITLE),
            author = item.author?.trim()?.take(MAX_AUTHOR)?.ifEmpty { null },
            thumbnailUrl = VideoId.thumbnailUrl(id),
        )
    }

    private fun save() {
        val s = _state.value
        AppLog.event(AppLog.Category.PLAYER, "queue_changed", mapOf("enabled" to s.enabled, "item_count" to s.items.size))
        cache.put(KEY_ITEMS, gson.toJson(s.items), TTL_MS)
        cache.put(KEY_ENABLED, s.enabled.toString(), TTL_MS)
    }

    companion object {
        private const val KEY_ITEMS = "queue:items"
        private const val KEY_ENABLED = "queue:enabled"
        private const val MAX_ITEMS = 50
        private const val MAX_TITLE = 200
        private const val MAX_AUTHOR = 100
        private val TTL_MS = TimeUnit.DAYS.toMillis(365)
    }
}
