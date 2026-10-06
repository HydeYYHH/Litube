package com.hhst.youtubelite.extractor

import android.os.Bundle
import com.hhst.youtubelite.player.engine.defaultLoadControl
import com.hhst.youtubelite.player.datasource.StreamSelection
import com.hhst.youtubelite.player.datasource.PlaybackStartup
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.hhst.youtubelite.player.datasource.MediaSourceResolver
import com.hhst.youtubelite.player.datasource.PlayerDataSource
import com.hhst.youtubelite.player.datasource.StartCappedSelectionFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.koin.core.context.GlobalContext
import org.schabi.newpipe.extractor.services.youtube.streams.StreamDemand
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Same-account native extraction through first decoded frame, with immediate cache replays. */
@UnstableApi
class YoutubeLoadSpeedAndroidTest {
    @get:org.junit.Rule val activity = ActivityScenarioRule(ExtractionTestActivity::class.java)

    @Test fun extractionAndFirstFrame() = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        activity.scenario.onActivity { host ->
            if (android.os.Build.VERSION.SDK_INT >= 27) {
                host.setShowWhenLocked(true)
                host.setTurnScreenOn(true)
            }
        }
        val ids = args.getString("videoIds").orEmpty().split(',').mapNotNull(VideoId::parse)
        assumeTrue("Supply -e videoIds id1,id2 for real-network load-speed benchmarking", ids.isNotEmpty())
        val extractor = GlobalContext.get().get<Extractor>()
        val diagnostics = GlobalContext.get().get<ExtractionDiagnostics>()
        // startup=off is the A/B control: manifest variant order and the uncapped selector.
        val startup = args.getString("startup", "on") != "off"
        val manualQuality = args.getString("quality")
        val meter = androidx.media3.exoplayer.upstream.DefaultBandwidthMeter.getSingletonInstance(instrumentation.targetContext)
        lateinit var selection: DefaultTrackSelector
        val resolver = if (startup) MediaSourceResolver(GlobalContext.get().get<PlayerDataSource>(), { meter.bitrateEstimate },
            PlaybackStartup.videoSupport(instrumentation.targetContext)).also {
                it.configureStartupTrackSelection { selection.parameters }
            }
            else MediaSourceResolver(GlobalContext.get().get<PlayerDataSource>())
        val records = mutableListOf<Map<String, Any>>()
        val demand = StreamDemand(720, null, false, setOf("avc", "vp9", "vp09", "hvc", "hev", "av01"))
        val leadMs = args.getString("prepareLeadMs", "0").toLong()
        // Stands in for the browser document: "session" captures configuration only, "full" also initializes.
        val preload = args.getString("preload", "none")
        var preloadMs = 0L
        if (preload != "none") {
            val preloadStarted = SystemClock.elapsedRealtime()
            GlobalContext.get().get<YoutubeSessionProvider>().capture(ids.first())
            if (preload == "full") extractor.initialize()
            preloadMs = SystemClock.elapsedRealtime() - preloadStarted
            Thread.sleep(args.getString("preloadIdleMs", "1000").toLong())
        }
        try {
            for (id in ids) for (replay in 0..1) {
                if (replay == 0) extractor.invalidateStream(id)
                val mark = diagnostics.mark()
                val started = SystemClock.elapsedRealtime()
                if (leadMs > 0 && replay == 0) {
                    extractor.preparePlayback(id, demand)
                    Thread.sleep(leadMs)
                }
                val requestAt = SystemClock.elapsedRealtime()
                val extraction = extractor.extract(id, demand = demand)
                val stream = try {
                    withTimeout(50_000) { extraction.stream.await() }
                } catch (failure: Exception) {
                    val record = mapOf("videoId" to id, "replay" to (replay == 1),
                        "failure" to failure.javaClass.simpleName, "events" to diagnostics.since(mark))
                    records += record
                    instrumentation.sendStatus(2, Bundle().apply {
                        putString("youtubeLoadFailure", Gson().toJson(record))
                    })
                    throw failure
                }
                val streamAt = SystemClock.elapsedRealtime()
                val metadata = extraction.metadata.await()
                val metadataAt = SystemClock.elapsedRealtime()
                val source = resolver.resolve(stream, metadata, preferredQuality = manualQuality)
                val sourceAt = SystemClock.elapsedRealtime()
                val firstFrame = AtomicLong()
                val ready = CountDownLatch(1)
                val failure = AtomicReference<String>()
                val waterfall = Collections.synchronizedList(mutableListOf<Map<String, Any>>())
                lateinit var player: ExoPlayer
                activity.scenario.onActivity { host ->
                    player = ExoPlayer.Builder(host).setLoadControl(defaultLoadControl())
                        .apply { if (startup) {
                            selection = DefaultTrackSelector(host, StartCappedSelectionFactory())
                            setTrackSelector(selection)
                        } }
                        .build().apply {
                        volume = 0f
                        trackSelectionParameters = trackSelectionParameters.buildUpon()
                            .setMaxVideoSize(1280, 720).apply {
                                manualQuality?.let { setMinVideoSize(0, StreamSelection.parseHeight(it)) }
                            }.build()
                        setVideoSurfaceView(host.surface)
                        addListener(object : Player.Listener {
                            override fun onRenderedFirstFrame() { firstFrame.compareAndSet(0, SystemClock.elapsedRealtime()); ready.countDown() }
                            override fun onPlayerError(error: PlaybackException) { failure.set(error.errorCodeName); ready.countDown() }
                        })
                        addAnalyticsListener(LoadWaterfall(sourceAt, waterfall))
                        setMediaSource(source.mediaSource)
                        prepare()
                        play()
                    }
                }
                try {
                    assertTrue("First frame timed out", ready.await(25, TimeUnit.SECONDS))
                    assertTrue("Media3 failed: ${failure.get()}", failure.get() == null && firstFrame.get() > 0)
                    val events = diagnostics.since(mark)
                    val record = linkedMapOf<String, Any>("videoId" to id, "replay" to (replay == 1),
                        "startup" to startup, "quality" to (manualQuality ?: "auto"), "preload" to preload, "preloadMs" to preloadMs,
                        "streamMs" to streamAt - started, "metadataWaitMs" to metadataAt - streamAt,
                        "sourceMs" to sourceAt - metadataAt, "mediaToFrameMs" to firstFrame.get() - sourceAt,
                        "firstFrameMs" to firstFrame.get() - started,
                        "prepareLeadMs" to requestAt - started,
                        "requestToFrameMs" to firstFrame.get() - requestAt,
                        "networkRequests" to events.count { it.stage == "request" },
                        "playerRequests" to events.count { it.stage == "player" && it.detail != "browser-response-cache" },
                        "streamCacheHit" to events.any { it.stage == "stream-cache" && it.detail == "HIT" },
                        "events" to events,
                        "waterfall" to synchronized(waterfall) { waterfall.toList() })
                    records += record
                    instrumentation.sendStatus(2, Bundle().apply { putString("youtubeLoadSpeed", Gson().toJson(record.filterKeys { it != "events" && it != "waterfall" })) })
                } finally { instrumentation.runOnMainSync { player.release() } }
                runCatching { extraction.chapters.await() }
            }
        } finally {
            File(instrumentation.targetContext.filesDir, "youtube-load-speed.json").writeText(Gson().toJson(records))
        }
    }
}

/** Media3 load timeline relative to source creation; URLs reduce to kind, itag and segment index. */
@UnstableApi
private class LoadWaterfall(private val origin: Long, private val out: MutableList<Map<String, Any>>) : AnalyticsListener {
    private fun label(uri: android.net.Uri): String {
        if (!uri.isHierarchical) return uri.scheme.orEmpty()
        val path = uri.path.orEmpty()
        val kind = when {
            "/hls_variant/" in path -> "variant"
            "/hls_playlist/" in path -> "playlist"
            "/videoplayback" in path -> "segment"
            else -> uri.host.orEmpty().substringBefore('.')
        }
        val itag = Regex("/itag/(\\d+)").find(path)?.groupValues?.get(1) ?: uri.getQueryParameter("itag")
        val sq = Regex("/sq/(\\d+)").find(path)?.groupValues?.get(1) ?: uri.getQueryParameter("sq")
        val host = if (uri.host.orEmpty().startsWith("manifest.")) "manifest" else "edge"
        return listOfNotNull(kind, itag?.let { "itag=$it" }, sq?.let { "sq=$it" }, host).joinToString(" ")
    }
    private fun add(event: String, info: LoadEventInfo, data: MediaLoadData, extra: Map<String, Any> = emptyMap()) {
        out += linkedMapOf<String, Any>("event" to event, "atMs" to SystemClock.elapsedRealtime() - origin,
            "dataType" to data.dataType, "trackType" to data.trackType,
            "height" to (data.trackFormat?.height ?: -1), "bitrate" to (data.trackFormat?.bitrate ?: -1),
            "request" to label(info.uri)) + extra
    }
    override fun onLoadStarted(eventTime: AnalyticsListener.EventTime, info: LoadEventInfo, data: MediaLoadData, retryCount: Int) =
        add("start", info, data, mapOf("retry" to retryCount))
    override fun onLoadCompleted(eventTime: AnalyticsListener.EventTime, info: LoadEventInfo, data: MediaLoadData) =
        add("done", info, data, mapOf("durationMs" to info.loadDurationMs, "bytes" to info.bytesLoaded))
    override fun onLoadError(eventTime: AnalyticsListener.EventTime, info: LoadEventInfo, data: MediaLoadData, error: java.io.IOException, wasCanceled: Boolean) =
        add("error", info, data, mapOf("error" to (error.message ?: error.javaClass.simpleName)))
    override fun onTracksChanged(eventTime: AnalyticsListener.EventTime, tracks: Tracks) {
        out += linkedMapOf("event" to "tracks", "atMs" to SystemClock.elapsedRealtime() - origin)
    }
    override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
        out += linkedMapOf("event" to "decoder", "atMs" to SystemClock.elapsedRealtime() - origin,
            "name" to decoderName, "initMs" to initializationDurationMs)
    }
    override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
        out += linkedMapOf("event" to "first-frame", "atMs" to SystemClock.elapsedRealtime() - origin)
    }
}
