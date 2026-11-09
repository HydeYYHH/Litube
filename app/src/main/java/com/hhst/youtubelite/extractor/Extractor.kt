package com.hhst.youtubelite.extractor

import android.webkit.WebView
import com.hhst.youtubelite.diagnostics.AppLog
import com.hhst.youtubelite.diagnostics.DiagnosticContext
import com.hhst.youtubelite.diagnostics.DiagnosticOutcome
import kotlinx.coroutines.CoroutineScope
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Host surface the extractor drives; production bridges to the core playback session. */
interface PlaybackHost {
    fun context(videoId: String, fresh: Boolean, catalog: Boolean, demand: PlaybackDemand?,
                taskCurrent: () -> Boolean, deadlineNanos: Long, playbackPriority: Boolean,
                diagnosticContext: DiagnosticContext): ExtractionContext
    fun resolve(videoId: String, context: ExtractionContext, demand: PlaybackDemand?, fresh: Boolean): MscPlayback.Resolved
    fun chapters(videoId: String, context: ExtractionContext): List<Chapter>
    /** Clients to try last for this video (recent media refusals). */
    fun deferredClients(videoId: String): Set<StreamClient>
    fun cache(inner: Cache, context: () -> ExtractionContext): Cache
    fun invalidateStream(videoId: String)
    fun scopeHint(): String
    fun recoveryScope(): String
    /** Feedback is accepted only when the failed request has an exact, current client plan. */
    fun noteMediaRejected(videoId: String, url: String, reason: MediaRejection, diagnosticContext: DiagnosticContext?): StreamClient? = null
    /** Compiles the solver ahead of playback using an already captured configuration. */
    fun initialize(): Boolean = false
    /** Captures this identity's configuration and compiles the solver before playback starts. */
    fun warm(videoId: String) {}
    fun extractionDiagnostics(): String = ""
    fun installBrowserCapture(view: WebView, generation: () -> Long) {}
}

/** [extract] returns immediately; work runs on [scope]. */
class Extractor(
    private val cache: Cache,
    private val scope: CoroutineScope = Promise.DEFAULT_SCOPE,
    /** Absent for cache-only extraction (test fixtures): every promise must hit [cache]. */
    private val host: PlaybackHost? = null,
) {
    private val inFlight = ConcurrentHashMap<String, Inflight>()
    private val streamEpochs = StreamEpochGuard()
    private val recoveries = ConcurrentHashMap<String, Pair<Inflight, Long>>()
    private val selectionLock = Any()
    private var selectedPreparation: Inflight? = null

    fun installBrowserCapture(view: WebView, generation: () -> Long) = host?.installBrowserCapture(view, generation)
    /** Blocking; compiles the solver and opens connections for an already configured session. */
    fun initialize(): Boolean = host?.initialize() ?: false
    /** Speculative warm-up for the card the user is most likely to open next. */
    fun warm(urlOrId: String) {
        val id = VideoId.parse(urlOrId) ?: return
        host?.warm(id)
    }
    fun noteMediaRejected(videoId: String, url: String, reason: MediaRejection, diagnosticContext: DiagnosticContext? = null): StreamClient? =
        host?.noteMediaRejected(videoId, url, reason, diagnosticContext)
    fun extractionDiagnostics(): String = host?.extractionDiagnostics().orEmpty()
    fun recoveryScope(): String = host?.recoveryScope().orEmpty()

    fun extract(urlOrId: String, catalog: Boolean = false, demand: PlaybackDemand? = null, diagnosticContext: DiagnosticContext? = null): Extraction {
        return synchronized(selectionLock) { startExtraction(urlOrId, catalog, demand, false, diagnosticContext).extraction }
    }

    /** Only the selected card, using the exact playback demand and shared task key. */
    fun preparePlayback(urlOrId: String, demand: PlaybackDemand) {
        synchronized(selectionLock) {
            val slot = startExtraction(urlOrId, false, demand, true)
            if (selectedPreparation !== slot) selectedPreparation?.cancelPreparation()
            slot.selectPreparation()
            selectedPreparation = slot
        }
    }

    class Prefetch internal constructor(val extraction: Extraction, private val release: () -> Unit) {
        private val closed = AtomicBoolean(false)
        fun cancel() { if (closed.compareAndSet(false, true)) release() }
    }

    fun prefetch(urlOrId: String, demand: PlaybackDemand?): Prefetch = synchronized(selectionLock) {
        val slot = startExtraction(urlOrId, false, demand, true, playbackPriority = false)
        slot.retainPrefetch()
        Prefetch(slot.extraction) {
            synchronized(selectionLock) { slot.releasePrefetch() }
        }
    }

    private fun startExtraction(urlOrId: String, catalog: Boolean,
                                demand: PlaybackDemand?,
                                preparation: Boolean, diagnosticContext: DiagnosticContext? = null,
                                playbackPriority: Boolean = !catalog): Inflight {
        val id = VideoId.parse(urlOrId)
            ?: throw IllegalArgumentException("Invalid YouTube url or id: $urlOrId")
        val (slot, created) = streamEpochs.withLock(id) {
            val key = id + ":" + host?.scopeHint().orEmpty() + ":" + catalog + ":" + demand?.cacheKey().orEmpty()
            val existing = inFlight[key] ?: selectedPreparation?.takeIf {
                it.epoch == streamEpochs.epochFor(id) && it.matches(id, catalog, demand?.cacheKey().orEmpty())
            }
            if (existing != null && existing.active.get()) {
                if (!preparation) existing.claim()
                if (playbackPriority) existing.prioritize()
                existing to false
            } else {
                val createdSlot = Inflight.create(id, cache, scope, streamEpochs, host,
                    catalog = catalog, demand = demand, preparation = preparation,
                    playbackPriority = playbackPriority, diagnosticContext = diagnosticContext)
                inFlight[key] = createdSlot
                createdSlot to true
            }
        }
        if (!created) {
            slot.logJoin(diagnosticContext)
            return slot
        }

        val remaining = AtomicInteger(3)
        val onSettled = {
            if (remaining.decrementAndGet() == 0) {
                inFlight.entries.removeIf { it.value === slot }
            }
        }
        slot.extraction.metadata.whenDone(onSettled)
        slot.extraction.stream.whenDone(onSettled)
        slot.extraction.chapters.whenDone(onSettled)
        slot.start()
        return slot
    }

    /** Cancelling the caller cancels only this wait — the shared parse and `/player` request keep running for other waiters (playback). */
    suspend fun awaitMedia(urlOrId: String, diagnosticContext: DiagnosticContext? = null): Pair<Metadata, Stream> {
        val extraction = extract(urlOrId, catalog = true, diagnosticContext = diagnosticContext)
        val metadata = extraction.metadata.await()
        val stream = extraction.stream.await()
        return metadata to stream
    }

    /**
     * One coordinated fresh extraction per video/session; playback and download join it.
     * Recaptures configuration and clears the complete token/minter context.
     */
    fun extractFresh(urlOrId: String, playbackPriority: Boolean = true, diagnosticContext: DiagnosticContext? = null): Extraction {
        val id = VideoId.parse(urlOrId)
            ?: throw IllegalArgumentException("Invalid YouTube url or id: $urlOrId")
        val recoveryKey = id + ":" + host?.recoveryScope().orEmpty()
        return synchronized(recoveries) {
        recoveries.entries.removeIf { it.value.first.extraction.stream.done && System.currentTimeMillis() - it.value.second >= 10_000 }
        recoveries[recoveryKey]?.let {
            if (playbackPriority) it.first.prioritize()
            it.first.logJoin(diagnosticContext)
            return@synchronized it.first.extraction
        }
        if (recoveries.size >= 128) throw IOException("RECOVERY_CAPACITY")
        invalidateStream(id)
        val slot = Inflight.create(
            id,
            StreamWriteDisabledCache(cache),
            scope,
            streamEpochs,
            host,
            fresh = true,
            playbackPriority = playbackPriority,
            diagnosticContext = diagnosticContext,
        )
        recoveries[recoveryKey] = slot to System.currentTimeMillis()
        slot.extraction.stream.whenDone { synchronized(recoveries) {
            if (recoveries[recoveryKey]?.first === slot) recoveries[recoveryKey] = slot to System.currentTimeMillis()
        } }
        slot.start()
        slot.extraction
        }
    }

    /** Like [awaitMedia] but on [extractFresh]. Cancel cancels only this wait. */
    suspend fun awaitFreshMedia(urlOrId: String, diagnosticContext: DiagnosticContext? = null): Pair<Metadata, Stream> {
        val extraction = extractFresh(urlOrId, playbackPriority = false, diagnosticContext = diagnosticContext)
        val metadata = extraction.metadata.await()
        val stream = extraction.stream.await()
        return metadata to stream
    }

    /** Explicit user retry starts a new recovery; existing download waiters keep their task. */
    fun resetRecovery(videoId: String) {
        val id = VideoId.parse(videoId) ?: return
        synchronized(recoveries) { recoveries.entries.removeIf { it.value.first.id == id } }
    }

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
 * One in-flight [Extractor.extract] for a video id. [Extractor] removes by identity
 * (not the video id), so a later extract cannot be cleared by an older request's
 * completion or invalidation.
 */
private class Inflight(
    val id: String,
    val epoch: Long,
    val extraction: Extraction,
    val active: AtomicBoolean,
    @Volatile private var preparation: Boolean,
    private val catalog: Boolean,
    private val demandKey: String,
    private val sessionCurrent: () -> Boolean,
    private val diagnosticContext: DiagnosticContext,
    private val promote: () -> Unit,
) {
    private var prefetchUsers = 0
    private var selected = false
    fun logJoin(waiter: DiagnosticContext?) {
        if (waiter != null && waiter.traceId != diagnosticContext.traceId) AppLog.detail(AppLog.Category.EXTRACTOR,
            "shared_operation_joined", mapOf("worker_trace_id" to diagnosticContext.traceId,
                "worker_operation_id" to diagnosticContext.operationId, "preparation" to preparation), waiter)
    }
    fun claim() { preparation = false }
    fun prioritize() = promote()
    fun selectPreparation() { selected = true }
    fun retainPrefetch() { prefetchUsers++ }
    fun releasePrefetch() {
        prefetchUsers--
        cancelIfUnused()
    }
    fun cancelPreparation() {
        selected = false
        cancelIfUnused()
    }
    private fun cancelIfUnused() { if (preparation && !selected && prefetchUsers == 0) active.set(false) }
    fun matches(id: String, catalog: Boolean, demandKey: String): Boolean =
        this.id == id && this.catalog == catalog && this.demandKey == demandKey &&
            active.get() && !extraction.stream.done && sessionCurrent()
    fun start() {
        extraction.metadata.start()
        extraction.stream.start()
        // Chapters share the Watch read with playback; scheduling behind the
        // stream promise keeps speculative work off the foreground path.
        extraction.stream.whenDone { extraction.chapters.start() }
    }

    companion object {
        fun create(
            id: String,
            cache: Cache,
            scope: CoroutineScope,
            streamEpochs: StreamEpochGuard,
            host: PlaybackHost?,
            fresh: Boolean = false,
            catalog: Boolean = false,
            demand: PlaybackDemand? = null,
            playbackPriority: Boolean = !catalog,
            preparation: Boolean = false,
            diagnosticContext: DiagnosticContext? = null,
        ): Inflight {
            val epoch = streamEpochs.epochFor(id)
            val active = AtomicBoolean(true)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
            val diagnostic = (diagnosticContext ?: DiagnosticContext(videoId = id)).child().copy(videoId = id)
            val initialScope = host?.scopeHint()
            val foreground = AtomicBoolean(playbackPriority)
            val captured = AtomicReference<ExtractionContext?>()
            // A host-less extractor serves cache entries only; fail the live path fast instead of hanging the caller.
            fun requireHost() = host ?: throw IOException("EXTRACTION_HOST_REQUIRED")
            val capturedContext by lazy { runCatching {
                requireHost().context(id, fresh, catalog, demand,
                    { active.get() && streamEpochs.epochFor(id) == epoch }, deadline, foreground.get(), diagnostic).also {
                    captured.set(it)
                    if (foreground.get()) it.prioritizePlayback()
                }
            } }
            fun context() = capturedContext.getOrThrow()
            // Dependent reads share failures as well as successful resolution.
            val resolved by lazy { runCatching { requireHost().resolve(id, context(), demand, fresh) } }
            val scopedCache = host?.cache(cache) { context() } ?: cache
            val streamCache = EpochGuardedCache(scopedCache, id, epoch, streamEpochs)
            val extraction = Extraction(
                metadata = Promise(Metadata(), scope, autostart = false) {
                    val operation = AppLog.operation(AppLog.Category.EXTRACTOR, "metadata", diagnostic.child())
                    try {
                        it.resolveMetadata(id, scopedCache) { resolved.getOrThrow().metadata }.also { operation.finish(DiagnosticOutcome.SUCCESS) }
                    } catch (failure: Throwable) {
                        operation.finishExtractionFailure(failure)
                        throw failure
                    }
                },
                stream = Promise(Stream(), scope, autostart = false) {
                    val operation = AppLog.operation(AppLog.Category.EXTRACTOR, "stream", diagnostic.child(), mapOf("catalog" to catalog, "fresh" to fresh, "preparation" to preparation))
                    try {
                        it.resolveStream(id, streamCache) { resolved.getOrThrow().stream }.also { operation.finish(DiagnosticOutcome.SUCCESS) }
                    } catch (failure: Throwable) {
                        operation.finishExtractionFailure(failure)
                        throw failure
                    }
                },
                chapters = Promise(ChapterList(), scope, autostart = false) {
                    it.resolveChapters(id, scopedCache) { requireHost().chapters(id, context()) }
                },
            )
            return Inflight(id, epoch, extraction, active, preparation, catalog,
                demand?.cacheKey().orEmpty(), { host?.scopeHint() == initialScope }, diagnostic) {
                foreground.set(true)
                captured.get()?.prioritizePlayback()
            }
        }
    }
}

internal fun Promise<Metadata>.resolveMetadata(
    videoId: String,
    cache: Cache,
    resolved: () -> Metadata,
) {
    cache.getMetadata(videoId)?.let { cached ->
        set { copyFrom(cached) }
        return
    }
    set { copyFrom(resolved()) }
    cache.putMetadata(videoId, value)
}

internal fun Promise<Stream>.resolveStream(
    videoId: String,
    cache: Cache,
    resolved: () -> Stream,
) {
    cache.getStream(videoId)?.let { cached ->
        set { copyFrom(cached) }
        return
    }
    set { copyFrom(resolved()) }
    cache.putStream(videoId, value)
}

internal fun Promise<ChapterList>.resolveChapters(
    videoId: String,
    cache: Cache,
    loaded: () -> List<Chapter>,
) {
    cache.getChapters(videoId)?.let { cached ->
        set { copyFrom(cached) }
        return
    }
    set { chapters = loaded() }
    cache.putChapters(videoId, value)
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
 * [Cache] view that drops stream reads/writes whose [epoch] is stale. Check and
 * mutate happen under [StreamEpochGuard.withLock] so an invalidation cannot land
 * between the freshness test and the inner write.
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
 * invalidation. Entries are only created by [bump] and never reclaimed, so a
 * settled sibling cannot make an older still-in-flight extraction look fresh
 * again (its absent-default epoch of 0 would otherwise match its own). The map
 * grows by one Long per invalidated video id.
 */
internal class StreamEpochGuard {
    private val epochs = ConcurrentHashMap<String, Long>()
    private val locks = ConcurrentHashMap<String, Any>()

    private fun lockFor(id: String): Any = locks.computeIfAbsent(id) { Any() }

    /** Serializes epoch checks with cache writes and [Extractor.invalidateStream]. */
    fun <T> withLock(id: String, block: () -> T): T =
        synchronized(lockFor(id)) { block() }

    fun epochFor(id: String): Long = epochs.getOrDefault(id, 0L)

    fun bump(id: String) {
        withLock(id) { bumpUnlocked(id) }
    }

    /** Caller must already hold [withLock] for [id]. */
    fun bumpUnlocked(id: String) {
        epochs.merge(id, 1L) { old, _ -> old + 1 }
    }
}

/** Content access restrictions are expected outcomes, not diagnostic incidents. */
private fun com.hhst.youtubelite.diagnostics.DiagnosticOperation.finishExtractionFailure(failure: Throwable) {
    val paid = (failure as? PlaybackResolutionException)?.paymentRequired == true
    finish(when {
        failure is kotlinx.coroutines.CancellationException -> DiagnosticOutcome.CANCELLED
        paid -> DiagnosticOutcome.DEFERRED
        else -> DiagnosticOutcome.FAILURE
    }, reason = if (paid) "payment_required" else null, failure = if (paid) null else failure)
}
