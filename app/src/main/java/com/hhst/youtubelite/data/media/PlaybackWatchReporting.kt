package com.hhst.youtubelite.data.media

import com.hhst.youtubelite.diagnostics.AppLog
import com.hhst.youtubelite.library.domain.HistoryEntry
import com.hhst.youtubelite.watch.domain.WatchVideo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Playback state sample the reporting loop consumes. */
data class PlaybackSample(val videoId: String?, val positionMs: Long, val durationMs: Long, val playing: Boolean)

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
    @Volatile private var video: WatchVideo? = null
    private var loop: Job? = null

    /** Publishes the loaded watch page metadata; null clears both sinks. */
    fun publish(value: WatchVideo?) {
        video = value
        if (value == null) reporter.stop() else reporter.watch(value.id, value.tracking)
    }

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
        if (sample == null) return
        reporter.tick(sample.positionMs, sample.durationMs, sample.playing, sample.videoId)
        recordLocal(sample)
    }

    private fun recordLocal(sample: PlaybackSample) {
        val video = video ?: return
        if (!sample.playing || video.id != sample.videoId) return
        if (MediaSession.signedIn() || MediaSession.privateBrowsing()) return
        val generation = MediaSession.snapshot().generation
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    MediaSession.withAnonymousSession(generation) {
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
