package com.hhst.youtubelite.data.media

import com.hhst.youtubelite.diagnostics.AppLog
import com.hhst.youtubelite.diagnostics.DiagnosticRedaction
import com.liskovsoft.youtubeapi.videoinfo.V2.PlayerTracking
import com.liskovsoft.youtubeapi.videoinfo.V2.WatchHistory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException

/**
 * Reports watch progress for the current identity. [WatchHistory] keys its
 * position ledger by the session scope, so an account switch starts a fresh ledger automatically;
 * the reporter itself only throttles ticks to one per [REPORT_EVERY_MS] of watched progress.
 */
class WatchTimeReporter(private val client: MediaClient, private val scope: CoroutineScope,
    private val newHistory: () -> WatchHistory = { WatchHistory() }, private val now: () -> Long = System::currentTimeMillis) {
    @Volatile private var epoch = 0L
    private var ledger = newHistory()
    private var suspended = false
    private var videoId: String? = null
    private var tracking: PlayerTracking? = null
    private var lastReportedMs = -1L
    /** Mirrors the engine's seek counter; starts aligned at 0. */
    private var lastDiscontinuityEpoch = 0L
    private var job: Job? = null
    private var identityGeneration = -1L
    private var failures = 0
    private var nextRetryAtMs = 0L

    /** Fired when an anonymous report lands; consumers get an identity-scoped refresh hint. */
    var onAnonymousReport: (() -> Unit)? = null

    /** Starts reporting for a video; null tracking (fixtures, old responses) disables reporting. */
    @Synchronized fun watch(videoId: String, tracking: PlayerTracking?, generation: Long = MediaSession.snapshot().generation) {
        if (generation != MediaSession.snapshot().generation) return
        if (this.videoId == videoId && this.tracking == tracking && identityGeneration == generation) return
        resetPending()
        suspended = false
        this.videoId = videoId
        this.tracking = tracking?.takeIf { it.watchTimeUrl != null }
        identityGeneration = generation
        lastReportedMs = -1L
        failures = 0
        nextRetryAtMs = 0L
        if (this.tracking == null) {
            // Tracking endpoints come with the player details; without them this video
            // cannot report at all — record the reason instead of silently going dark.
            AppLog.event(AppLog.Category.PLAYER, "watch_report.unavailable",
                fields = mapOf("video" to DiagnosticRedaction.identity(videoId),
                    "reason" to if (tracking == null) "details_missing" else "watchtime_url_missing"))
        }
    }

    @Synchronized fun stop() {
        resetPending()
        videoId = null
        tracking = null
        identityGeneration = -1L
    }

    /** Cancels pending reports and discards the interval spanning private playback. */
    @Synchronized fun cancelPending() {
        if (suspended) return
        suspended = true
        resetPending()
    }

    private fun resetPending() {
        epoch++
        job?.cancel()
        job = null
        ledger = newHistory()
        lastReportedMs = -1L
        failures = 0
        nextRetryAtMs = 0L
    }

    @Synchronized fun tick(positionMs: Long, durationMs: Long, playing: Boolean, playingVideoId: String? = videoId,
        discontinuityEpoch: Long = lastDiscontinuityEpoch) {
        if (MediaSession.privateBrowsing()) { cancelPending(); return }
        suspended = false
        val video = videoId ?: return
        val track = tracking ?: return
        if (playingVideoId != video) return
        // A seek breaks the reported range: re-anchor the ledger at the new
        // position right away — even while paused — so the skipped span is
        // never counted as watch time. The pending report, if any, lands first
        // and the next tick retries the re-anchor.
        if (discontinuityEpoch != lastDiscontinuityEpoch) {
            if (job?.isActive == true) return
            lastDiscontinuityEpoch = discontinuityEpoch
            if (lastReportedMs >= 0 && durationMs > 0 && positionMs in 0..durationMs) {
                scheduleReport(video, track, positionMs, durationMs, discontinuity = true)
            }
            return
        }
        if (!playing || durationMs <= 0 || positionMs !in 0..durationMs ||
            playingVideoId != video || MediaSession.privateBrowsing()) return
        // Only watched progress reports: at most one per REPORT_EVERY_MS span.
        if (lastReportedMs >= 0 && positionMs >= lastReportedMs && positionMs - lastReportedMs < REPORT_EVERY_MS) return
        scheduleReport(video, track, positionMs, durationMs, discontinuity = false)
    }

    private fun scheduleReport(video: String, track: PlayerTracking, positionMs: Long, durationMs: Long,
        discontinuity: Boolean) {
        if (job?.isActive == true) return
        if (!discontinuity && (failures >= MAX_FAILURES || now() < nextRetryAtMs)) return
        val generation = MediaSession.snapshot().generation
        if (generation != identityGeneration) return
        val requestEpoch = epoch
        val requestLedger = ledger
        val watchedInterval = !discontinuity && lastReportedMs >= 0 && positionMs > lastReportedMs
        job = scope.launch {
            try {
                val reported = client.read { context, http ->
                    if (context.generation != generation || MediaSession.privateBrowsing() ||
                        (!context.signedIn && context.visitor.isNullOrBlank())) return@read false
                    val guardedHttp = object : com.liskovsoft.youtubeapi.videoinfo.V2.HttpClient {
                        override fun execute(request: com.liskovsoft.youtubeapi.videoinfo.V2.HttpRequest): com.liskovsoft.youtubeapi.videoinfo.V2.HttpReply {
                            if (epoch != requestEpoch || MediaSession.privateBrowsing())
                                throw CancellationException("Reporting paused")
                            return http.execute(request)
                        }
                    }
                    requestLedger.update(video, positionMs, durationMs, track, context, guardedHttp,
                        discontinuity = discontinuity).requireValue()
                    true
                }
                synchronized(this@WatchTimeReporter) {
                    if (reported && videoId == video && requestEpoch == epoch &&
                        MediaSession.snapshot().generation == generation && !MediaSession.privateBrowsing()) {
                        lastReportedMs = positionMs
                        if (!discontinuity) {
                            if (failures > 0) AppLog.detail(AppLog.Category.PLAYER, "watch_report.recovered",
                                fields = mapOf("video" to DiagnosticRedaction.identity(video), "after_failures" to failures))
                            failures = 0
                            nextRetryAtMs = 0L
                        }
                        // Only a real watched interval can change recommendations;
                        // a seek re-anchor sends nothing and needs no refresh.
                        if (!MediaSession.signedIn() && watchedInterval) onAnonymousReport?.invoke()
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                synchronized(this@WatchTimeReporter) {
                    if (requestEpoch != epoch || MediaSession.snapshot().generation != generation) return@launch
                    // Bounded backoff: the next tick retries, but not faster than the delay
                    // and never past MAX_FAILURES for this arming.
                    failures += 1
                    nextRetryAtMs = now() + minOf(REPORT_EVERY_MS shl failures, MAX_BACKOFF_MS)
                    val reason = (failure as? MediaException)?.failure?.code ?: failure.javaClass.simpleName
                    if (failures >= MAX_FAILURES) {
                        AppLog.event(AppLog.Category.PLAYER, "watch_report.suspended",
                            fields = mapOf("video" to DiagnosticRedaction.identity(video), "failures" to failures, "reason" to reason))
                    } else {
                        AppLog.detail(AppLog.Category.PLAYER, "watch_report.retry_scheduled",
                            fields = mapOf("video" to DiagnosticRedaction.identity(video), "failures" to failures, "reason" to reason))
                    }
                }
            }
        }
    }

    internal suspend fun awaitPendingReport() { job?.join() }

    private companion object {
        const val REPORT_EVERY_MS = 10_000L
        const val MAX_FAILURES = 5
        const val MAX_BACKOFF_MS = 60_000L
    }
}
