package com.hhst.youtubelite.data.media

import com.hhst.youtubelite.library.domain.*
import com.hhst.youtubelite.playlist.domain.*
import com.hhst.youtubelite.saves.domain.SaveChoice
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

/** Device-owned collections use a separate namespace from account data. */
class LocalLibraryStore(private val store: AuthStore,
    private val writer: java.util.concurrent.Executor = java.util.concurrent.Executor { it.run() }) {
    private val json = Json { ignoreUnknownKeys = true }
    private data class Collection(val id: String, val title: String, val description: String, val videos: List<HistoryEntry>)
    private data class Data(val history: List<HistoryEntry> = emptyList(), val collections: List<Collection> = emptyList(), val paused: Boolean = false)
    // Older payloads may predate fields; parse through nullable mirrors and
    // normalize once into domain types.
    @Serializable private data class StoredEntry(
        val id: String? = null, val videoId: String? = null, val title: String? = null,
        val author: String? = null, val channelId: String? = null, val artwork: String? = null,
        val duration: String? = null, val progress: Float? = null, val section: String? = null,
        val views: String? = null, val removeToken: String? = null,
    )
    @Serializable private data class StoredCollection(
        val id: String? = null, val title: String? = null, val description: String? = null,
        val videos: List<StoredEntry>? = null,
    )
    @Serializable private data class StoredData(
        val history: List<StoredEntry>? = null,
        val collections: List<StoredCollection>? = null,
        val paused: Boolean = false,
    )
    private fun StoredEntry.toEntry() = HistoryEntry(
        id.orEmpty(), videoId.orEmpty(), title.orEmpty(), author.orEmpty(), channelId.orEmpty(),
        artwork.orEmpty(), duration.orEmpty(), progress, section.orEmpty(), views.orEmpty(), removeToken)
    private fun HistoryEntry.toStored() = StoredEntry(id, videoId, title, author, channelId, artwork, duration, progress, section, views, removeToken)
    private fun Data.toStored() = StoredData(history.map { it.toStored() },
        collections.map { StoredCollection(it.id, it.title, it.description, it.videos.map { entry -> entry.toStored() }) }, paused)
    private var loaded: Data? = null
    private var data: Data
        get() = loaded ?: read().also { loaded = it }
        set(value) { loaded = value }
    private data class PendingWrite(val data: Data, val revision: Long)
    private val pending = java.util.concurrent.atomic.AtomicReference<PendingWrite?>()
    private val writeLock = Any()
    private var revision = 0L
    private val persistedRevision = java.util.concurrent.atomic.AtomicLong()

    private fun read(): Data = runCatching {
        store.read(KEY)?.let { raw ->
            val parsed = json.decodeFromString(StoredData.serializer(), raw)
            Data(
                history = parsed.history.orEmpty().map { it.toEntry() },
                collections = parsed.collections.orEmpty().map { stored ->
                    Collection(stored.id.orEmpty(), stored.title.orEmpty(), stored.description.orEmpty(),
                        stored.videos.orEmpty().map { it.toEntry() })
                },
                paused = parsed.paused,
            ).also { value ->
                require(value.history.size <= 500 && value.collections.size <= 100)
                value.history.forEach { require(it.videoId.isNotBlank() && it.title.isNotBlank()) }
                value.collections.forEach {
                    require(it.id.startsWith("local:") && it.title.isNotBlank() && it.videos.size <= PlaylistLimits.MAX_ENTRIES)
                }
            }
        }
    }.getOrNull() ?: run {
        // Corrupt/unreadable payload: keep a bounded recovery copy so the next
        // write cannot silently erase the only remaining evidence.
        store.read(KEY)?.let { raw ->
            if (raw.length <= RECOVERY_BACKUP_CHARS) store.write(KEY_RECOVERY, raw)
            com.hhst.youtubelite.diagnostics.AppLog.event(
                com.hhst.youtubelite.diagnostics.AppLog.Category.STORAGE, "device_library_corrupt",
                mapOf("bytes" to raw.length, "backed_up" to (raw.length <= RECOVERY_BACKUP_CHARS)))
        }
        Data()
    }

    @Synchronized fun history() = HistoryPage(data.history.map { entry ->
        // Local removal is keyed by videoId; entries persisted before the removal
        // token existed still need a usable handle.
        entry.copy(removeToken = entry.removeToken ?: entry.videoId)
    })
    @Synchronized fun paused() = data.paused
    @Synchronized fun record(entry: HistoryEntry) {
        if (data.paused) return
        save(data.copy(history = (listOf(entry.copy(removeToken = entry.videoId)) + data.history.filter { it.videoId != entry.videoId }).take(500)), deferred = true)
    }
    @Synchronized fun remove(videoId: String): RemovedHistoryEntry? {
        val index = data.history.indexOfFirst { it.videoId == videoId }
        if (index < 0) return null
        val removed = RemovedHistoryEntry(
            entry = data.history[index],
            prevVideoId = data.history.getOrNull(index - 1)?.videoId,
            nextVideoId = data.history.getOrNull(index + 1)?.videoId,
            index = index,
        )
        save(data.copy(history = data.history.filterNot { it.videoId == videoId }))
        return removed
    }

    /**
     * Undoes exactly one [remove]: puts the entry back relative to its former
     * neighbours. Entries recorded/removed after the delete are left alone; if
     * the same video is present again (re-watched), the fresh record wins.
     */
    @Synchronized fun restore(removed: RemovedHistoryEntry) {
        if (data.history.any { it.videoId == removed.entry.videoId }) return
        val history = data.history.toMutableList()
        val prevIdx = removed.prevVideoId?.let { prev -> history.indexOfFirst { it.videoId == prev } } ?: -1
        val nextIdx = if (prevIdx >= 0) -1
            else removed.nextVideoId?.let { next -> history.indexOfFirst { it.videoId == next } } ?: -1
        when {
            prevIdx >= 0 -> history.add(prevIdx + 1, removed.entry)
            nextIdx >= 0 -> history.add(nextIdx, removed.entry)
            // Both neighbours are gone: the recorded index is the only position
            // evidence left, clamped to the current size.
            else -> history.add(removed.index.coerceAtMost(history.size), removed.entry)
        }
        save(data.copy(history = history.take(500)))
    }
    @Synchronized fun clear() = save(data.copy(history = emptyList()))
    @Synchronized fun pause(value: Boolean) = save(data.copy(paused = value))
    @Synchronized fun playlists() = PlaylistCollectionPage(data.collections.map(::summary))
    @Synchronized fun choices(videoId: String) = data.collections.map { SaveChoice(it.id, it.title, it.videos.any { video -> video.videoId == videoId }) }
    @Synchronized fun create(title: String, description: String, video: HistoryEntry): SaveChoice {
        require(title.isNotBlank())
        check(data.collections.size < 100) { "Too many playlists on this device" }
        val list = Collection("local:" + UUID.randomUUID(), title.trim(), description.trim(), listOf(video))
        save(data.copy(collections = data.collections + list))
        return SaveChoice(list.id, list.title, true)
    }
    @Synchronized fun setContaining(id: String, video: HistoryEntry, contains: Boolean) {
        updatePlaylist(id, video.videoId, video.takeIf { contains })
    }
    @Synchronized fun removeFromPlaylist(id: String, videoId: String) {
        updatePlaylist(id, videoId, null)
    }
    private fun updatePlaylist(id: String, videoId: String, added: HistoryEntry?) {
        val old = data.collections.firstOrNull { it.id == id }
            ?: if (id == WATCH_LATER) {
                check(data.collections.size < 100) { "Too many playlists on this device" }
                Collection(id, "Watch later", "", emptyList())
            } else error("Playlist is unavailable")
        val videos = old.videos.filter { it.videoId != videoId }
        check(added == null || videos.size < PlaylistLimits.MAX_ENTRIES) { "Playlist is full" }
        val updated = old.copy(videos = if (added != null) videos + added else videos)
        save(data.copy(collections = data.collections.filter { it.id != id } + updated))
    }
    @Synchronized fun playlist(id: String): PlaylistPage? {
        val list = data.collections.firstOrNull { it.id == id }
            ?: if (id == WATCH_LATER) Collection(id, "Watch later", "", emptyList()) else return null
        return PlaylistPage(summary(list), list.description, list.videos.map {
            PlaylistEntry(it.videoId, it.videoId, it.title, it.author, it.channelId, it.artwork, it.duration, it.views, "")
        })
    }
    private fun summary(list: Collection) = PlaylistSummary(list.id, list.title, "On this device", "", list.videos.size,
        list.videos.firstOrNull()?.artwork.orEmpty(), "", null, local = true)
    private fun save(next: Data, deferred: Boolean = false) {
        val write = PendingWrite(next, ++revision)
        if (!deferred) {
            persist(write)
            pending.set(null)
            data = next
            return
        }
        data = next
        pending.set(write)
        writer.execute {
            val snapshot = pending.getAndSet(null) ?: return@execute
            try {
                persist(snapshot)
            } catch (error: Exception) {
                // Keep the failed revision dirty for the next flush — unless a newer
                // snapshot arrived while we were writing, which wins.
                pending.updateAndGet { current ->
                    if (snapshot.revision > persistedRevision.get() &&
                        (current == null || snapshot.revision > current.revision)) snapshot else current
                }
                com.hhst.youtubelite.diagnostics.AppLog.event(
                    com.hhst.youtubelite.diagnostics.AppLog.Category.STORAGE, "device_library_write_failed", failure = error)
            }
        }
    }

    /** Test seam: rewrites the encoded payload before it reaches the store. */
    internal var writeInterceptor: ((String) -> String)? = null

    private fun persist(write: PendingWrite) {
        val encoded = this.json.encodeToString(StoredData.serializer(), write.data.toStored())
        val json = writeInterceptor?.invoke(encoded) ?: encoded
        synchronized(writeLock) {
            if (write.revision <= persistedRevision.get()) return
            store.write(KEY, json)
            persistedRevision.set(write.revision)
        }
    }
    companion object {
        const val WATCH_LATER = "local:watch-later"
        private const val KEY = "device-library:data"
        private const val KEY_RECOVERY = "device-library:recovery"
        /** Recovery copy cap: forensic value vs one prefs entry size. */
        private const val RECOVERY_BACKUP_CHARS = 256 * 1024

        /** Production writer: one daemon thread so callers never marshal the library inline. */
        fun backgroundWriter(): java.util.concurrent.Executor =
            java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "device-library-writer").apply { isDaemon = true }
            }
    }
}
