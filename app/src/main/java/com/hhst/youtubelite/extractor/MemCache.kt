package com.hhst.youtubelite.extractor

import android.util.LruCache

/**
 * In-memory LRU [Cache]: interface writes use the shared [CacheTtl];
 * [LayeredCache] promotions inherit the disk entry's remaining lifetime.
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

    fun putMetadata(videoId: String, metadata: Metadata, expiresAt: Long) {
        synchronized(meta) { meta.put(videoId, Boxed(metadata, expiresAt)) }
    }

    override fun getStream(videoId: String): Stream? =
        synchronized(streams) { streams.getIfFresh(videoId) }

    override fun putStream(videoId: String, stream: Stream) {
        putStream(videoId, stream, System.currentTimeMillis() + CacheTtl.STREAM_MS)
    }

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

    fun putChapters(videoId: String, chapters: ChapterList, expiresAt: Long) {
        synchronized(chapterLists) { chapterLists.put(videoId, Boxed(chapters, expiresAt)) }
    }

    fun clear() {
        synchronized(meta) { meta.evictAll() }
        synchronized(streams) { streams.evictAll() }
        synchronized(chapterLists) { chapterLists.evictAll() }
    }

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
