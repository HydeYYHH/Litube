package com.hhst.youtubelite.player

import com.hhst.youtubelite.diagnostics.AppLog
import com.hhst.youtubelite.core.JsonCache
import com.hhst.youtubelite.extractor.VideoId
import java.util.concurrent.TimeUnit
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Stable queue owner: this device or one signed-in account (plus its delegated channel).
 * Derived from session identity fields only — never rotating access tokens — so a stored
 * queue survives restarts and token refreshes under the same owner.
 */
data class QueueOwner private constructor(val id: String) {
    companion object {
        /** Every signed-out state, including the whole-app anonymous switch. */
        val DEVICE = QueueOwner("device")

        fun account(accountId: String, onBehalfOfUser: String? = null): QueueOwner = QueueOwner(
            if (onBehalfOfUser.isNullOrBlank()) "a:$accountId" else "a:$accountId:c:$onBehalfOfUser",
        )

        fun from(signedIn: Boolean, accountId: String?, onBehalfOfUser: String?): QueueOwner =
            if (signedIn && !accountId.isNullOrBlank()) account(accountId, onBehalfOfUser) else DEVICE
    }
}

@kotlinx.serialization.Serializable
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
    val owner: QueueOwner = QueueOwner.DEVICE,
)

/**
 * Queue persistence + navigation. Backed by [JsonCache] with a long (but finite)
 * TTL — `Long.MAX_VALUE` would overflow `now + ttl` and expire immediately.
 *
 * Storage is partitioned by [QueueOwner]: activating an owner swaps the published
 * state without touching other owners' queues, and current media keeps playing across
 * the swap. Mutators take an optional owner token captured before async work; a stale
 * token drops the write so a late write can never land in the newly active queue.
 */
class QueueRepository(
    private val cache: JsonCache,
    private val ownerProvider: () -> QueueOwner = { QueueOwner.DEVICE },
) {
    private val lock = Any()
    private var active: QueueOwner = ownerProvider()
    private var playingVideoId: String? = null
    private val _state = MutableStateFlow(loadState(active))
    val state: StateFlow<QueueState> = _state.asStateFlow()

    fun currentOwner(): QueueOwner = synchronized(lock) { active }

    /**
     * Activates the current owner's stored queue. Same-owner activation is a
     * no-op: a token refresh is not an identity change and must not reload.
     */
    fun activate() {
        val owner = ownerProvider()
        synchronized(lock) {
            if (owner == active) return
            active = owner
            playingVideoId = cache.get(playingKey(owner), String::class.java)
            _state.value = loadState(owner)
            AppLog.event(AppLog.Category.PLAYER, "queue_owner_activated",
                mapOf("owner" to owner.id, "item_count" to _state.value.items.size))
        }
    }

    fun setEnabled(enabled: Boolean, owner: QueueOwner? = null) {
        guarded(owner) {
            _state.update { it.copy(enabled = enabled) }
            save()
        }
    }

    fun add(item: QueueItem, owner: QueueOwner? = null) {
        val clean = sanitize(item)?.let {
            it.copy(title = it.title.ifBlank { it.videoId })
        } ?: return
        guarded(owner) {
            _state.update { s ->
                if (s.items.any { it.videoId == clean.videoId }) s
                else s.copy(items = evictOverflow(s.items + clean, keepVideoId = playingVideoId))
            }
            save()
        }
    }

    /**
     * "Play next": insert after the playing entry, or move it there when already queued;
     * the playing item itself is never moved. With no playing entry the target lands at
     * the head, which is where [nextStrict] resumes an unknown current. [add] keeps
     * its append semantics.
     */
    fun addNext(item: QueueItem, owner: QueueOwner? = null) {
        val clean = sanitize(item)?.let {
            it.copy(title = it.title.ifBlank { it.videoId })
        } ?: return
        guarded(owner) {
            val playing = playingVideoId
            if (playing == clean.videoId) return@guarded
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
    }

    fun remove(videoId: String, owner: QueueOwner? = null) {
        guarded(owner) {
            _state.update { s -> s.copy(items = s.items.filterNot { it.videoId == videoId }) }
            save()
        }
    }

    fun clear(owner: QueueOwner? = null) {
        guarded(owner) {
            _state.update { it.copy(items = emptyList()) }
            save()
        }
    }

    fun move(fromIndex: Int, toIndex: Int, owner: QueueOwner? = null) {
        guarded(owner) {
            _state.update { s ->
                if (fromIndex !in s.items.indices || toIndex !in s.items.indices) return@update s
                val items = s.items.toMutableList()
                items.add(toIndex, items.removeAt(fromIndex))
                s.copy(items = items)
            }
            save()
        }
    }

    fun upsertPlaying(item: QueueItem, owner: QueueOwner? = null) {
        val clean = sanitize(item) ?: return
        guarded(owner) {
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
    }

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
     * Without wrapping: null at the tail and when the current video is not queued.
     * Navigation stops at the tail — there is no whole-queue loop mode.
     */
    fun nextStrict(currentVideoId: String?): QueueItem? {
        val items = _state.value.items
        if (items.isEmpty()) return null
        val idx = items.indexOfFirst { it.videoId == currentVideoId }
        if (idx < 0) return items.first()
        return items.getOrNull(idx + 1)
    }

    fun previous(currentVideoId: String?): QueueItem? {
        val items = _state.value.items
        val idx = items.indexOfFirst { it.videoId == currentVideoId }
        return if (idx > 0) items[idx - 1] else null
    }

    fun random(currentVideoId: String?): QueueItem? {
        val candidates = _state.value.items.filter { it.videoId != currentVideoId }
        return candidates.randomOrNull()
    }

    /** Drops writes from a stale [owner] token so a late write can never land in the newly active queue. */
    private fun <T> guarded(owner: QueueOwner?, block: () -> T): T? = synchronized(lock) {
        if (owner != null && owner != active) {
            AppLog.event(AppLog.Category.PLAYER, "queue_write_dropped",
                mapOf("requested" to owner.id, "active" to active.id))
            return null
        }
        block()
    }

    private fun loadState(owner: QueueOwner): QueueState {
        var itemsJson = cache.get(itemsKey(owner), String::class.java)
        var enabledStr = cache.get(enabledKey(owner), String::class.java)
        // One-time fallback: the pre-identity global queue belongs to this device —
        // never to account owners. Stops once the first per-owner save writes both keys.
        if (owner == QueueOwner.DEVICE) {
            if (itemsJson == null) itemsJson = cache.get(LEGACY_ITEMS, String::class.java)
            if (enabledStr == null) enabledStr = cache.get(LEGACY_ENABLED, String::class.java)
        }
        val enabled = enabledStr?.toBooleanStrictOrNull() == true
        val items = if (itemsJson == null) {
            emptyList()
        } else {
            runCatching {
                // Previous parser tolerated nulls: they decode as nulls (dropped below)
                // or coerce to defaults; any other malformed row still fails the read.
                val json = kotlinx.serialization.json.Json {
                    ignoreUnknownKeys = true; coerceInputValues = true }
                val array = json.parseToJsonElement(itemsJson)
                    as kotlinx.serialization.json.JsonArray
                array.map { if (it is kotlinx.serialization.json.JsonNull) null
                    else json.decodeFromJsonElement(QueueItem.serializer(), it) }
            }.onFailure { AppLog.event(AppLog.Category.STORAGE, "queue_cache_corrupt", failure = it) }.getOrNull().orEmpty()
                .mapNotNull { raw -> raw?.let(::sanitize) }
                .distinctBy { it.videoId }
                .map { it.copy(title = it.title.ifBlank { it.videoId }) }
                .takeLast(MAX_ITEMS)
        }
        return QueueState(enabled = enabled, items = items, owner = owner)
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
        AppLog.event(AppLog.Category.PLAYER, "queue_changed",
            mapOf("owner" to active.id, "enabled" to s.enabled, "item_count" to s.items.size))
        cache.put(itemsKey(active), kotlinx.serialization.json.Json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(QueueItem.serializer()), s.items), TTL_MS)
        cache.put(enabledKey(active), s.enabled.toString(), TTL_MS)
        playingVideoId?.let { cache.put(playingKey(active), it, TTL_MS) }
    }

    private fun itemsKey(owner: QueueOwner) = "queue:items:${owner.id}"
    private fun enabledKey(owner: QueueOwner) = "queue:enabled:${owner.id}"
    private fun playingKey(owner: QueueOwner) = "queue:playing:${owner.id}"

    companion object {
        /** Pre-identity global keys, read once as the device owner's fallback. */
        private const val LEGACY_ITEMS = "queue:items"
        private const val LEGACY_ENABLED = "queue:enabled"
        private const val MAX_ITEMS = 50
        private const val MAX_TITLE = 200
        private const val MAX_AUTHOR = 100
        private val TTL_MS = TimeUnit.DAYS.toMillis(365)
    }
}
