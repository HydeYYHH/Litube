package com.hhst.youtubelite.data.media

import com.liskovsoft.youtubeapi.videoinfo.V2.PlayerTracking
import com.liskovsoft.youtubeapi.videoinfo.V2.WatchHistory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException

/**
 * Reports watch progress to the server while signed in. The MSC [WatchHistory] keys its
 * position ledger by the session scope, so an account switch starts a fresh ledger automatically;
 * the reporter itself only throttles ticks to one per [REPORT_EVERY_MS] of watched progress.
 */
class WatchTimeReporter(private val client: MediaClient, private val scope: CoroutineScope,
    private val history: WatchHistory = WatchHistory()) {
    private var videoId: String? = null
    private var tracking: PlayerTracking? = null
    private var lastReportedMs = -1L
    private var job: Job? = null

    /** Starts reporting for a video; null tracking (fixtures, old responses) disables reporting. */
    fun watch(videoId: String, tracking: PlayerTracking?) {
        if (this.videoId == videoId && this.tracking == tracking) return
        job?.cancel()
        this.videoId = videoId
        this.tracking = tracking?.takeIf { it.watchTimeUrl != null }
        lastReportedMs = -1L
    }

    fun stop() {
        job?.cancel()
        videoId = null
        tracking = null
    }

    fun tick(positionMs: Long, durationMs: Long, playing: Boolean, playingVideoId: String? = videoId) {
        val video = videoId ?: return
        val track = tracking ?: return
        if (!playing || durationMs <= 0 || positionMs !in 0..durationMs ||
            playingVideoId != video || !MediaSession.signedIn() || job?.isActive == true) return
        // A backward seek starts a new range and reports at once.
        if (lastReportedMs >= 0 && positionMs >= lastReportedMs && positionMs - lastReportedMs < REPORT_EVERY_MS) return
        val generation = MediaSession.snapshot().generation
        job = scope.launch {
            try {
                val reported = client.read { context, http ->
                    if (context.generation != generation) return@read false
                    history.update(video, positionMs, durationMs, track, context, http).requireValue()
                    true
                }
                if (reported && videoId == video) lastReportedMs = positionMs
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The next tick retries an unconfirmed report.
            }
        }
    }

    private companion object {
        const val REPORT_EVERY_MS = 10_000L
    }
}
