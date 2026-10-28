package com.hhst.youtubelite.data.media

import com.hhst.youtubelite.diagnostics.AppLog
import com.hhst.youtubelite.library.domain.HistoryEntry
import com.hhst.youtubelite.shorts.domain.ShortDetail
import com.hhst.youtubelite.watch.domain.WatchVideo
import com.liskovsoft.youtubeapi.videoinfo.V2.PlayerTracking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Playback state sample the reporting loop consumes. */
data class PlaybackSample(val videoId: String?, val positionMs: Long, val durationMs: Long, val playing: Boolean,
    /** Engine seek counter; a change re-anchors the reported interval. */
    val discontinuityEpoch: Long = 0)

/** Metadata one playback session reports against; hosts publish it when their page data lands. */
data class ReportingVideo(
    val id: String,
    val title: String,
    val channel: String,
    val channelId: String,
    val artwork: String,
    val tracking: PlayerTracking?,
    val sessionGeneration: Long? = null,
)

fun WatchVideo.toReportingVideo() = ReportingVideo(id, title, channel, channelId, artwork, tracking, sessionGeneration)
fun ShortDetail.toReportingVideo() = ReportingVideo(item.id, item.title, item.author, item.channelId, item.artwork, tracking, sessionGeneration)

/**
 * Session-owned watch progress reporting. The loop lives here instead of in any
 * activity, so watch-time and device-history keep flowing while playback outlives
 * its host (PiP, mini player, audio-only background). Whoever loads a watch page
 * publishes its metadata; private mode suppresses both sinks.
 */
class PlaybackWatchReporting(
    private val client: MediaClient,
    private val localLibrary: () -> LocalLibraryStore?,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val reporter: WatchTimeReporter = WatchTimeReporter(client, scope),
) {
    @Volatile private var video: ReportingVideo? = null
    /** Endpoints armed from the playback response; keyed by the media identity they belong to. */
    private data class ArmedTracking(val videoId: String, val tracking: PlayerTracking?, val generation: Long)
    @Volatile private var armed: ArmedTracking? = null
    private var loop: Job? = null
    @Volatile private var privacyEpoch = 0L

    /** Monotonic refresh hint; page consumers separately validate their current identity. */
    private val _anonymousReportSignal = MutableStateFlow(0L)
    val anonymousReportSignal = _anonymousReportSignal.asStateFlow()
    init {
        reporter.onAnonymousReport = { _anonymousReportSignal.value += 1L }
    }

    fun onPrivacyChanged() {
        privacyEpoch++
        reporter.cancelPending()
    }

    /**
     * Arms the endpoints a successful playback response carried for [videoId].
     * Page metadata may arrive in either order: whichever lands second activates
     * the reporter, and a page publish never disarms an armed session.
     */
    @Synchronized fun armTracking(videoId: String, tracking: PlayerTracking?, generation: Long = MediaSession.snapshot().generation) {
        if (generation != MediaSession.snapshot().generation) return
        armed = ArmedTracking(videoId, tracking, generation)
        if (video?.id == videoId && video?.sessionGeneration == generation) {
            reporter.watch(videoId, usableTracking(tracking) ?: usableTracking(video?.tracking), generation)
        }
    }

    /** Publishes the loaded page metadata; null clears both sinks. */
    @Synchronized fun publish(value: ReportingVideo?) {
        val generation = MediaSession.snapshot().generation
        if (value?.sessionGeneration != null && value.sessionGeneration != generation) return
        video = value?.copy(sessionGeneration = generation)
        if (value == null) {
            reporter.stop()
            return
        }
        // Reporting endpoints come from the playback response, not the page:
        // a publish without them must keep the endpoints armed for this video.
        val tracking = usableTracking(armed?.takeIf { it.videoId == value.id && it.generation == generation }?.tracking)
            ?: usableTracking(value.tracking)
        reporter.watch(value.id, tracking, generation)
    }

    private fun usableTracking(value: PlayerTracking?): PlayerTracking? =
        value?.takeIf { !it.playbackUrl.isNullOrBlank() && !it.watchTimeUrl.isNullOrBlank() }

    /** Starts the reporting loop; the sampler reads the registered playback. Re-attach replaces. */
    fun attach(sample: () -> PlaybackSample?) {
        loop?.cancel()
        loop = scope.launch {
            while (true) {
                delay(TICK_MS)
                tick(sample())
            }
        }
    }

    fun detach() {
        loop?.cancel()
        loop = null
        reporter.stop()
    }

    /** One reporting pass; internal so tests drive it without the wall-clock loop. */
    fun tick(sample: PlaybackSample?) {
        if (MediaSession.privateBrowsing()) {
            // Privacy on: queued reports die here and nothing new is scheduled.
            reporter.cancelPending()
            return
        }
        if (sample == null) return
        reporter.tick(sample.positionMs, sample.durationMs, sample.playing, sample.videoId,
            sample.discontinuityEpoch)
        recordLocal(sample)
    }

    private fun recordLocal(sample: PlaybackSample) {
        val video = video ?: return
        if (!sample.playing || video.id != sample.videoId) return
        if (MediaSession.signedIn() || MediaSession.privateBrowsing()) return
        val generation = MediaSession.snapshot().generation
        if (video.sessionGeneration != generation) return
        val requestPrivacy = privacyEpoch
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    MediaSession.withAnonymousSession(generation) {
                        // Re-checked at write time: the toggle may have landed while queued.
                        if (MediaSession.privateBrowsing() || requestPrivacy != privacyEpoch) return@withAnonymousSession
                        val store = localLibrary() ?: return@withAnonymousSession
                        store.record(HistoryEntry(video.id, video.id, video.title, video.channel, video.channelId,
                            video.artwork, "",
                            progress = if (sample.durationMs > 0)
                                (sample.positionMs.toFloat() / sample.durationMs).coerceIn(0f, 1f) else null,
                            section = "On this device"))
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
            } catch (error: Exception) {
                AppLog.event(AppLog.Category.STORAGE, "local_history.write_failed", failure = error)
            }
        }
    }

    private companion object {
        const val TICK_MS = 5_000L
    }
}
