package com.hhst.youtubelite.data.media

import com.hhst.youtubelite.diagnostics.AppLog
import com.hhst.youtubelite.library.domain.HistoryEntry
import com.hhst.youtubelite.shorts.domain.ShortDetail
import com.hhst.youtubelite.watch.domain.WatchVideo
import com.liskovsoft.youtubeapi.videoinfo.V2.PlayerTracking
import com.liskovsoft.youtubeapi.videoinfo.V2.loadPlayerDetails
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private val reloadVideo: suspend (String) -> ReportingVideo? = { id ->
        client.read { context, http ->
            val details = loadPlayerDetails(id, context, http).requireValue()
            ReportingVideo(id, details.title.orEmpty(), details.author.orEmpty(), details.channelId.orEmpty(),
                details.thumbnail.orEmpty(), details.tracking, context.generation)
        }
    },
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    @Volatile private var video: ReportingVideo? = null
    /** Endpoints armed from the playback response; keyed by the media identity they belong to. */
    private data class ArmedTracking(val videoId: String, val tracking: PlayerTracking?, val generation: Long)
    @Volatile private var armed: ArmedTracking? = null
    private var loop: Job? = null
    private var contextRefresh: Job? = null
    private var contextRefreshKey: Pair<String, Long>? = null
    private var contextRefreshAt = 0L
    @Volatile private var sampledVideoId: String? = null
    @Volatile private var privacyEpoch = 0L
    private var localRecordKey: Triple<String, Long, Long>? = null
    private var localRecordPositionMs = -1L

    /** Monotonic refresh hint; page consumers separately validate their current identity. */
    private val _anonymousReportSignal = MutableStateFlow(0L)
    val anonymousReportSignal = _anonymousReportSignal.asStateFlow()
    init {
        reporter.onAnonymousReport = { _anonymousReportSignal.update { it + 1L } }
    }

    fun onPrivacyChanged() {
        privacyEpoch++
        localRecordKey = null
        contextRefresh?.cancel()
        contextRefreshKey = null
        reporter.cancelPending()
    }

    /**
     * Arms the endpoints a successful playback response carried for [videoId].
     * Page metadata may arrive in either order: whichever lands second activates
     * the reporter, and a page publish never disarms an armed session.
     */
    @Synchronized fun armTracking(videoId: String, tracking: PlayerTracking?, generation: Long = MediaSession.snapshot().generation) {
        if (generation != MediaSession.snapshot().generation) return
        if (contextRefreshKey?.first?.let { it != videoId } == true) {
            contextRefresh?.cancel()
            contextRefreshKey = null
        }
        armed = ArmedTracking(videoId, tracking, generation)
        val pageTracking = video?.takeIf { it.id == videoId && it.sessionGeneration == generation }?.tracking
        reporter.watch(videoId, usableTracking(tracking) ?: usableTracking(pageTracking), generation)
    }

    /** Publishes page metadata without interrupting an armed playback session. */
    @Synchronized fun publish(value: ReportingVideo?) {
        val generation = MediaSession.snapshot().generation
        if (value?.sessionGeneration != null && value.sessionGeneration != generation) return
        video = value?.copy(sessionGeneration = generation)
        localRecordKey = null
        if (value == null) {
            if (armed == null) reporter.finish()
            return
        }
        val active = armed?.takeIf { it.generation == generation }
        if (active == null) reporter.watch(value.id, usableTracking(value.tracking), generation)
        else if (active.videoId == value.id) {
            reporter.watch(value.id, usableTracking(active.tracking) ?: usableTracking(value.tracking), generation)
        }
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
        sampledVideoId = null
        contextRefresh?.cancel()
        contextRefreshKey = null
        reporter.finish()
    }

    fun finishPlayback(sample: PlaybackSample) {
        tick(sample)
        reporter.finish()
        sampledVideoId = null
        contextRefresh?.cancel()
        contextRefreshKey = null
        if (armed?.videoId == sample.videoId) armed = null
    }

    internal suspend fun awaitPendingReports() = reporter.awaitPendingReport()
    internal suspend fun awaitContextRefresh() = contextRefresh?.join()

    /** One reporting pass; internal so tests drive it without the wall-clock loop. */
    fun tick(sample: PlaybackSample?) {
        sampledVideoId = sample?.videoId
        if (MediaSession.privateBrowsing()) {
            // Privacy on: queued reports die here and nothing new is scheduled.
            reporter.cancelPending()
            return
        }
        if (sample == null) return
        if (sample.videoId == null) {
            reporter.finish()
            armed = null
            return
        }
        val generation = MediaSession.snapshot().generation
        val staleContext = armed?.let { it.videoId == sample.videoId && it.generation != generation } == true ||
            video?.let { it.id == sample.videoId && it.sessionGeneration != generation } == true
        if (sample.playing && staleContext) refreshContext(sample.videoId, generation)
        armed?.takeIf { it.videoId == sample.videoId && it.generation == generation }?.let {
            val pageTracking = video?.takeIf { page -> page.id == it.videoId && page.sessionGeneration == generation }?.tracking
            reporter.watch(it.videoId, usableTracking(it.tracking) ?: usableTracking(pageTracking), generation)
        }
        reporter.tick(sample.positionMs, sample.durationMs, sample.playing, sample.videoId,
            sample.discontinuityEpoch)
        recordLocal(sample)
    }

    @Synchronized private fun refreshContext(videoId: String, generation: Long) {
        if (ContentSource.fixtures) return
        val key = videoId to generation
        val now = clock()
        if (contextRefreshKey == key &&
            (contextRefresh?.isActive == true || now - contextRefreshAt < CONTEXT_RETRY_MS)) return
        contextRefresh?.cancel()
        contextRefreshKey = key
        contextRefreshAt = now
        contextRefresh = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val fresh = reloadVideo(videoId) ?: return@launch
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (contextRefreshKey != key || sampledVideoId != videoId || fresh.sessionGeneration != generation ||
                    MediaSession.snapshot().generation != generation || MediaSession.privateBrowsing()) return@launch
                publish(fresh)
                armTracking(videoId, fresh.tracking, generation)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                AppLog.event(AppLog.Category.PLAYER, "watch_report.context_refresh_failed",
                    mapOf("reason" to error.javaClass.simpleName), failure = error)
            }
        }
        contextRefresh?.start()
    }

    private fun recordLocal(sample: PlaybackSample) {
        val video = video ?: return
        if (!sample.playing || video.id != sample.videoId) return
        if (MediaSession.signedIn() || MediaSession.privateBrowsing()) return
        val generation = MediaSession.snapshot().generation
        if (video.sessionGeneration != generation) return
        val recordKey = Triple(video.id, generation, sample.discontinuityEpoch)
        if (localRecordKey == recordKey && sample.positionMs - localRecordPositionMs in 0 until LOCAL_RECORD_EVERY_MS) return
        localRecordKey = recordKey
        localRecordPositionMs = sample.positionMs
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
        const val TICK_MS = 1_000L
        const val LOCAL_RECORD_EVERY_MS = 5_000L
        const val CONTEXT_RETRY_MS = 30_000L
    }
}
