package com.hhst.youtubelite.extractor

import kotlinx.coroutines.CoroutineScope
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.services.youtube.PoTokenProvider as NpPoTokenProvider
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * YouTube extraction entry point.
 *
 * [extract] returns immediately; work runs on [scope]. Prefer a persistent [LayeredCache] in production.
 */
class Extractor(
    downloader: HttpDownloader,
    private val cache: Cache,
    poToken: NpPoTokenProvider? = null,
    private val clientOrder: ClientOrderStore? = null,
    private val scope: CoroutineScope = Promise.DEFAULT_SCOPE,
    private val playerCache: PlayerCache? = null,
) {
    private val inFlight = ConcurrentHashMap<String, Inflight>()
    private val streamEpochs = StreamEpochGuard()

    init {
        NewPipe.init(downloader)
        if (poToken != null) {
            YoutubeStreamExtractor.setPoTokenProvider(poToken)
        }
        // iOS HLS still has googlevideo URLs when WEB/Android return SABR-only
        // adaptive formats. HLS is not subject to the streaming poToken 403.
        YoutubeStreamExtractor.setFetchIosClient(true)
        clientOrder?.load()
    }

    /**
     * Starts extraction for [urlOrId]. Reuses an in-flight [Extraction] for the same id.
     *
     * @throws IllegalArgumentException if [urlOrId] is not a valid id or URL
     */
    fun extract(urlOrId: String): Extraction {
        val id = VideoId.parse(urlOrId)
            ?: throw IllegalArgumentException("Invalid YouTube url or id: $urlOrId")
        while (true) {
            val (slot, created) = streamEpochs.withLock(id) {
                val existing = inFlight[id]
                if (existing != null) {
                    existing to false
                } else {
                    val createdSlot = Inflight.create(id, cache, scope, clientOrder, streamEpochs)
                    inFlight[id] = createdSlot
                    createdSlot to true
                }
            }
            if (!created) return slot.extraction

            val remaining = AtomicInteger(3)
            val onSettled = {
                if (remaining.decrementAndGet() == 0) {
                    inFlight.remove(id, slot)
                }
            }
            slot.extraction.metadata.whenDone(onSettled)
            slot.extraction.stream.whenDone(onSettled)
            slot.extraction.chapters.whenDone(onSettled)
            slot.start()
            return slot.extraction
        }
    }

    /** Invalidates cached stream data so the next [extract] refetches `/player`. */
    fun invalidateStream(videoId: String) {
        val id = VideoId.parse(videoId) ?: return
        streamEpochs.withLock(id) {
            streamEpochs.bumpUnlocked(id)
            cache.invalidateStream(id)
            // The WebView-side raw /player response is stale too: page retries and
            // replays must not replay the evicted URLs.
            playerCache?.invalidate(id)
            val slot = inFlight[id]
            if (slot != null && slot.epoch < streamEpochs.epochFor(id)) {
                inFlight.remove(id, slot)
            }
        }
    }
}

/**
 * One in-flight [Extractor.extract] for a video id. Identity (not the video
 * id) is what [Extractor] removes from the request table, so a later extract
 * cannot be cleared by an older request's completion or invalidation.
 */
private class Inflight(
    val id: String,
    val epoch: Long,
    val extraction: Extraction,
) {
    fun start() {
        extraction.metadata.start()
        extraction.stream.start()
        extraction.chapters.start()
    }

    companion object {
        fun create(
            id: String,
            cache: Cache,
            scope: CoroutineScope,
            clientOrder: ClientOrderStore?,
            streamEpochs: StreamEpochGuard,
        ): Inflight {
            val player = PlayerPage.shared(id, scope, clientOrder)
            val epoch = streamEpochs.epochFor(id)
            val streamCache = EpochGuardedCache(cache, id, epoch, streamEpochs)
            val extraction = Extraction(
                videoId = id,
                metadata = Promise(Metadata(), scope, autostart = false) {
                    it.resolveMetadata(id, cache, player)
                },
                stream = Promise(Stream(), scope, autostart = false) {
                    it.resolveStream(id, streamCache, player)
                },
                chapters = Promise(ChapterList(), scope, autostart = false) {
                    it.resolveChapters(id, cache, player)
                },
            )
            return Inflight(id, epoch, extraction)
        }
    }
}

/**
 * [Cache] view that drops stream reads and writes whose [epoch] is no longer
 * current. Check and mutate happen under [StreamEpochGuard.withLock] so an
 * invalidation cannot land between the freshness test and the inner write.
 */
internal class EpochGuardedCache(
    private val inner: Cache,
    private val id: String,
    private val epoch: Long,
    private val epochs: StreamEpochGuard,
) : Cache by inner {
    override fun getStream(videoId: String): Stream? {
        if (videoId != id) return inner.getStream(videoId)
        return epochs.withLock(id) {
            if (epochs.epochFor(id) != epoch) null
            else inner.getStream(videoId)
        }
    }

    override fun putStream(videoId: String, stream: Stream) {
        if (videoId != id) {
            inner.putStream(videoId, stream)
            return
        }
        epochs.withLock(id) {
            if (epochs.epochFor(id) != epoch) return@withLock
            inner.putStream(videoId, stream)
        }
    }
}

/**
 * Monotonic per-video epoch counter guarding stream writes after an
 * invalidation. Epochs start at 0 and entries are only created by [bump];
 * they are never reclaimed, so a settled sibling can never make an older
 * still-in-flight extraction look fresh again (its absent-default epoch of
 * 0 would otherwise match its own). The map therefore grows by one Long
 * per invalidated video id.
 */
internal class StreamEpochGuard {
    private val epochs = ConcurrentHashMap<String, Long>()
    private val locks = ConcurrentHashMap<String, Any>()

    private fun lockFor(id: String): Any = locks.computeIfAbsent(id) { Any() }

    /** Serializes epoch checks with cache writes and [Extractor.invalidateStream]. */
    fun <T> withLock(id: String, block: () -> T): T =
        synchronized(lockFor(id)) { block() }

    /** The epoch an extraction started with; 0 before the first bump. */
    fun epochFor(id: String): Long = epochs.getOrDefault(id, 0L)

    /** Invalidates every extraction whose [epochFor] predates this call. */
    fun bump(id: String) {
        withLock(id) { bumpUnlocked(id) }
    }

    /** Caller must already hold [withLock] for [id]. */
    fun bumpUnlocked(id: String) {
        epochs.merge(id, 1L) { old, _ -> old + 1 }
    }
}
