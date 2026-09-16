package com.hhst.youtubelite.extractor

import java.util.concurrent.ConcurrentHashMap

/**
 * Reads mem first, then disk. Writes fill both so player hits stay hot in RAM.
 *
 * Disk hits are promoted into mem with the disk entry's remaining lifetime:
 * re-boxing with a fresh TTL would let the mem copy outlive the disk entry and
 * keep serving expired googlevideo URLs, which 403 on the player.
 *
 * Stream get / put / invalidate for one id share a lock so a disk→mem
 * promotion cannot resurrect an entry [invalidateStream] just cleared.
 */
class LayeredCache(
    private val mem: MemCache,
    private val disk: DiskCache,
) : Cache {

    private val streamLocks = ConcurrentHashMap<String, Any>()

    private fun streamLock(videoId: String): Any =
        streamLocks.computeIfAbsent(videoId) { Any() }

    override fun getMetadata(videoId: String): Metadata? {
        mem.getMetadata(videoId)?.let { return it }
        val (value, until) = disk.getMetadataWithExpiry(videoId) ?: return null
        mem.putMetadata(videoId, value, until)
        return value
    }

    override fun putMetadata(videoId: String, metadata: Metadata) {
        mem.putMetadata(videoId, metadata)
        disk.putMetadata(videoId, metadata)
    }

    override fun getStream(videoId: String): Stream? {
        synchronized(streamLock(videoId)) {
            mem.getStream(videoId)?.let { return it }
            val (value, until) = disk.getStreamWithExpiry(videoId) ?: return null
            mem.putStream(videoId, value, until)
            return value
        }
    }

    override fun putStream(videoId: String, stream: Stream) {
        synchronized(streamLock(videoId)) {
            mem.putStream(videoId, stream)
            disk.putStream(videoId, stream)
        }
    }

    override fun invalidateStream(videoId: String) {
        synchronized(streamLock(videoId)) {
            mem.invalidateStream(videoId)
            disk.invalidateStream(videoId)
        }
    }

    override fun getChapters(videoId: String): ChapterList? {
        mem.getChapters(videoId)?.let { return it }
        val (value, until) = disk.getChaptersWithExpiry(videoId) ?: return null
        mem.putChapters(videoId, value, until)
        return value
    }

    override fun putChapters(videoId: String, chapters: ChapterList) {
        mem.putChapters(videoId, chapters)
        disk.putChapters(videoId, chapters)
    }
}
