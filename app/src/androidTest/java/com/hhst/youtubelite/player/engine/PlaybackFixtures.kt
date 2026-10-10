package com.hhst.youtubelite.player.engine

import com.hhst.youtubelite.core.JsonCache
import com.hhst.youtubelite.extractor.Cache
import com.hhst.youtubelite.extractor.ChapterList
import com.hhst.youtubelite.extractor.Metadata
import com.hhst.youtubelite.extractor.Stream
import java.util.concurrent.ConcurrentHashMap

internal fun streamCache(
    stream: Stream,
    title: String? = null,
    durationSeconds: Long = 20,
    onInvalidate: (String) -> Unit = {},
): Cache = object : Cache {
    override fun getMetadata(videoId: String) = Metadata().also { metadata ->
        metadata.id = videoId
        if (title != null) metadata.title = title
        metadata.duration = durationSeconds
    }
    @Suppress("UNUSED_PARAMETER")
    override fun putMetadata(videoId: String, metadata: Metadata) = Unit
    @Suppress("UNUSED_PARAMETER")
    override fun getStream(videoId: String) = stream
    @Suppress("UNUSED_PARAMETER")
    override fun putStream(videoId: String, stream: Stream) = Unit
    override fun invalidateStream(videoId: String) = onInvalidate(videoId)
    @Suppress("UNUSED_PARAMETER")
    override fun getChapters(videoId: String) = ChapterList()
    @Suppress("UNUSED_PARAMETER")
    override fun putChapters(videoId: String, chapters: ChapterList) = Unit
}

internal class MemoryJsonCache : JsonCache {
    val values = ConcurrentHashMap<String, Any>()
    override fun <T> get(key: String, type: Class<T>): T? = values[key]?.let(type::cast)
    @Suppress("UNUSED_PARAMETER")
    override fun put(key: String, value: Any, ttlMs: Long) { values[key] = value }
    override fun invalidate(key: String) { values.remove(key) }
}
