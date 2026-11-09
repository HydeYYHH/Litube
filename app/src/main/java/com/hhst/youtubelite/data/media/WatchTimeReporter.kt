package com.hhst.youtubelite.data.media

import com.hhst.youtubelite.diagnostics.AppLog
import com.hhst.youtubelite.diagnostics.DiagnosticRedaction
import com.liskovsoft.youtubeapi.videoinfo.V2.HttpClient
import com.liskovsoft.youtubeapi.videoinfo.V2.HttpReply
import com.liskovsoft.youtubeapi.videoinfo.V2.HttpRequest
import com.liskovsoft.youtubeapi.videoinfo.V2.PlayerTracking
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestContext
import com.liskovsoft.youtubeapi.videoinfo.V2.WatchHistory
import com.liskovsoft.youtubeapi.videoinfo.V2.loadAccountWatchTracking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Bounded, identity-scoped reporting of observed playback intervals. */
class WatchTimeReporter(private val client: MediaClient, private val scope: CoroutineScope,
    private val newHistory: () -> WatchHistory = { WatchHistory() }, private val now: () -> Long = System::currentTimeMillis,
    private val loadAccountTracking: (String, RequestContext, HttpClient) -> PlayerTracking = { id, context, http ->
        loadAccountWatchTracking(id, context, http).requireValue()
    }) {
    private sealed interface Update {
        val durationMs: Long
        data class Anchor(val positionMs: Long, override val durationMs: Long) : Update
        data class Range(val startMs: Long, val endMs: Long, override val durationMs: Long) : Update
    }

    private class Session(val videoId: String, val tracking: PlayerTracking?, val generation: Long,
        val lease: Long, val history: WatchHistory) {
        @Volatile var valid = true
        var accountTracking: PlayerTracking? = null
        val updates = ArrayDeque<Update>()
        var job: Job? = null
        var inFlight: Update? = null
        var closing = false
        var positionMs = -1L
        var queuedPositionMs = -1L
        var durationMs = 0L
        var discontinuity: Long? = null
        var playing = false
        var failures = 0
        var nextRetryAtMs = 0L
    }

    @Volatile private var lease = 0L
    private val sessions = linkedSetOf<Session>()
    private val wirePermit = Semaphore(1)
    private var current: Session? = null
    private var suspended = false
    var onAnonymousReport: (() -> Unit)? = null

    @Synchronized fun watch(videoId: String, tracking: PlayerTracking?, generation: Long = MediaSession.snapshot().generation) {
        if (generation != MediaSession.snapshot().generation) return
        val usable = tracking?.takeIf { !it.watchTimeUrl.isNullOrBlank() }
        val previous = current
        if (previous?.videoId == videoId && (previous.tracking == usable || MediaSession.signedIn()) &&
            previous.generation == generation && previous.valid) return
        previous?.let(::finishSession)
        current = Session(videoId, usable, generation, lease, newHistory()).also(sessions::add)
        while (sessions.size > MAX_SESSIONS) invalidate(sessions.first { it !== current })
        if (usable == null && !MediaSession.signedIn()) AppLog.event(AppLog.Category.PLAYER, "watch_report.unavailable",
            mapOf("video" to DiagnosticRedaction.identity(videoId),
                "reason" to if (tracking == null) "details_missing" else "watchtime_url_missing"))
    }

    /** Flushes the observed tail without transferring it to the next video's ledger. */
    @Synchronized fun finish() {
        current?.let(::finishSession)
        current = null
        sessions.toList().forEach(::dispatch)
    }

    @Synchronized fun stop() {
        invalidateAll()
        current = null
        suspended = false
    }

    @Synchronized fun cancelPending() {
        if (suspended) return
        suspended = true
        val previous = current
        invalidateAll()
        current = previous?.let { Session(it.videoId, it.tracking, it.generation, lease, newHistory()).also(sessions::add) }
    }

    @Synchronized fun tick(positionMs: Long, durationMs: Long, playing: Boolean, playingVideoId: String? = current?.videoId,
        discontinuityEpoch: Long = current?.discontinuity ?: 0L) {
        if (MediaSession.privateBrowsing()) { cancelPending(); return }
        suspended = false
        sessions.toList().forEach(::dispatch)
        val session = current ?: return
        if (!isCurrent(session)) { invalidate(session); return }
        if (session.tracking == null && !MediaSession.signedIn() || playingVideoId != session.videoId ||
            durationMs <= 0 || positionMs !in 0..durationMs) return
        val discontinuity = session.discontinuity?.let { it != discontinuityEpoch } == true ||
            session.positionMs >= 0 && positionMs < session.positionMs
        if (discontinuity && session.positionMs >= 0) {
            enqueueRange(session, force = true)
            enqueueAnchor(session, positionMs, durationMs)
        } else if (playing && session.positionMs < 0) {
            enqueueAnchor(session, positionMs, durationMs)
        }
        session.discontinuity = discontinuityEpoch
        if (playing || session.positionMs >= 0) {
            session.positionMs = positionMs
            session.durationMs = durationMs
            enqueueRange(session, force = !playing && session.playing)
        }
        session.playing = playing
        dispatch(session)
    }

    private fun enqueueAnchor(session: Session, positionMs: Long, durationMs: Long) {
        val previous = session.updates.lastOrNull()
        if (previous is Update.Anchor && previous !== session.inFlight) session.updates.removeLast()
        session.updates.addLast(Update.Anchor(positionMs, durationMs))
        session.queuedPositionMs = positionMs
        trim(session)
    }

    private fun enqueueRange(session: Session, force: Boolean) {
        val start = session.queuedPositionMs
        val end = session.positionMs
        if (start < 0 || end <= start || !force && end - start < REPORT_EVERY_MS) return
        val previous = session.updates.lastOrNull()
        if (previous is Update.Range && previous !== session.inFlight && previous.endMs == start) {
            session.updates.removeLast()
            session.updates.addLast(Update.Range(previous.startMs, end, session.durationMs))
        } else session.updates.addLast(Update.Range(start, end, session.durationMs))
        session.queuedPositionMs = end
        trim(session)
    }

    private fun trim(session: Session) {
        while (session.updates.size > MAX_UPDATES) {
            session.updates.remove(session.updates.first { it !== session.inFlight })
        }
    }

    private fun finishSession(session: Session) {
        session.closing = true
        enqueueRange(session, force = true)
        dispatch(session)
        if (session.job == null && session.updates.isEmpty()) sessions.remove(session)
    }

    private fun isCurrent(session: Session): Boolean = session.valid && session.lease == lease &&
        MediaSession.snapshot().generation == session.generation && !MediaSession.privateBrowsing()

    private fun invalidate(session: Session) {
        session.valid = false
        session.job?.cancel()
        session.updates.clear()
        sessions.remove(session)
    }

    private fun invalidateAll() {
        lease++
        sessions.toList().forEach(::invalidate)
    }

    private fun dispatch(session: Session) {
        if (!scope.isActive || session.job?.isActive == true || session.updates.isEmpty() || !isCurrent(session) ||
            now() < session.nextRetryAtMs) return
        session.job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                while (true) {
                    val update = synchronized(this@WatchTimeReporter) {
                        if (!isCurrent(session)) null else session.updates.firstOrNull()?.also { session.inFlight = it }
                    } ?: break
                    try {
                        val delivered = wirePermit.withPermit { client.read { context, http ->
                            if (!isCurrent(session) || context.generation != session.generation ||
                                !context.signedIn && context.visitor.isNullOrBlank()) return@read false
                            val guarded = object : HttpClient {
                                override fun execute(request: HttpRequest): HttpReply {
                                    if (!isCurrent(session)) throw CancellationException("Reporting paused")
                                    return http.execute(request)
                                }
                            }
                            // Media may come from an anonymous client. Its tracking cannot
                            // be promoted to an account view by adding an OAuth header.
                            val tracking = if (context.signedIn) session.accountTracking
                                ?: loadAccountTracking(session.videoId, context, guarded).also { session.accountTracking = it }
                                else requireNotNull(session.tracking)
                            when (update) {
                                is Update.Anchor -> session.history.update(session.videoId, update.positionMs,
                                    update.durationMs, tracking, context, guarded, discontinuity = true).requireValue()
                                is Update.Range -> {
                                    session.history.update(session.videoId, update.startMs, update.durationMs,
                                        tracking, context, guarded, discontinuity = true).requireValue()
                                    session.history.update(session.videoId, update.endMs, update.durationMs,
                                        tracking, context, guarded).requireValue()
                                }
                            }
                            true
                        } }
                        synchronized(this@WatchTimeReporter) {
                            if (!delivered || !isCurrent(session)) { invalidate(session); return@synchronized }
                            if (session.updates.firstOrNull() === update) session.updates.removeFirst()
                            session.inFlight = null
                            if (session.failures > 0) AppLog.detail(AppLog.Category.PLAYER, "watch_report.recovered",
                                mapOf("video" to DiagnosticRedaction.identity(session.videoId), "after_failures" to session.failures))
                            session.failures = 0
                            session.nextRetryAtMs = 0L
                            AppLog.detail(AppLog.Category.PLAYER, "watch_report.acknowledged", mapOf(
                                "video" to DiagnosticRedaction.identity(session.videoId),
                                "identity" to if (MediaSession.signedIn()) "account" else "device",
                                "kind" to if (update is Update.Range) "interval" else "anchor",
                                "start_ms" to if (update is Update.Range) update.startMs else (update as Update.Anchor).positionMs,
                                "end_ms" to if (update is Update.Range) update.endMs else (update as Update.Anchor).positionMs,
                                "generation" to session.generation))
                            if (update is Update.Range && !MediaSession.signedIn()) onAnonymousReport?.invoke()
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        synchronized(this@WatchTimeReporter) {
                            if (!isCurrent(session)) return@synchronized
                            session.failures = (session.failures + 1).coerceAtMost(MAX_FAILURES)
                            val backoff = if (session.failures >= MAX_FAILURES) RECOVERY_PROBE_MS
                                else minOf(REPORT_EVERY_MS shl session.failures, MAX_BACKOFF_MS)
                            session.nextRetryAtMs = now() + backoff
                            val fields = mapOf("video" to DiagnosticRedaction.identity(session.videoId), "failures" to session.failures,
                                "reason" to ((error as? MediaException)?.failure?.code ?: error.javaClass.simpleName))
                            if (session.failures >= MAX_FAILURES) AppLog.event(AppLog.Category.PLAYER, "watch_report.suspended", fields)
                            else AppLog.detail(AppLog.Category.PLAYER, "watch_report.retry_scheduled", fields)
                        }
                        break
                    }
                }
            } finally {
                synchronized(this@WatchTimeReporter) {
                    session.inFlight = null
                    session.job = null
                    if (!session.valid || session.closing && session.updates.isEmpty()) sessions.remove(session)
                    else dispatch(session)
                }
            }
        }
        session.job?.start()
    }

    internal suspend fun awaitPendingReport() {
        synchronized(this) { sessions.mapNotNull { it.job } }.joinAll()
    }

    private companion object {
        const val REPORT_EVERY_MS = 10_000L
        const val MAX_FAILURES = 5
        const val MAX_BACKOFF_MS = 60_000L
        const val RECOVERY_PROBE_MS = 5 * 60_000L
        const val MAX_SESSIONS = 4
        const val MAX_UPDATES = 16
    }
}
