@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hhst.youtubelite.extractor

import android.os.Bundle
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.data.media.MediaHomeRepository
import com.hhst.youtubelite.data.media.MediaClient
import com.hhst.youtubelite.data.media.MediaSession
import com.hhst.youtubelite.data.media.PlaybackWatchReporting
import com.hhst.youtubelite.player.PlayerViewModel
import com.hhst.youtubelite.player.engine.PlaybackApi
import com.hhst.youtubelite.watch.WatchActivity
import com.liskovsoft.youtubeapi.videoinfo.V2.PlayerClient
import com.liskovsoft.youtubeapi.videoinfo.V2.HttpRequest
import com.liskovsoft.youtubeapi.videoinfo.V2.playerAnswer
import com.liskovsoft.youtubeapi.videoinfo.V2.playerQuery
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.koin.core.context.GlobalContext

class AnonymousRecommendationAndroidTest {
    @Test fun realPlaybackReportsAnIntervalAndRecordsHomeAvailability() = runBlocking(Dispatchers.IO) {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("network") == "1" && arguments.getString("anonymousReporting") == "1")
        assumeTrue(!MediaSession.signedIn() && !MediaSession.privateBrowsing())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val koin = GlobalContext.get()
        val home = MediaHomeRepository(koin.get())
        val reporting = koin.get<PlaybackWatchReporting>()
        val records = JSONArray()
        fun record(event: String, fields: JSONObject) {
            fields.put("event", event)
            records.put(fields)
            instrumentation.sendStatus(2, Bundle().apply { putString("anonymousRecommendation", fields.toString()) })
        }
        suspend fun readHome(event: String) {
            val page = home.loadHome("All", null)
            record(event, JSONObject().put("items", page.items.size).put("messages", page.messages.size)
                .put("hasContinuation", page.nextKey != null))
        }
        var scenario: ActivityScenario<WatchActivity>? = null
        var model: PlayerViewModel? = null
        try {
            readHome("home_before")
            val identity = MediaSession.snapshot()
            val visitor = requireNotNull(identity.visitor)
            val fingerprint = MessageDigest.getInstance("SHA-256").digest(visitor.toByteArray())
                .take(8).joinToString("") { "%02x".format(it) }
            record("identity", JSONObject().put("generation", identity.generation).put("visitorHash", fingerprint))
            val before = reporting.anonymousReportSignal.value
            val video = requireNotNull(VideoId.parse(arguments.getString("videoId", "aqz-KE-bpKQ")))
            scenario = ActivityScenario.launch(WatchActivity.intent(context, video))
            lateinit var engine: PlaybackApi
            instrumentation.runOnMainSync { model = koin.get(); engine = koin.get() }
            val deadline = SystemClock.elapsedRealtime() + 120_000
            var nextSample = 0L
            while (SystemClock.elapsedRealtime() < deadline) {
                val state = engine.snapshot.value
                if (state.videoId != null && state.videoId != video) break
                if (state.error != null) {
                    record("playback_failed", JSONObject().put("positionMs", state.positionMs))
                    if (arguments.getString("inspectPlayerResponses") == "1") {
                        koin.get<MediaClient>().read { identity, http ->
                            for (profile in listOf(PlayerClient.TV, PlayerClient.WEB, PlayerClient.ANDROID_VR)) {
                                val query = playerQuery(profile, video, identity.visitor)
                                val response = http.execute(HttpRequest("POST", query.url, query.headers, query.body))
                                val answer = playerAnswer(response.body)
                                record("player_availability", JSONObject().put("profile", profile.name)
                                    .put("httpStatus", response.status)
                                    .put("status", answer.status?.takeIf { it.matches(Regex("[A-Z_]{1,48}")) })
                                    .put("muxedFormats", answer.muxedFormats.size)
                                    .put("adaptiveFormats", answer.adaptiveFormats.size)
                                    .put("hasHls", !answer.hlsUrl.isNullOrBlank())
                                    .put("hasSabr", !answer.sabrUrl.isNullOrBlank()))
                            }
                        }
                    }
                    throw AssertionError("Playback failed before a watched interval could be reported")
                }
                val now = SystemClock.elapsedRealtime()
                if (now >= nextSample) {
                    record("playback", JSONObject().put("positionMs", state.positionMs).put("playing", state.isPlaying)
                        .put("firstFrame", state.firstFrameRendered).put("reports", reporting.anonymousReportSignal.value - before))
                    nextSample = now + 5_000
                }
                if (reporting.anonymousReportSignal.value > before) break
                delay(250)
            }
            val delivered = reporting.anonymousReportSignal.value - before
            record("reporting_result", JSONObject().put("watchedIntervals", delivered))
            assertTrue("No watched interval was acknowledged", delivered > 0)
            assertEquals("Visitor changed during playback", visitor, MediaSession.snapshot().visitor)
            assertEquals("Identity changed during playback", identity.generation, MediaSession.snapshot().generation)
            readHome("home_after_report")
            delay(10_000)
            readHome("home_after_wait")
        } finally {
            instrumentation.runOnMainSync { model?.stopOnExit() }
            scenario?.close()
            File(context.filesDir, "anonymous-recommendation.json").writeText(records.toString(2))
        }
    }
}
