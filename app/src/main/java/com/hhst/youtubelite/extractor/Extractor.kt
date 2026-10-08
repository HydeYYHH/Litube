package com.hhst.youtubelite.extractor

import kotlinx.coroutines.CoroutineScope
import org.schabi.newpipe.extractor.NewPipe
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit
import java.io.IOException
import android.webkit.WebView
import org.schabi.newpipe.extractor.services.youtube.streams.ExtractionContext
import org.schabi.newpipe.extractor.services.youtube.streams.StreamDemand
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * YouTube extraction entry point.
 *
 * [extract] returns immediately; work runs on [scope].
 */
class Extractor(
    downloader: HttpDownloader,
    private val cache: Cache,
    private val scope: CoroutineScope = Promise.DEFAULT_SCOPE,
    private val host: YoutubeExtractionHost? = null,
) {
    private val inFlight = ConcurrentHashMap<String, Inflight>()
    private val streamEpochs = StreamEpochGuard()
    private val recoveries = ConcurrentHashMap<String, Pair<Inflight, Long>>()
    private val selectionLock = Any()
    private var selectedPreparation: Inflight? = null
    fun installBrowserCapture(view: WebView, generation: () -> Long) { host?.installBrowserCapture(view, generation) }
    /** Blocking; compiles the solver and opens connections for an already configured session. */
    fun initialize(): Boolean = host?.initialize() ?: false
    fun sessionStamp(): String = host?.scopeHint().orEmpty()
    fun recoveryScope(): String = host?.recoveryScope().orEmpty()
    fun extractionDiagnostics(): String = host?.diagnostics?.export() ?: "Legacy Java extraction adapter"

    init {
        NewPipe.init(downloader)
    }

    /**
     * Starts extraction for [urlOrId]. Reuses an in-flight [Extraction] for the same id.
     *
     * @throws IllegalArgumentException if [urlOrId] is not a valid id or URL
     */
    fun extract(urlOrId: String, catalog: Boolean = false, demand: StreamDemand? = null): Extraction {
        return synchronized(selectionLock) { startExtraction(urlOrId, catalog, demand, false).extraction }
    }

    /** Only the selected card, using the exact playback demand and shared task key. */
    fun preparePlayback(urlOrId: String, demand: StreamDemand) {
        synchronized(selectionLock) {
            val slot = startExtraction(urlOrId, false, demand, true)
            if (selectedPreparation !== slot) selectedPreparation?.cancelPreparation()
            selectedPreparation = slot
        }
    }

    private fun startExtraction(urlOrId: String, catalog: Boolean,
                                demand: StreamDemand?,
                                preparation: Boolean): Inflight {
        val id = VideoId.parse(urlOrId)
            ?: throw IllegalArgumentException("Invalid YouTube url or id: $urlOrId")
        val (slot, created) = streamEpochs.withLock(id) {
            val key = id + ":" + host?.scopeHint().orEmpty() + ":" + catalog + ":" + demand?.cacheKey().orEmpty()
            val existing = inFlight[key] ?: selectedPreparation?.takeIf {
                it.matches(id, catalog, demand?.cacheKey().orEmpty())
            }
            if (existing != null && existing.active.get()) {
                if (!preparation) existing.claim()
                existing to false
            } else {
                val createdSlot = Inflight.create(id, cache, scope, streamEpochs, host,
                    catalog = catalog, demand = demand, preparation = preparation)
                inFlight[key] = createdSlot
                createdSlot to true
            }
        }
        if (!created) return slot

        val remaining = AtomicInteger(if (host == null) 3 else 2)
        val onSettled = {
            if (remaining.decrementAndGet() == 0) {
                inFlight.entries.removeIf { it.value === slot }
            }
        }
        slot.extraction.metadata.whenDone(onSettled)
        slot.extraction.stream.whenDone(onSettled)
        if (host == null) slot.extraction.chapters.whenDone(onSettled)
        slot.start()
        return slot
    }

    /**
     * Suspend until metadata and stream are ready. Cancelling the caller only
     * cancels this wait — the shared parse and `/player` request keep running
     * for other waiters (playback).
     */
    suspend fun awaitMedia(urlOrId: String): Pair<Metadata, Stream> {
        val extraction = extract(urlOrId, catalog = true)
        val metadata = extraction.metadata.await()
        val stream = extraction.stream.await()
        return metadata to stream
    }

    /**
     * One coordinated fresh extraction per video/session; playback and download join it.
     * Captures configuration again and clears the complete token/minter context.
     */
    fun extractFresh(urlOrId: String, playbackPriority: Boolean = true): Extraction {
        val id = VideoId.parse(urlOrId)
            ?: throw IllegalArgumentException("Invalid YouTube url or id: $urlOrId")
        val recoveryKey = id + ":" + recoveryScope()
        return synchronized(recoveries) {
        recoveries.entries.removeIf { it.value.first.extraction.stream.done && System.currentTimeMillis() - it.value.second >= 10_000 }
        recoveries[recoveryKey]?.let { return@synchronized it.first.extraction }
        if (recoveries.size >= 128) throw IOException("RECOVERY_CAPACITY")
        invalidateStream(id)
        val slot = Inflight.create(
            id,
            StreamWriteDisabledCache(cache),
            scope,
            streamEpochs,
            host,
            true,
            playbackPriority = playbackPriority,
        )
        recoveries[recoveryKey] = slot to System.currentTimeMillis()
        slot.extraction.stream.whenDone { synchronized(recoveries) { recoveries[recoveryKey] = slot to System.currentTimeMillis() } }
        slot.start()
        slot.extraction
        }
    }

    /** Like [awaitMedia] but on [extractFresh]. Cancel cancels only this wait. */
    suspend fun awaitFreshMedia(urlOrId: String): Pair<Metadata, Stream> {
        val extraction = extractFresh(urlOrId, playbackPriority = false)
        val metadata = extraction.metadata.await()
        val stream = extraction.stream.await()
        return metadata to stream
    }

    /** Invalidates cached stream data so the next [extract] refetches `/player`. */
    fun invalidateStream(videoId: String) {
        val id = VideoId.parse(videoId) ?: return
        streamEpochs.withLock(id) {
            streamEpochs.bumpUnlocked(id)
            cache.invalidateStream(id)
            host?.invalidateStream(id)
            inFlight.entries.removeIf { it.value.id == id && it.value.epoch < streamEpochs.epochFor(id) }
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
    private val eagerChapters: Boolean,
    val active: AtomicBoolean,
    private var preparation: Boolean,
    private val catalog: Boolean,
    private val demandKey: String,
    private val sessionCurrent: () -> Boolean,
) {
    fun claim() { preparation = false }
    fun cancelPreparation() { if (preparation) active.set(false) }
    fun matches(id: String, catalog: Boolean, demandKey: String): Boolean =
        this.id == id && this.catalog == catalog && this.demandKey == demandKey &&
            active.get() && !extraction.stream.done && sessionCurrent()
    fun start() {
        extraction.metadata.start()
        extraction.stream.start()
        if (eagerChapters) extraction.chapters.start()
    }

    companion object {
        fun create(
            id: String,
            cache: Cache,
            scope: CoroutineScope,
            streamEpochs: StreamEpochGuard,
            host: YoutubeExtractionHost? = null,
            fresh: Boolean = false,
            catalog: Boolean = false,
            demand: StreamDemand? = null,
            playbackPriority: Boolean = !catalog,
            preparation: Boolean = false,
        ): Inflight {
            val epoch = streamEpochs.epochFor(id)
            val active = AtomicBoolean(true)
            val initialScope = host?.recoveryScope()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
            val contextRef = AtomicReference<ExtractionContext>()
            fun createContext(forceFresh: Boolean) = requireNotNull(host).context(id, forceFresh, catalog || forceFresh,
                demand, { active.get() && streamEpochs.epochFor(id) == epoch }, deadline, playbackPriority).also(contextRef::set)
            val context = lazy { contextRef.get() ?: createContext(fresh) }
            fun activeContext() = contextRef.get() ?: context.value
            val contextFactory: (() -> ExtractionContext)? =
                if (host != null) ({ activeContext() }) else null
            val scopedCache = if (host != null) host.cache(cache) { activeContext() } else cache
            val resolvedCache = if (fresh) StreamWriteDisabledCache(scopedCache) else scopedCache
            val player = PlayerPage.shared(id, scope, contextFactory, host?.plans,
                if (host != null && !fresh) ({ createContext(true) }) else null)
            val streamCache = EpochGuardedCache(resolvedCache, id, epoch, streamEpochs)
            val extraction = Extraction(
                metadata = Promise(Metadata(), scope, autostart = false) {
                    it.resolveMetadata(id, resolvedCache, player)
                },
                stream = Promise(Stream(), scope, autostart = false) {
                    it.resolveStream(id, streamCache, player)
                },
                chapters = Promise(ChapterList(), scope, autostart = false) {
                    it.resolveChapters(id, resolvedCache, player)
                },
            )
            return Inflight(id, epoch, extraction, host == null, active, preparation, catalog,
                demand?.cacheKey().orEmpty(), {
                    contextRef.get()?.let { runCatching { it.check() }.isSuccess }
                        ?: (host?.recoveryScope() == initialScope)
                })
        }
    }
}

/**
 * Download 403 refresh must not persist googlevideo URLs into the playback
 * stream cache.
 */
private class StreamWriteDisabledCache(
    private val inner: Cache,
) : Cache by inner {
    override fun getStream(videoId: String): Stream? = null
    override fun putStream(videoId: String, stream: Stream) = Unit
    override fun invalidateStream(videoId: String) = Unit
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
