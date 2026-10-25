package com.hhst.youtubelite.extractor

import java.io.IOException
import java.util.concurrent.TimeUnit

/** One executed page/API request. */
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
 * Immutable per-task extraction state: session snapshot, transport, budget and
 * cancellation. Handed to the challenge solver and the poToken minter so their
 * requests share the same fences as resolution itself.
 */
class ExtractionContext(
    val session: YoutubeWebSession,
    private val transport: ExtractionTransport,
    val live: Boolean,
    val catalog: Boolean,
    val playbackPriority: Boolean,
    /** Demand identity participating in stream-cache keys; empty when absent. */
    val demandKey: String = "",
    val deadlineNanos: Long,
    val current: () -> Boolean,
    val diagnostics: EngineDiagnostics,
) {
    @Throws(IOException::class)
    fun check() {
        if (System.nanoTime() >= deadlineNanos) throw IOException("EXTRACTION_DEADLINE")
        if (!current()) throw IOException("SESSION_CHANGED_OR_CANCELLED")
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
    }

    fun remainingMillis(): Long = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime())

    /** Nested budget for one sub-phase (solver compile, token init). */
    fun withTimeLimit(limitMillis: Long): ExtractionContext {
        val nested = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(limitMillis)
        return ExtractionContext(session, transport, live, catalog, playbackPriority, demandKey,
            minOf(deadlineNanos, nested), current, diagnostics)
    }

    @Throws(IOException::class)
    fun get(url: String, headers: Map<String, List<String>>): PageResponse {
        check()
        return transport.get(this, url, headers)
    }

    @Throws(IOException::class)
    fun post(url: String, headers: Map<String, List<String>>, body: ByteArray): PageResponse {
        check()
        return transport.post(this, url, headers, body)
    }
}

/** Failed page/API read carrying the HTTP status for diagnostics and recovery. */
class PageHttpException(val stage: String, val status: Int, val retryAtMillis: Long) :
    IOException("${stage}_HTTP_${status}")
