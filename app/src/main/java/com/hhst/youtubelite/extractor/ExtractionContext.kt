package com.hhst.youtubelite.extractor

import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class PageResponse(
    val responseCode: Int,
    val responseHeaders: Map<String, List<String>>,
    val responseBody: String,
    val url: String,
) {
    fun header(name: String): String? =
        responseHeaders.entries.firstOrNull { it.key.equals(name, true) }?.value?.firstOrNull()

    companion object {
        fun headerText(retryAfter: String?, nowMillis: Long): Long = retryAfter
            ?.trim()?.toLongOrNull()?.coerceIn(0, 3600)?.times(1000)?.plus(nowMillis) ?: 0L
    }
}

/** HTTP access scoped to one extraction task; cancellable and deadline-bound. */
interface ExtractionTransport {
    @Throws(IOException::class)
    fun get(context: ExtractionContext, url: String, headers: Map<String, List<String>>): PageResponse

    @Throws(IOException::class)
    fun post(context: ExtractionContext, url: String, headers: Map<String, List<String>>, body: ByteArray): PageResponse
}

/** Bounded local diagnostics containing only enums, counters and durations. */
interface EngineDiagnostics {
    fun event(stage: String, profile: String, detail: String, elapsedMillis: Long, httpStatus: Int)
}

/**
 * Immutable per-task extraction state: session, transport, budget, cancellation.
 * Challenge solver and poToken minter share the same fences as resolution.
 */
class ExtractionContext(
    val session: YoutubeWebSession,
    private val transport: ExtractionTransport,
    val live: Boolean,
    val catalog: Boolean,
    playbackPriority: Boolean,
    /** Demand identity participating in stream-cache keys; empty when absent. */
    val demandKey: String = "",
    val deadlineNanos: Long,
    val current: () -> Boolean,
    val diagnostics: EngineDiagnostics,
    private val priority: AtomicBoolean = AtomicBoolean(playbackPriority),
    private val networkRetryAvailable: AtomicBoolean = AtomicBoolean(true),
    private val webConfiguration: ((ExtractionContext) -> ExtractionContext)? = null,
) {
    val playbackPriority: Boolean get() = priority.get()

    internal fun prioritizePlayback() { priority.set(true) }

    @Throws(IOException::class)
    fun check() {
        if (System.nanoTime() >= deadlineNanos) throw IOException("EXTRACTION_DEADLINE")
        if (!current()) throw IOException("SESSION_CHANGED_OR_CANCELLED")
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
    }

    fun remainingMillis(): Long = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime())

    internal fun withWebConfiguration(): ExtractionContext {
        check()
        return if (!session.nativeBootstrap) this else
            (webConfiguration ?: throw IOException("WEB_CONFIGURATION_REQUIRED"))(this).also { it.check() }
    }

    internal fun withSession(snapshot: YoutubeWebSession, valid: () -> Boolean): ExtractionContext =
        ExtractionContext(snapshot, transport, live, catalog, playbackPriority, demandKey, deadlineNanos,
            { current() && valid() }, diagnostics, priority, networkRetryAvailable)

    /** Nested budget for one sub-phase (solver compile, token init). */
    fun withTimeLimit(limitMillis: Long): ExtractionContext {
        val nested = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(limitMillis)
        return ExtractionContext(session, transport, live, catalog, playbackPriority, demandKey,
            minOf(deadlineNanos, nested), current, diagnostics, priority, networkRetryAvailable, webConfiguration)
    }

    @Throws(IOException::class)
    fun get(url: String, headers: Map<String, List<String>>): PageResponse {
        return read("GET", url) { transport.get(it, url, headers) }
    }

    @Throws(IOException::class)
    fun post(url: String, headers: Map<String, List<String>>, body: ByteArray): PageResponse {
        return read("POST", url) { transport.post(it, url, headers, body) }
    }

    private fun read(method: String, url: String, request: (ExtractionContext) -> PageResponse): PageResponse {
        var attempt = this
        while (true) {
            attempt.check()
            val started = System.nanoTime()
            try {
                return request(attempt)
            } catch (failure: IOException) {
                val error = com.liskovsoft.youtubeapi.videoinfo.V2.transportError(failure)
                diagnostics.event("request-failed", ExtractionReadRetry.stage(url),
                    "$method:${error.code}:${error.cause.orEmpty()}", (System.nanoTime() - started) / 1_000_000, 0)
                // Same captured identity, deadline and one retry allowance across nested phases.
                // A failed shared producer recovers here before publishing failure to its waiters.
                check()
                if (!ExtractionReadRetry.allowed(method, url, failure) || remainingMillis() < 500 ||
                    !networkRetryAvailable.compareAndSet(true, false)) throw failure
                diagnostics.event("request-retry", ExtractionReadRetry.stage(url), "$method:${error.code}", 0, 0)
                // Recovery must not add another full 10 s HTTP timeout to startup.
                attempt = withTimeLimit(2_000)
            }
        }
    }
}

class PageHttpException(val stage: String, val status: Int, val retryAtMillis: Long) :
    IOException("${stage}_HTTP_${status}")
