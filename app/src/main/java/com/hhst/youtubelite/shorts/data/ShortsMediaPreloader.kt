@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hhst.youtubelite.shorts.data

import android.content.Context
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import com.hhst.youtubelite.data.media.MediaSession
import com.hhst.youtubelite.diagnostics.AppLog
import com.hhst.youtubelite.extractor.Extractor
import com.hhst.youtubelite.extractor.Format
import com.hhst.youtubelite.extractor.PlaybackDemand
import com.hhst.youtubelite.extractor.Stream
import com.hhst.youtubelite.extractor.VideoId
import com.hhst.youtubelite.extension.ExtensionManager
import com.hhst.youtubelite.extension.PreferenceKeys
import com.hhst.youtubelite.player.datasource.PlayerDataSource
import com.hhst.youtubelite.player.datasource.StreamSelection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Warms the shared playback cache with the head of the next short so a swipe
 * lands on buffered bytes instead of a cold fetch.
 *
 * Only real media is prefetched: init + index plus a small head of the audio
 * and video renditions the resolver would pick, read through the same
 * [PlayerDataSource] factories as playback so request plans, cache keys and
 * the shared SimpleCache all match what the player later reads. Prefetch is
 * not viewing: nothing here touches history or watch reporting.
 */
class ShortsMediaPreloader(
    private val context: Context,
    private val extractor: Extractor,
    private val dataSources: PlayerDataSource,
    private val prefs: ExtensionManager? = null,
    private val demandFor: (String) -> PlaybackDemand? = { null },
) {
    /** Serializes prefetch network work; playback always outranks it. */
    private val networkPermit = Semaphore(1)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var job: Job? = null
    @Volatile private var target: String? = null

    /** Starts (or re-targets) the single background prefetch for [videoId]. */
    fun preload(videoId: String?) {
        val id = videoId?.let(VideoId::parse) ?: run { cancel(); return }
        synchronized(lock) {
            if (id == target && job?.isActive == true) return
            if (target != null && target != id) AppLog.detail(
                AppLog.Category.PLAYER, "shorts_prefetch_retarget",
                mapOf("video_id" to id, "superseded" to true))
            job?.cancel()
            target = id
            val demand = demandFor(id)
            job = scope.launch { run(id, demand) }
        }
    }

    /** Stops any in-flight prefetch; called on identity change, privacy switch and leaving Shorts. */
    fun cancel() {
        synchronized(lock) {
            if (job?.isActive == true) AppLog.detail(
                AppLog.Category.PLAYER, "shorts_prefetch_cancelled", emptyMap())
            job?.cancel()
            job = null
            target = null
        }
    }

    private suspend fun run(videoId: String, demand: PlaybackDemand?) {
        val session = MediaSession.snapshot().generation
        if (MediaSession.privateBrowsing() || prefs?.isEnabled(PreferenceKeys.DATA_SAVER) == true) return
        coroutineContext.ensureActive()
        val preparation = extractor.prefetch(videoId, demand)
        try {
            val stream = preparation.extraction.stream.await()
            coroutineContext.ensureActive()
            // The resolved URLs outlive nothing: bail out when identity or
            // privacy moved while the read was in flight.
            if (MediaSession.privateBrowsing() || MediaSession.snapshot().generation != session) return
            val formats = pickFormats(stream, demand)
            if (formats.isEmpty()) return
            var remaining = TOTAL_BUDGET_BYTES
            var cached = 0L
            for (format in formats) {
                coroutineContext.ensureActive()
                if (MediaSession.privateBrowsing() || MediaSession.snapshot().generation != session) break
                if (remaining <= 0) break
                val covered = networkPermit.withPermit {
                    val requestContext = coroutineContext
                    runInterruptible {
                        head(format, remaining) {
                            requestContext.ensureActive()
                            if (MediaSession.privateBrowsing() || MediaSession.snapshot().generation != session ||
                                prefs?.isEnabled(PreferenceKeys.DATA_SAVER) == true) throw CancellationException("Prefetch stopped")
                        }
                    }
                }
                cached += covered
                remaining -= covered
            }
            if (cached > 0) AppLog.event(AppLog.Category.PLAYER, "shorts_prefetch",
                mapOf("video_id" to videoId, "bytes" to cached, "formats" to formats.size))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Prefetch is best-effort; playback resolves its own media.
        } finally {
            preparation.cancel()
        }
    }

    /**
     * Mirrors the resolver's adaptive pick: highest-bitrate original/default
     * audio plus the startup video rendition. Manifest-driven streams (HLS or
     * remote DASH) and plan-less formats are skipped — their requests cannot
     * be replayed against the byte cache reliably.
     */
    private fun pickFormats(stream: Stream, demand: PlaybackDemand?): List<Format> {
        val candidates = stream.formats.filter { it.requestPlan != null }
        if (candidates.none { it.hasDashRanges }) return emptyList()
        val videos = StreamSelection.preferPlayable(candidates.filter { it.videoOnly && it.hasDashRanges }, false)
        val audios = StreamSelection.preferPlayable(
            StreamSelection.audioCandidates(candidates.filter { it.audioOnly && it.hasDashRanges }), false)
        val height = demand?.height?.takeIf { it > 0 }
            ?: com.hhst.youtubelite.player.datasource.PlaybackStartup.MAX_START_HEIGHT
        val video = StreamSelection.selectVideo(StreamSelection.filterBest(videos), "${height}p")
        val audio = audios.maxByOrNull { it.bitrate }
        return listOfNotNull(audio, video).distinctBy { it.url }
    }

    /** Reads up to [budget] head bytes of [format] into the shared cache; returns bytes written. */
    private fun head(format: Format, budget: Long, checkActive: () -> Unit): Long {
        val plan = format.requestPlan ?: return 0
        val total = plan.resourceLength.takeIf { it > 0 }
            ?: com.hhst.youtubelite.downloader.net.DownloadBitrate.queryParam(format.url, "clen")?.toLongOrNull()
            ?: -1L
        val length = headBytes(format).coerceAtMost(budget).let { if (total > 0) minOf(it, total) else it }
        if (length <= 0) return 0
        val source = dataSources.formats(listOf(format), dash = true).createDataSource()
        if (source !is CacheDataSource) {
            runCatching { source.close() }
            return 0
        }
        var written = 0L
        try {
            // CacheWriter runs blocking; slice the head so cancellation and
            // failure land between reads instead of mid-stream.
            while (written < length) {
                checkActive()
                val slice = minOf(length - written, PREFETCH_SLICE_BYTES)
                var cachedInSlice = 0L
                val spec = DataSpec.Builder()
                    .setUri(format.url)
                    .setPosition(written)
                    .setLength(slice)
                    .build()
                CacheWriter(source, spec, null) { _, bytesCached, _ ->
                    checkActive()
                    cachedInSlice = bytesCached
                }.cache()
                written += (if (cachedInSlice > 0) cachedInSlice else slice).coerceAtMost(slice)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Partially written spans stay in the cache and still help.
        } finally {
            runCatching { source.close() }
        }
        return written
    }

    /** Init + index plus roughly [HEAD_SECONDS] of media, capped per format. */
    private fun headBytes(format: Format): Long {
        val head = (maxOf(format.initEnd, format.indexEnd) + 1).coerceAtLeast(0).toLong()
        val media = if (format.bitrate > 0) format.bitrate.toLong() / 8 * HEAD_SECONDS else DEFAULT_MEDIA_BYTES
        return (head + media).coerceAtMost(PER_FORMAT_BUDGET_BYTES)
    }

    private companion object {
        /** Initial prefetch budget: about 2 seconds of media, ~4 MiB across formats. */
        const val HEAD_SECONDS = 2
        const val TOTAL_BUDGET_BYTES = 4L * 1024 * 1024
        const val PER_FORMAT_BUDGET_BYTES = 2L * 1024 * 1024
        const val PREFETCH_SLICE_BYTES = 1L * 1024 * 1024
        const val DEFAULT_MEDIA_BYTES = 256L * 1024
    }
}
