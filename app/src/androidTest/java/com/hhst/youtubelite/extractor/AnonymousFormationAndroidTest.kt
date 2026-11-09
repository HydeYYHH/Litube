@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hhst.youtubelite.extractor

import android.os.Bundle
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.data.media.MediaHomeRepository
import com.hhst.youtubelite.data.media.MediaSession
import com.hhst.youtubelite.data.media.MediaSearchRepository
import com.hhst.youtubelite.data.media.PlaybackWatchReporting
import com.hhst.youtubelite.home.domain.FeedItem
import com.hhst.youtubelite.player.PlayerViewModel
import com.hhst.youtubelite.player.engine.PlaybackApi
import com.hhst.youtubelite.watch.WatchActivity
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.koin.core.context.GlobalContext

/** Observes server recommendations after real anonymous search and playback. */
class AnonymousFormationAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val arguments = InstrumentationRegistry.getArguments()
    private val context = instrumentation.targetContext
    private val koin = GlobalContext.get()
    private val records = JSONArray()
    private var previousAnonymousMode: Boolean? = null

    @Before fun useDeviceIdentity() {
        assumeTrue(arguments.getString("network") == "1" && arguments.getString("anonymousFormation") == "1")
        assumeTrue(!MediaSession.privateBrowsing())
        previousAnonymousMode = MediaSession.anonymousMode()
        MediaSession.setAnonymousMode(true)
    }

    @After fun restoreIdentity() {
        val previous = previousAnonymousMode ?: return
        try {
            instrumentation.runOnMainSync { koin.get<PlaybackApi>().stop() }
        } finally {
            MediaSession.setAnonymousMode(previous)
        }
    }

    private fun record(event: String, value: JSONObject) {
        value.put("event", event)
        records.put(value)
        instrumentation.sendStatus(2, Bundle().apply { putString("anonymousFormation", value.toString()) })
    }

    private fun identity(): JSONObject {
        val snapshot = MediaSession.snapshot()
        val cookie = android.webkit.CookieManager.getInstance().getCookie("https://www.youtube.com").orEmpty()
        fun fingerprint(value: String?): String = value?.takeIf(String::isNotBlank)?.let {
            MessageDigest.getInstance("SHA-256").digest(it.toByteArray())
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 255) }
        }.orEmpty()
        return JSONObject().put("visitorHash", fingerprint(snapshot.visitor))
            .put("visitorCookieHash", fingerprint(snapshot.visitorCookie))
            .put("generation", snapshot.generation)
            .put("signedIn", snapshot.signedIn)
            .put("cookieHasVisitorInfo", cookie.contains("VISITOR_INFO1_LIVE="))
    }

    @Test fun formationCapture() = runBlocking(Dispatchers.IO) {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("network") == "1" && arguments.getString("anonymousFormation") == "1")
        assertTrue(!MediaSession.signedIn() && !MediaSession.privateBrowsing())
        val home = MediaHomeRepository(koin.get())
        val reporting = koin.get<PlaybackWatchReporting>()
        val baseline = home.loadHome("All", null)
        val before = identity()
        assertTrue("Home did not initialize the visitor identity", before.optString("visitorHash").isNotBlank())
        assertTrue("Home did not initialize the visitor cookie", before.optString("visitorCookieHash").isNotBlank())
        File(context.filesDir, "anonymous-formation-identity.json").writeText(before.toString())
        record("identity", before)

        fun homeIds(page: com.hhst.youtubelite.home.domain.HomePage): JSONObject {
            val videos = page.items.filterIsInstance<FeedItem.Video>()
            return JSONObject().put("count", videos.size)
                .put("videoIds", JSONArray(videos.take(48).map { it.id }))
                .put("channelIds", JSONArray(videos.take(48).map { it.channelId }))
        }
        record("home_baseline", homeIds(baseline))

        val query = arguments.getString("formationQuery")?.trim().orEmpty().ifBlank { "3blue1brown" }
        val search = MediaSearchRepository(koin.get()).search(query)
        val candidates = search.results.filter { it.id.isNotBlank() }.take(4)
        record("search", JSONObject().put("query", query).put("results", search.results.size)
            .put("picked", JSONArray(candidates.map { JSONObject().put("id", it.id)
                .put("channelId", it.channelId).put("title", it.title) })))
        assertTrue("Search returned no playable results for $query", candidates.isNotEmpty())

        val watched = JSONArray()
        for (video in candidates.take(2)) {
            val beforeReports = reporting.anonymousReportSignal.value
            val watchedMs = playAndWatch(video.id, 20_000)
            record("watched", JSONObject().put("videoId", video.id).put("channelId", video.channelId)
                .put("watchedMs", watchedMs)
                .put("reportedIntervals", reporting.anonymousReportSignal.value - beforeReports))
            watched.put(video.id)
        }
        delay(30_000) // server-side digest window before the home re-read
        val after = home.loadHome("All", null)
        record("home_after", homeIds(after).put("watched", watched))

        // The web client profile reads the same identity: the browser-comparison arm.
        val client = koin.get<com.hhst.youtubelite.data.media.MediaClient>()
        val webProbe = JSONObject()
        client.read { identity, http ->
            for (name in listOf("native", "web_profile")) {
                val query2 = com.liskovsoft.youtubeapi.videoinfo.V2.homeQuery(context = identity)
                val body = if (name == "web_profile") {
                    JSONObject(query2.body).apply {
                        getJSONObject("context").getJSONObject("client").put("clientName", "WEB")
                            .put("clientVersion", "2.20240701.00.00")
                    }.toString()
                } else query2.body
                val reply = http.execute(com.liskovsoft.youtubeapi.videoinfo.V2.HttpRequest(
                    "POST", query2.url, query2.headers, body))
                val page = runCatching {
                    com.liskovsoft.youtubeapi.videoinfo.V2.browsePage(reply.body)
                }.getOrNull()
                val ids = JSONArray()
                val channels = JSONArray()
                page?.items?.filter { it.id.isNotBlank() }?.take(48)?.forEach {
                    ids.put(it.id); channels.put(it.channelId.orEmpty())
                }
                webProbe.put(name, JSONObject().put("http", reply.status).put("count", ids.length())
                    .put("videoIds", ids).put("channelIds", channels)
                    .put("messages", JSONArray(page?.messages.orEmpty())))
            }
        }
        record("web_profile_home", webProbe)
        File(context.filesDir, "anonymous-formation.json").writeText(records.toString(2))
    }

    @Test fun identitySurvivesForcedRestart() = runBlocking(Dispatchers.IO) {
        assumeTrue(arguments.getString("network") == "1" && arguments.getString("anonymousFormation") == "1")
        assertTrue(!MediaSession.signedIn() && !MediaSession.privateBrowsing())
        val stored = File(context.filesDir, "anonymous-formation-identity.json").takeIf { it.exists() }
            ?.readText()?.let { JSONObject(it) }
        assumeTrue("Run formationCapture before the restart phase", stored != null)
        val captured = requireNotNull(stored)
        assertTrue("Capture the fingerprint before the restart phase", captured.optString("visitorHash").isNotBlank())
        assertTrue("Capture the cookie fingerprint before the restart phase", captured.optString("visitorCookieHash").isNotBlank())
        val current = identity()
        val result = JSONObject().put("stored", captured).put("current", current)
            .put("visitorSame", current.optString("visitorHash") == captured.optString("visitorHash") &&
                captured.optString("visitorHash").isNotEmpty())
            .put("cookiePairSame", current.optString("visitorCookieHash") == captured.optString("visitorCookieHash") &&
                captured.optString("visitorCookieHash").isNotEmpty())
        record("restart_identity", result)
        assertEquals("Visitor identity changed across the forced restart",
            captured.optString("visitorHash"), current.optString("visitorHash"))
        assertEquals("Visitor cookie changed across the forced restart",
            captured.optString("visitorCookieHash"), current.optString("visitorCookieHash"))
        File(context.filesDir, "anonymous-formation.json").writeText(records.toString(2))
    }

    private suspend fun playAndWatch(videoId: String, targetWatchMs: Long): Long {
        val reporting = koin.get<PlaybackWatchReporting>()
        val engine = koin.get<PlaybackApi>()
        val model = koin.get<PlayerViewModel>()
        var watchedMs = 0L
        var previousPosition: Long? = null
        var previousEpoch: Long? = null
        ActivityScenario.launch<WatchActivity>(WatchActivity.intent(context, videoId)).use {
            val deadline = SystemClock.elapsedRealtime() + 120_000
            var firstFrame = false
            while (SystemClock.elapsedRealtime() < deadline) {
                val state = engine.snapshot.value
                if (state.videoId == videoId) {
                    state.failureReport?.let { failure ->
                        File(context.filesDir, "formation-playback-failure.txt").writeText(failure.text)
                        throw AssertionError("Playback failed for $videoId")
                    }
                    firstFrame = firstFrame || state.firstFrameRendered
                    val position = model.positionState.value
                    if (state.firstFrameRendered && state.isPlaying) {
                        if (previousEpoch == state.discontinuityEpoch) {
                            val delta = previousPosition?.let { position - it } ?: 0
                            if (delta in 0..1_000) watchedMs += delta
                        }
                        previousPosition = position
                        previousEpoch = state.discontinuityEpoch
                    } else previousPosition = null
                    if (firstFrame && watchedMs >= targetWatchMs) break
                }
                delay(200)
            }
            assertTrue("No first frame for $videoId", firstFrame)
            assertTrue("Playback did not reach the requested watch duration for $videoId: $watchedMs ms",
                watchedMs >= targetWatchMs)
        }
        instrumentation.runOnMainSync { engine.stop() }
        withTimeout(15_000) { reporting.awaitPendingReports() }
        return watchedMs
    }
}
