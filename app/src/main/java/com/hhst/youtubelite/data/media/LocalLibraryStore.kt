package com.hhst.youtubelite.data.media

import com.google.gson.Gson
import com.hhst.youtubelite.library.domain.*
import com.hhst.youtubelite.playlist.domain.*
import com.hhst.youtubelite.saves.domain.SaveChoice
import java.util.UUID

/** Device-owned collections use a separate namespace from account data. */
class LocalLibraryStore(private val store: AuthStore, private val gson: Gson = Gson()) {
    private data class Collection(val id: String, val title: String, val description: String, val videos: List<HistoryEntry>)
    private data class Data(val history: List<HistoryEntry> = emptyList(), val collections: List<Collection> = emptyList(), val paused: Boolean = false)
    private var loaded: Data? = null
    private var data: Data
        get() = loaded ?: read().also { loaded = it }
        set(value) { loaded = value }

    private fun read(): Data = runCatching {
        store.read(KEY)?.let { json ->
            gson.fromJson(json, Data::class.java).also { value ->
                require(value.history.size <= 500 && value.collections.size <= 100)
                value.history.forEach { require(it.videoId.isNotBlank() && it.title.isNotBlank()) }
                value.collections.forEach {
                    require(it.id.startsWith("local:") && it.title.isNotBlank() && it.videos.size <= PlaylistLimits.MAX_ENTRIES)
                }
            }
        }
    }.getOrNull() ?: Data()

    @Synchronized fun history() = HistoryPage(data.history)
    @Synchronized fun paused() = data.paused
    @Synchronized fun record(entry: HistoryEntry) {
        if (data.paused) return
        save(data.copy(history = (listOf(entry.copy(removeToken = entry.videoId)) + data.history.filter { it.videoId != entry.videoId }).take(500)))
    }
    @Synchronized fun remove(videoId: String) = save(data.copy(history = data.history.filter { it.videoId != videoId }))
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
        val old = data.collections.firstOrNull { it.id == id }
            ?: if (id == WATCH_LATER) {
                check(data.collections.size < 100) { "Too many playlists on this device" }
                Collection(id, "Watch later", "", emptyList())
            } else error("Playlist is unavailable")
        val videos = old.videos.filter { it.videoId != video.videoId }
        check(!contains || videos.size < PlaylistLimits.MAX_ENTRIES) { "Playlist is full" }
        val updated = old.copy(videos = if (contains) videos + video else videos)
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
        list.videos.firstOrNull()?.artwork.orEmpty(), "", null)
    private fun save(next: Data) {
        store.write(KEY, gson.toJson(next))
        data = next
    }
    companion object {
        const val WATCH_LATER = "local:watch-later"
        private const val KEY = "device-library:data"
    }
}
