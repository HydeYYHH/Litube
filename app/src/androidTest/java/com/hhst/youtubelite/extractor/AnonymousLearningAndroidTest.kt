@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hhst.youtubelite.extractor

import android.os.Bundle
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.data.media.MediaHomeRepository
import com.hhst.youtubelite.data.media.MediaSession
import com.hhst.youtubelite.data.media.PlaybackWatchReporting
import com.hhst.youtubelite.home.domain.FeedItem
import com.hhst.youtubelite.player.PlayerViewModel
import com.hhst.youtubelite.player.engine.PlaybackApi
import com.hhst.youtubelite.watch.WatchActivity
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.koin.core.context.GlobalContext

class AnonymousLearningAndroidTest {
    @Test fun directPlaybackRetainsItsVisitorAndReportsEachVideo() = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("network") == "1" && arguments.getString("anonymousReporting") == "1")
        assertTrue("Use an isolated anonymous profile", !MediaSession.signedIn() && !MediaSession.privateBrowsing())
        val context = instrumentation.targetContext
        val koin = GlobalContext.get()
        val reporting = koin.get<PlaybackWatchReporting>()
        val home = MediaHomeRepository(koin.get())
        val records = JSONArray()
        fun record(event: String, value: JSONObject) {
            value.put("event", event)
            records.put(value)
            instrumentation.sendStatus(2, Bundle().apply { putString("anonymousLearning", value.toString()) })
        }
        val videos = arguments.getString("videoIds", "GjjK6yD5I9I,FEZS601QSVE,aqz-KE-bpKQ").split(',').map {
            requireNotNull(VideoId.parse(it.trim()))
        }.take(5)
        val requestedWatchMs = arguments.getString("watchMillis")?.toLongOrNull()?.coerceIn(1_000, 60_000) ?: 12_000L
        home.loadHome("All", null)
        val visitor = requireNotNull(MediaSession.snapshot().visitor)
        val generation = MediaSession.snapshot().generation
        try {
            for (video in videos) {
                val before = reporting.anonymousReportSignal.value
                val started = SystemClock.elapsedRealtime()
                var maximumPosition = 0L
                var firstFrame = false
                var watchedMs = 0L
                var targetWatchMs = requestedWatchMs
                var previousPosition: Long? = null
                var previousEpoch: Long? = null
                ActivityScenario.launch<WatchActivity>(WatchActivity.intent(context, video)).use {
                    lateinit var engine: PlaybackApi
                    lateinit var model: PlayerViewModel
                    instrumentation.runOnMainSync { engine = koin.get(); model = koin.get() }
                    try {
                        val deadline = SystemClock.elapsedRealtime() + 90_000
                        while (SystemClock.elapsedRealtime() < deadline) {
                            val state = engine.snapshot.value
                            if (state.videoId == video) {
                                state.failureReport?.let { failure ->
                                    File(context.filesDir, "learning-playback-failure.txt").writeText(failure.text)
                                    throw AssertionError("Playback failed for $video")
                                }
                                firstFrame = firstFrame || state.firstFrameRendered
                                val position = model.positionState.value
                                maximumPosition = maxOf(maximumPosition, position)
                                if (state.firstFrameRendered && state.isPlaying) {
                                    if (previousPosition == null && state.durationMs > 0) {
                                        targetWatchMs = minOf(requestedWatchMs, (state.durationMs - position - 500).coerceAtLeast(1_000))
                                    }
                                    if (previousEpoch == state.discontinuityEpoch) {
                                        val delta = previousPosition?.let { position - it } ?: 0
                                        if (delta in 0..1_000) watchedMs += delta
                                    }
                                    previousPosition = position
                                    previousEpoch = state.discontinuityEpoch
                                } else previousPosition = null
                                if (watchedMs >= targetWatchMs) break
                            }
                            delay(200)
                        }
                    } finally {
                        instrumentation.runOnMainSync { engine.stop() }
                        withTimeout(15_000) { reporting.awaitPendingReports() }
                    }
                }
                assertTrue("No frame for $video", firstFrame)
                assertTrue("Insufficient playback for $video: $watchedMs < $targetWatchMs", watchedMs >= targetWatchMs)
                assertTrue("No watched interval for $video", reporting.anonymousReportSignal.value > before)
                val currentVisitor = requireNotNull(MediaSession.snapshot().visitor)
                assertEquals("Visitor changed between videos", visitor, currentVisitor)
                assertEquals(generation, MediaSession.snapshot().generation)
                val page = home.loadHome("All", null)
                record("after_video", JSONObject().put("video", video).put("elapsedMs", SystemClock.elapsedRealtime() - started)
                    .put("positionMs", maximumPosition).put("watchedMs", watchedMs).put("watchedIntervals", reporting.anonymousReportSignal.value - before)
                    .put("homeItems", page.items.size).put("messages", page.messages.size).put("hasNext", page.nextKey != null))
            }
            delay(15_000)
            val settled = home.loadHome("All", null)
            record("home_after_wait", JSONObject().put("homeItems", settled.items.size)
                .put("messages", settled.messages.size).put("hasNext", settled.nextKey != null))

        } finally {
            File(context.filesDir, "anonymous-learning.json").writeText(records.toString(2))
        }
    }
}
