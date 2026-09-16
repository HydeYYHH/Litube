package com.hhst.youtubelite.extractor

import com.hhst.youtubelite.core.JsonCache
import java.util.concurrent.TimeUnit

/**
 * Shared entry TTLs for every [Cache] layer. Defined once so the mem layer can
 * never drift out of sync with the disk layer's expiry.
 */
internal object CacheTtl {
    val METADATA_MS: Long = TimeUnit.HOURS.toMillis(6)
    val STREAM_MS: Long = TimeUnit.MINUTES.toMillis(2)
    val CHAPTERS_MS: Long = TimeUnit.HOURS.toMillis(6)
}

/**
 * Persistent cache for [Metadata], [Stream], and [ChapterList].
 *
 * Stream URLs expire sooner (~2 min) than metadata and chapters (~6 h).
 */
interface Cache {
    fun getMetadata(videoId: String): Metadata?
    fun putMetadata(videoId: String, metadata: Metadata)

    fun getStream(videoId: String): Stream?
    fun putStream(videoId: String, stream: Stream)
    fun invalidateStream(videoId: String)

    fun getChapters(videoId: String): ChapterList?
    fun putChapters(videoId: String, chapters: ChapterList)
}

/** [Cache] backed by [JsonCache] (MMKV in production). */
class DiskCache(
    private val store: JsonCache,
) : Cache {

    override fun getMetadata(videoId: String): Metadata? =
        store.get(METADATA_KEY + videoId, Metadata::class.java)

    override fun putMetadata(videoId: String, metadata: Metadata) {
        store.put(METADATA_KEY + videoId, metadata, CacheTtl.METADATA_MS)
    }

    override fun getStream(videoId: String): Stream? =
        store.get(STREAM_KEY + videoId, Stream::class.java)

    override fun putStream(videoId: String, stream: Stream) {
        store.put(STREAM_KEY + videoId, stream, CacheTtl.STREAM_MS)
    }

    override fun invalidateStream(videoId: String) {
        store.invalidate(STREAM_KEY + videoId)
    }

    override fun getChapters(videoId: String): ChapterList? =
        store.get(CHAPTERS_KEY + videoId, ChapterList::class.java)

    override fun putChapters(videoId: String, chapters: ChapterList) {
        store.put(CHAPTERS_KEY + videoId, chapters, CacheTtl.CHAPTERS_MS)
    }

    /** [Metadata] plus its absolute expiry, for TTL-inheriting promotion into mem. */
    fun getMetadataWithExpiry(videoId: String): Pair<Metadata, Long>? =
        withExpiry(METADATA_KEY + videoId, Metadata::class.java, CacheTtl.METADATA_MS)

    /** [Stream] plus its absolute expiry, for TTL-inheriting promotion into mem. */
    fun getStreamWithExpiry(videoId: String): Pair<Stream, Long>? =
        withExpiry(STREAM_KEY + videoId, Stream::class.java, CacheTtl.STREAM_MS)

    /** [ChapterList] plus its absolute expiry, for TTL-inheriting promotion into mem. */
    fun getChaptersWithExpiry(videoId: String): Pair<ChapterList, Long>? =
        withExpiry(CHAPTERS_KEY + videoId, ChapterList::class.java, CacheTtl.CHAPTERS_MS)

    private fun <T> withExpiry(key: String, type: Class<T>, ttlMs: Long): Pair<T, Long>? {
        val (value, entryUntil) = store.getWithExpiry(key, type) ?: return null
        val until = entryUntil
            // Store cannot report an expiry: assume the full write TTL from now.
            ?: return value to (System.currentTimeMillis() + ttlMs)
        if (until <= System.currentTimeMillis()) return null
        return value to until
    }

    private companion object {
        const val METADATA_KEY = "extractor:metadata:"
        const val STREAM_KEY = "extractor:stream:"
        // Legacy storage prefix (the type was once called Segment): changing
        // the value would silently drop every on-disk entry.
        const val CHAPTERS_KEY = "extractor:segment:"
    }
}
