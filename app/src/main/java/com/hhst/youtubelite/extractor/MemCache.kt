package com.hhst.youtubelite.extractor

import android.util.LruCache

/**
 * In-memory LRU [Cache] for player-facing Innertube results.
 *
 * Entries carry an absolute expiry; writes through the [Cache] interface use
 * the shared [CacheTtl], while [LayeredCache] promotions inherit the disk
 * entry's remaining lifetime. Without inheritance the mem copy would outlive
 * the disk entry and keep serving expired googlevideo URLs, which 403 on the
 * player.
 */
class MemCache(
    maxEntries: Int = DEFAULT_MAX,
) : Cache {

    private val meta = lru<Metadata>(maxEntries)
    private val streams = lru<Stream>(maxEntries)
    private val chapterLists = lru<ChapterList>(maxEntries)

    override fun getMetadata(videoId: String): Metadata? =
        synchronized(meta) { meta.getIfFresh(videoId) }

    override fun putMetadata(videoId: String, metadata: Metadata) {
        putMetadata(videoId, metadata, System.currentTimeMillis() + CacheTtl.METADATA_MS)
    }

    /** Promotion entry point: [expiresAt] is inherited, not restarted. */
    fun putMetadata(videoId: String, metadata: Metadata, expiresAt: Long) {
        synchronized(meta) { meta.put(videoId, Boxed(metadata, expiresAt)) }
    }

    override fun getStream(videoId: String): Stream? =
        synchronized(streams) { streams.getIfFresh(videoId) }

    override fun putStream(videoId: String, stream: Stream) {
        putStream(videoId, stream, System.currentTimeMillis() + CacheTtl.STREAM_MS)
    }

    /** Promotion entry point: [expiresAt] is inherited, not restarted. */
    fun putStream(videoId: String, stream: Stream, expiresAt: Long) {
        synchronized(streams) { streams.put(videoId, Boxed(stream, expiresAt)) }
    }

    override fun invalidateStream(videoId: String) {
        synchronized(streams) { streams.remove(videoId) }
    }

    override fun getChapters(videoId: String): ChapterList? =
        synchronized(chapterLists) { chapterLists.getIfFresh(videoId) }

    override fun putChapters(videoId: String, chapters: ChapterList) {
        putChapters(videoId, chapters, System.currentTimeMillis() + CacheTtl.CHAPTERS_MS)
    }

    /** Promotion entry point: [expiresAt] is inherited, not restarted. */
    fun putChapters(videoId: String, chapters: ChapterList, expiresAt: Long) {
        synchronized(chapterLists) { chapterLists.put(videoId, Boxed(chapters, expiresAt)) }
    }

    /** Expiry-stamped wrapper so TTL checks need no extra map. */
    private class Boxed<T>(val value: T, val expiresAt: Long)

    private fun <T> lru(max: Int) = object : LruCache<String, Boxed<T>>(max) {
        override fun sizeOf(key: String, value: Boxed<T>): Int = 1

        fun getIfFresh(key: String): T? {
            val boxed = get(key) ?: return null
            if (System.currentTimeMillis() >= boxed.expiresAt) {
                remove(key)
                return null
            }
            return boxed.value
        }
    }

    companion object {
        const val DEFAULT_MAX = 16
    }
}
