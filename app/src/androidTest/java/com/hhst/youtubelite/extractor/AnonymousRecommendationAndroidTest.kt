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
import com.hhst.youtubelite.player.engine.LoopMode
import com.hhst.youtubelite.watch.WatchActivity
import com.liskovsoft.youtubeapi.videoinfo.V2.PlayerClient
import com.liskovsoft.youtubeapi.videoinfo.V2.HttpRequest
import com.liskovsoft.youtubeapi.videoinfo.V2.playerAnswer
import com.liskovsoft.youtubeapi.videoinfo.V2.playerQuery
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
    @Test fun documentBootstrapComparesHomeWithoutChangingStoredIdentity() = runBlocking(Dispatchers.IO) {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("network") == "1" && arguments.getString("anonymousBootstrap") == "1")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val before = MediaSession.snapshot()
        val records = JSONArray()
        GlobalContext.get().get<MediaClient>().read { _, http ->
            val anonymous = com.liskovsoft.youtubeapi.videoinfo.V2.RequestContext(
                visitor = before.visitor, visitorCookie = before.visitorCookie)
            val profiles = listOf(
                Triple("tv", "https://www.youtube.com/tv?hl=en&gl=US",
                    playerQuery(PlayerClient.TV, "context", null).headers.getValue("User-Agent")),
                Triple("mobile", "https://m.youtube.com/?hl=en&persist_app=1&app=m",
                    com.hhst.youtubelite.core.Constants.userAgent()),
            )
            for ((name, url, agent) in profiles) {
                for ((scope, storedCookie) in listOf("stored" to anonymous.visitorCookie, "fresh" to null)) {
                    val reply = http.execute(HttpRequest("GET", url, mapOf(
                        "User-Agent" to agent, "Cookie" to storedCookie.orEmpty())))
                    val visitor = Regex("\"(?:VISITOR_DATA|visitorData)\"\\s*:\\s*\"([^\"]{1,4096})\"")
                        .find(reply.body)?.groupValues?.get(1)
                    val cookies = com.hhst.youtubelite.data.media.anonymousVisitorCookie(
                        reply.header("Set-Cookie").orEmpty(), storedCookie)
                    val row = JSONObject().put("profile", name).put("cookieScope", scope).put("documentHttp", reply.status)
                        .put("hasVisitor", visitor != null).put("hasCookiePair", cookies != null)
                        .put("sameVisitor", visitor == anonymous.visitor)
                    if (visitor != null && reply.status in 200..299) {
                        val query = com.liskovsoft.youtubeapi.videoinfo.V2.homeQuery(context =
                            anonymous.copy(visitor = visitor, visitorCookie = cookies))
                        val response = http.execute(HttpRequest("POST", query.url, query.headers, query.body))
                        val page = com.liskovsoft.youtubeapi.videoinfo.V2.browsePage(response.body)
                        row.put("homeHttp", response.status).put("items", page.items.size)
                            .put("messages", JSONArray(page.messages))
                    }
                    records.put(row)
                    instrumentation.sendStatus(2, Bundle().apply { putString("anonymousBootstrap", row.toString()) })
                }
            }
        }
        val after = MediaSession.snapshot()
        assertEquals(before, after)
        File(instrumentation.targetContext.filesDir, "anonymous-bootstrap-comparison.json").writeText(records.toString(2))
    }

    @Test fun homeProfilesRecordRawAndParsedAvailability() = runBlocking(Dispatchers.IO) {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("network") == "1" && arguments.getString("anonymousReporting") == "1")
        assumeTrue(!MediaSession.signedIn() && !MediaSession.privateBrowsing())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val client = GlobalContext.get().get<MediaClient>()
        val probeVideo = requireNotNull(VideoId.parse(arguments.getString("videoId", "aqz-KE-bpKQ")))
        MediaHomeRepository(client).loadHome("All", null)
        val records = JSONArray()
        client.read { identity, http ->
            val visitor = requireNotNull(identity.visitor)
            fun probe(name: String, query: com.liskovsoft.youtubeapi.videoinfo.V2.PlayerQuery) {
                val reply = http.execute(HttpRequest("POST", query.url, query.headers, query.body))
                val root = runCatching { JSONObject(reply.body) }.getOrNull()
                val counts = mutableMapOf<String, Int>()
                val videos = mutableSetOf<String>()
                fun visit(value: Any?) {
                    when (value) {
                        is JSONObject -> value.keys().forEach { key ->
                            val child = value.opt(key)
                            if (key.endsWith("Renderer")) counts[key] = (counts[key] ?: 0) + 1
                            if (key == "videoId" && child is String) videos += child
                            visit(child)
                        }
                        is JSONArray -> for (i in 0 until value.length()) visit(value.opt(i))
                    }
                }
                visit(root)
                val page = com.liskovsoft.youtubeapi.videoinfo.V2.browsePage(reply.body)
                val result = JSONObject().put("profile", name).put("http", reply.status)
                    .put("rawVideoIds", videos.size).put("parsedItems", page.items.size)
                    .put("messages", JSONArray(page.messages)).put("hasContinuation", page.nextKey != null)
                    .put("renderers", JSONObject(counts as Map<*, *>))
                records.put(result)
                instrumentation.sendStatus(2, Bundle().apply { putString("anonymousHomeProbe", result.toString()) })
            }
            val tv = com.liskovsoft.youtubeapi.videoinfo.V2.homeQuery(context = identity)
            probe("tv_header", tv)
            val body = JSONObject(tv.body).apply { getJSONObject("context").getJSONObject("client").put("visitorData", visitor) }
            probe("tv_body", tv.copy(body = body.toString()))
            probe("tv_body_no_cookie", tv.copy(body = body.toString(), headers = tv.headers.filterKeys { !it.equals("Cookie", true) }))
            val consent = "SOCS=CAISEwgDEgk5NTA1MzE4OTQaAmVuIAEaBgiA7frSBg"
            val cookie = identity.visitorCookie.orEmpty().split(';').map(String::trim)
                .filterNot { it.startsWith("SOCS=") }.plus(consent).joinToString("; ")
            probe("tv_accepted_consent", tv.copy(body = body.toString(), headers = tv.headers + ("Cookie" to cookie)))
            val full = JSONObject(body.toString())
            full.getJSONObject("context").apply {
                getJSONObject("client").apply {
                    put("clientScreen", "WATCH").put("acceptLanguage", "en").put("acceptRegion", "US")
                    put("utcOffsetMinutes", 0).put("webpSupport", false).put("animatedWebpSupport", true)
                    put("tvAppInfo", JSONObject().put("appQuality", "TV_APP_QUALITY_FULL_ANIMATION").put("zylonLeftNav", true))
                }
                put("user", JSONObject().put("enableSafetyMode", false).put("lockedSafetyMode", false))
            }
            full.put("racyCheckOk", true).put("contentCheckOk", true)
            probe("tv_full_context", tv.copy(body = full.toString(), headers = tv.headers +
                mapOf("Cookie" to cookie, "Referer" to "https://www.youtube.com/tv")))
            val complete = JSONObject(full.toString())
            val tvAgent = tv.headers.getValue("User-Agent")
            complete.getJSONObject("context").getJSONObject("client").apply {
                put("userAgent", tvAgent).put("browserName", "Cobalt")
                put("browserVersion", tvAgent.substringAfter("Cobalt/").substringBefore(' '))
            }
            probe("tv_complete_client", tv.copy(body = complete.toString(), headers = tv.headers +
                mapOf("Cookie" to cookie, "Referer" to "https://www.youtube.com/tv")))
            complete.getJSONObject("context").getJSONObject("client").getJSONObject("tvAppInfo")
                .put("appQuality", "TV_APP_QUALITY_LIMITED_ANIMATION")
            probe("tv_limited_animation", tv.copy(body = complete.toString(), headers = tv.headers +
                mapOf("Cookie" to cookie, "Referer" to "https://www.youtube.com/tv")))
            probe("web_home", com.liskovsoft.youtubeapi.videoinfo.V2.browseQuery("FEwhat_to_watch", identity))
            val document = http.execute(HttpRequest("GET", "https://www.youtube.com/tv?hl=en&gl=US",
                mapOf("Cookie" to identity.visitorCookie.orEmpty(), "User-Agent" to "Mozilla/5.0")))
            val documentVisitor = Regex("\"(?:VISITOR_DATA|visitorData)\"\\s*:\\s*\"([^\"]+)\"")
                .find(document.body)?.groupValues?.get(1)
            val documentResult = JSONObject().put("profile", "tv_document").put("http", document.status)
                .put("hasVisitor", documentVisitor != null)
                .put("length", document.body.length).put("hasConfig", document.body.contains("ytcfg"))
                .put("consentPage", document.body.contains("consent.youtube.com"))
            records.put(documentResult)
            instrumentation.sendStatus(2, Bundle().apply { putString("anonymousHomeProbe", documentResult.toString()) })
            if (documentVisitor != null) {
                body.getJSONObject("context").getJSONObject("client").put("visitorData", documentVisitor)
                probe("tv_document_visitor", tv.copy(body = body.toString(),
                    headers = tv.headers + ("X-Goog-Visitor-Id" to documentVisitor)))
            }
            val cookieId = identity.visitorCookie.orEmpty().split(';').map(String::trim)
                .firstOrNull { it.startsWith("VISITOR_INFO1_LIVE=") }?.substringAfter('=')
            fun visitorId(encoded: String?): String? = runCatching {
                val decoded = android.util.Base64.decode(java.net.URLDecoder.decode(encoded.orEmpty(), "UTF-8"),
                    android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP)
                if (decoded.size < 2 || decoded[0].toInt() != 10) null
                else decoded.copyOfRange(2, 2 + decoded[1].toInt()).toString(Charsets.UTF_8)
            }.getOrNull()
            for (profile in listOf(PlayerClient.TV, PlayerClient.WEB)) {
                val agent = playerQuery(profile, "YQHsXMglC9A", null).headers.getValue("User-Agent")
                val page = http.execute(HttpRequest("GET", "https://www.youtube.com/tv?hl=en&gl=US",
                    mapOf("Cookie" to cookie, "User-Agent" to agent)))
                val pageVisitor = Regex("\"(?:VISITOR_DATA|visitorData)\"\\s*:\\s*\"([^\"]+)\"")
                    .find(page.body)?.groupValues?.get(1)
                val result = JSONObject().put("profile", "document_${profile.name}").put("http", page.status)
                    .put("length", page.body.length).put("hasVisitor", pageVisitor != null)
                    .put("hasConfig", page.body.contains("ytcfg"))
                    .put("visitorMatchesCookie", pageVisitor != null && visitorId(pageVisitor) == cookieId)
                    .put("responseHasCookieIdentity", page.header("Set-Cookie").orEmpty().contains("VISITOR_INFO1_LIVE="))
                records.put(result)
                instrumentation.sendStatus(2, Bundle().apply { putString("anonymousHomeProbe", result.toString()) })
                if (pageVisitor != null) {
                    full.getJSONObject("context").getJSONObject("client").put("visitorData", pageVisitor)
                    probe("tv_document_${profile.name}_identity", tv.copy(body = full.toString(),
                        headers = tv.headers + mapOf("Cookie" to cookie, "X-Goog-Visitor-Id" to pageVisitor)))
                }
            }
            for (profile in listOf(PlayerClient.TV, PlayerClient.WEB)) {
                for (bodyVisitor in listOf(false, true)) {
                    val query = playerQuery(profile, probeVideo, identity.visitor)
                    val payload = JSONObject(query.body)
                    if (bodyVisitor) payload.getJSONObject("context").getJSONObject("client").put("visitorData", visitor)
                    val response = http.execute(HttpRequest("POST", query.url,
                        query.headers + ("Cookie" to identity.visitorCookie.orEmpty()), payload.toString()))
                    val root = JSONObject(response.body)
                    val tracking = root.optJSONObject("playbackTracking")?.optJSONObject("videostatsPlaybackUrl")?.optString("baseUrl")
                    val trackingUrl = tracking?.toHttpUrlOrNull()
                    val watchUrl = root.optJSONObject("playbackTracking")?.optJSONObject("videostatsWatchtimeUrl")
                        ?.optString("baseUrl")?.toHttpUrlOrNull()
                    val result = JSONObject().put("profile", "player_${profile.name}_$bodyVisitor").put("http", response.status)
                        .put("video", probeVideo)
                        .put("status", root.optJSONObject("playabilityStatus")?.optString("status"))
                        .put("visitorMatchesCookie", visitorId(root.optJSONObject("responseContext")?.optString("visitorData")) == cookieId)
                        .put("requestVisitorMatchesCookie", visitorId(visitor) == cookieId)
                        .put("hasCookieIdentity", !cookieId.isNullOrBlank()).put("hasDecodableVisitorIdentity", visitorId(visitor) != null)
                        .put("hasTracking", !tracking.isNullOrBlank())
                        .put("playbackTrackingKeys", JSONObject(listOf("ei", "vm", "of", "c").associateWith {
                            !trackingUrl?.queryParameter(it).isNullOrBlank()
                        }))
                        .put("watchTimeTrackingKeys", JSONObject(listOf("ei", "vm", "of", "c").associateWith {
                            !watchUrl?.queryParameter(it).isNullOrBlank()
                        }))
                    records.put(result)
                    instrumentation.sendStatus(2, Bundle().apply { putString("anonymousHomeProbe", result.toString()) })
                }
            }
        }
        File(instrumentation.targetContext.filesDir, "anonymous-home-profiles.json").writeText(records.toString(2))
        assertTrue(records.length() >= 4)
    }

    @Test fun realPlaybackReportsAnIntervalAndRecordsHomeAvailability() = runBlocking(Dispatchers.IO) {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("network") == "1" && arguments.getString("anonymousReporting") == "1")
        assumeTrue(!MediaSession.privateBrowsing())
        val previousMode = MediaSession.anonymousMode()
        val temporaryAnonymous = arguments.getString("temporaryAnonymous") == "1"
        assumeTrue(!MediaSession.signedIn() || temporaryAnonymous)
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
        var restoreLoop: (() -> Unit)? = null
        try {
            if (temporaryAnonymous) MediaSession.setAnonymousMode(true)
            readHome("home_before")
            val identity = MediaSession.snapshot()
            val visitor = requireNotNull(identity.visitor)
            val fingerprint = MessageDigest.getInstance("SHA-256").digest(visitor.toByteArray())
                .take(8).joinToString("") { "%02x".format(it) }
            record("identity", JSONObject().put("generation", identity.generation).put("visitorHash", fingerprint)
                .put("hasVisitorCookie", !identity.visitorCookie.isNullOrBlank()))
            assertTrue("Anonymous identity has no visitor cookie", !identity.visitorCookie.isNullOrBlank())
            val before = reporting.anonymousReportSignal.value
            val video = requireNotNull(VideoId.parse(arguments.getString("videoId", "aqz-KE-bpKQ")))
            scenario = ActivityScenario.launch(WatchActivity.intent(context, video))
            lateinit var engine: PlaybackApi
            instrumentation.runOnMainSync {
                model = koin.get()
                engine = koin.get()
                val previousLoop = engine.snapshot.value.loopMode
                restoreLoop = { engine.setLoopMode(previousLoop) }
                engine.setLoopMode(LoopMode.PAUSE_AT_END)
            }
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
                val watchedEnough = if (arguments.getString("completeShort") == "1")
                    state.firstFrameRendered && state.ended
                else state.positionMs >= 20_000
                if (reporting.anonymousReportSignal.value > before && watchedEnough) break
                delay(250)
            }
            reporting.awaitPendingReports()
            val delivered = reporting.anonymousReportSignal.value - before
            record("reporting_result", JSONObject().put("watchedIntervals", delivered))
            assertTrue("No watched interval was acknowledged", delivered > 0)
            val finalState = engine.snapshot.value
            record("playback_result", JSONObject().put("video", video).put("ended", finalState.ended)
                .put("positionMs", finalState.positionMs).put("durationMs", finalState.durationMs)
                .put("firstFrame", finalState.firstFrameRendered))
            assertTrue("Playback did not reach the observed interval", finalState.firstFrameRendered &&
                if (arguments.getString("completeShort") == "1") finalState.ended
                else finalState.positionMs >= 20_000)
            assertEquals("Visitor changed during playback", visitor, MediaSession.snapshot().visitor)
            assertEquals("Identity changed during playback", identity.generation, MediaSession.snapshot().generation)
            readHome("home_after_report")
            delay(10_000)
            readHome("home_after_wait")
        } finally {
            instrumentation.runOnMainSync { model?.stopOnExit(); restoreLoop?.invoke() }
            scenario?.close()
            if (temporaryAnonymous) MediaSession.setAnonymousMode(previousMode)
            File(context.filesDir, "anonymous-recommendation.json").writeText(records.toString(2))
        }
    }
}
