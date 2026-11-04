@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hhst.youtubelite.player.datasource

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.text.CueGroup
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.source.LazySubtitleSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.extractor.ExtractionTestActivity
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LazySubtitleAndroidTest {
    @get:Rule val activity = ActivityScenarioRule(ExtractionTestActivity::class.java)

    @Test fun anUnselectedSlowCaptionDoesNotBlockTheFirstFrame() = exercise(selected = false)
    @Test fun aSelectedCaptionStillRendersCues() = exercise(selected = true)
    @Test fun aRateLimitedCaptionIsRequestedOnlyOnce() = exercise(selected = true, denied = true)

    private fun exercise(selected: Boolean, denied: Boolean = false) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val media = File.createTempFile("subtitle-startup", ".ts", app.cacheDir)
        instrumentation.context.assets.open("player/quality-recovery/360.ts").use { input ->
            media.outputStream().use { input.copyTo(it) }
        }
        val server = MockWebServer().apply {
            enqueue(if (denied) MockResponse().setResponseCode(429)
                else MockResponse().setBody("WEBVTT\n\n00:00.000 --> 00:10.000\nCaption still works\n")
                    .apply { if (!selected) setHeadersDelay(4, TimeUnit.SECONDS) })
            start()
        }
        val frame = CountDownLatch(1)
        val cue = CountDownLatch(1)
        val error = CountDownLatch(1)
        val failure = AtomicReference<PlaybackException>()
        var player: ExoPlayer? = null
        try {
            val video = ProgressiveMediaSource.Factory(DefaultDataSource.Factory(app))
                .createMediaSource(MediaItem.fromUri(android.net.Uri.fromFile(media)))
            val format = Format.Builder().setId("sub:en").setSampleMimeType(MimeTypes.TEXT_VTT)
                .setLanguage("en").setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()
            val caption = LazySubtitleSourceFactory.create(DefaultHttpDataSource.Factory(), format,
                android.net.Uri.parse(server.url("/caption.vtt").toString()))
            activity.scenario.onActivity { host ->
                player = ExoPlayer.Builder(host).setLoadControl(com.hhst.youtubelite.player.engine.defaultLoadControl()).build().apply {
                    volume = 0f
                    trackSelectionParameters = trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !selected).setPreferredTextLanguage("en").build()
                    setVideoSurfaceView(host.surface)
                    addListener(object : Player.Listener {
                        override fun onRenderedFirstFrame() { frame.countDown() }
                        override fun onCues(cueGroup: CueGroup) {
                            if (cueGroup.cues.any { it.text.toString() == "Caption still works" }) cue.countDown()
                        }
                        override fun onPlayerError(exception: PlaybackException) { failure.set(exception); error.countDown() }
                    })
                    addAnalyticsListener(object : AnalyticsListener {
                        override fun onLoadError(eventTime: AnalyticsListener.EventTime, info: LoadEventInfo, data: MediaLoadData, exception: IOException, wasCanceled: Boolean) {
                            if (info.dataSpec.uri.path == "/caption.vtt") error.countDown()
                        }
                    })
                    setMediaSource(MergingMediaSource(video, caption)); prepare(); play()
                }
            }
            if (denied) {
                assertTrue("429 was retried instead of surfacing", error.await(3, TimeUnit.SECONDS))
                assertTrue("Caption failure blocked video", frame.await(3, TimeUnit.SECONDS))
                assertNull("Caption failure became a playback failure", failure.get())
                assertEquals(1, server.requestCount)
            } else {
                assertTrue("Caption preparation blocked the first frame: ${failure.get()}", frame.await(3, TimeUnit.SECONDS))
                if (selected) {
                    assertTrue("Selected caption produced no cues", cue.await(3, TimeUnit.SECONDS))
                    assertEquals(1, server.requestCount)
                } else assertEquals("Unselected caption was fetched eagerly", 0, server.requestCount)
                assertNull(failure.get())
            }
        } finally {
            instrumentation.runOnMainSync { player?.release() }
            server.shutdown()
            media.delete()
        }
    }
}
