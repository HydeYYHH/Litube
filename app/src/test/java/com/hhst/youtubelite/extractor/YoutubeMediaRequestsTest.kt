package com.hhst.youtubelite.extractor

import com.grack.nanojson.JsonObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.schabi.newpipe.extractor.services.youtube.streams.ClientProfile
import org.schabi.newpipe.extractor.services.youtube.streams.RequestPlan
import org.schabi.newpipe.extractor.services.youtube.streams.YoutubeSession

class YoutubeMediaRequestsTest {
    private fun session() = YoutubeSession("scope", YoutubeSession.Account.AUTHENTICATED, 0, null, "user",
        "user||", "visitor", "actual-browser-UA", 1, null, "test", JsonObject(), { "SECRET_COOKIE" })

    @Test fun get403RetriesPostOnceAndRetainsProfileUa() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(403))
            server.enqueue(MockResponse().setHeader("Content-Type", "video/mp4").setBody("media"))
            val policy = YoutubeMediaRequests({ true }, ExtractionDiagnostics())
            val plan = RequestPlan(session(), ClientProfile.WEB_SAFARI, RequestPlan.Protocol.HTTPS, RequestPlan.Range.QUERY, true)
            val url = server.url("/videoplayback?c=ANDROID").toString()
            policy.register(url, plan)
            val client = OkHttpClient.Builder().followRedirects(false).addInterceptor(policy.interceptor()).build()
            client.newCall(policy.build(url, plan, 100, 5, mapOf("Authorization" to "SECRET_AUTH", "Cookie" to "SECRET_COOKIE", "User-Agent" to "wrong"))).execute().use {
                assertEquals(200, it.code)
                assertEquals("media", it.body!!.string())
            }
            val get = server.takeRequest()
            val post = server.takeRequest()
            assertEquals("GET", get.method)
            assertEquals("POST", post.method)
            assertEquals("100-104", get.requestUrl!!.queryParameter("range"))
            assertEquals(plan.userAgent, get.getHeader("User-Agent"))
            assertNull(get.getHeader("Authorization"))
            assertNull(get.getHeader("Cookie"))
            assertArrayEquals(byteArrayOf(0x78, 0), post.body.readByteArray())
            assertFalse(policy.build(url, plan).header("User-Agent").orEmpty().contains("ANDROID"))
            client.dispatcher.executorService.shutdown()
        } finally { server.shutdown() }
    }

    @Test fun rangeIsBoundedAndIdentityChangeRejectsRequest() {
        var current = true
        val policy = YoutubeMediaRequests({ current }, ExtractionDiagnostics())
        val plan = RequestPlan(session(), ClientProfile.WEB, RequestPlan.Protocol.HTTPS, RequestPlan.Range.HEADER, true)
        val request = policy.build("https://rr.googlevideo.com/videoplayback", plan, 50, 50L * 1024 * 1024)
        assertEquals("bytes=50-${50 + 10L * 1024 * 1024 - 1}", request.header("Range"))
        assertNull(request.header("Cookie"))
        current = false
        assertThrows(IOException::class.java) { policy.build("https://rr.googlevideo.com/videoplayback", plan) }
    }

    @Test fun noCompatibilityRetryForRateLimitOrManifest() {
        val server = MockWebServer()
        server.start()
        try {
            val policy = YoutubeMediaRequests({ true }, ExtractionDiagnostics())
            val plan = RequestPlan(session(), ClientProfile.WEB, RequestPlan.Protocol.HLS, RequestPlan.Range.NONE, false)
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "30"))
            val client = OkHttpClient.Builder().addInterceptor(policy.interceptor()).build()
            client.newCall(policy.build(server.url("/manifest.m3u8").toString(), plan)).execute().use { assertEquals(429, it.code) }
            assertEquals(1, server.requestCount)
            client.dispatcher.executorService.shutdown()
        } finally { server.shutdown() }
    }

    @Test fun configurationParserHandlesBracesInsideStringsAndMergesSets() {
        val config = YoutubeSessionProvider.parseConfig("ytcfg.set({\"EVENT_ID\":\"{value}\"});ytcfg.set({\"SESSION_INDEX\":2});")
        assertEquals("{value}", config.getString("EVENT_ID"))
        assertEquals(2, config.getInt("SESSION_INDEX"))
    }

    @Test fun partialResponsesProvePositionAndObjectLength() {
        val policy = YoutubeMediaRequests({ true }, ExtractionDiagnostics())
        val plan = RequestPlan(session(), ClientProfile.WEB, RequestPlan.Protocol.HTTPS, RequestPlan.Range.HEADER, true, 200)
        val request = policy.build("https://rr.googlevideo.com/videoplayback", plan, 100, 5)
        val response = okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1)
            .code(206).message("Partial").header("Content-Range", "bytes 100-104/200")
            .body("media".toResponseBody()).build()
        response.use { assertEquals(YoutubeMediaRequests.Window(5, 200), policy.window(it, plan)) }
        response.newBuilder().header("Content-Range", "bytes 99-103/200").build().use {
            assertThrows(IOException::class.java) { policy.window(it, plan) }
        }
        response.newBuilder().header("Content-Range", "bytes 100-104/201").build().use {
            assertThrows(IOException::class.java) { policy.window(it, plan) }
        }
    }

    @Test fun queryCroppedViewDoesNotChangeWholeObjectIdentity() {
        val policy = YoutubeMediaRequests({ true }, ExtractionDiagnostics())
        val plan = RequestPlan(session(), ClientProfile.WEB, RequestPlan.Protocol.HTTPS, RequestPlan.Range.QUERY, true, 200)
        val response = okhttp3.Response.Builder().request(policy.build("https://rr.googlevideo.com/videoplayback", plan, 100, 5))
            .protocol(okhttp3.Protocol.HTTP_1_1).code(206).message("Partial")
            .header("Content-Range", "bytes 0-4/5").body("media".toResponseBody()).build()
        response.use { assertEquals(YoutubeMediaRequests.Window(5, 200), policy.window(it, plan)) }
        val tail = policy.build("https://rr.googlevideo.com/videoplayback", plan, 198, 10)
        assertEquals("198-199", tail.url.queryParameter("range"))
    }

    @Test fun htmlBodiesAndIgnoredRangesAreRejectedBeforeConsumption() {
        val server = MockWebServer()
        server.start()
        try {
            val policy = YoutubeMediaRequests({ true }, ExtractionDiagnostics())
            val plan = RequestPlan(session(), ClientProfile.WEB, RequestPlan.Protocol.HTTPS, RequestPlan.Range.HEADER, false)
            val client = OkHttpClient.Builder().addInterceptor(policy.interceptor()).build()
            server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("sign in"))
            assertThrows(IOException::class.java) { client.newCall(policy.build(server.url("/videoplayback").toString(), plan, 0, 5)).execute() }
            server.enqueue(MockResponse().setHeader("Content-Type", "video/mp4").setBody("entire-file"))
            assertThrows(IOException::class.java) { client.newCall(policy.build(server.url("/videoplayback").toString(), plan, 100, 5)).execute() }
            assertEquals(2, server.requestCount)
            client.dispatcher.executorService.shutdown()
        } finally { server.shutdown() }
    }

    @Test fun unrangedPlanKeepsTheWholeBodyWhenAHeaderRangeIsIgnored() {
        val server = MockWebServer()
        server.start()
        try {
            val policy = YoutubeMediaRequests({ true }, ExtractionDiagnostics())
            val plan = RequestPlan(session(), ClientProfile.WEB, RequestPlan.Protocol.HLS, RequestPlan.Range.NONE, false)
            val client = OkHttpClient.Builder().addInterceptor(policy.interceptor()).build()
            val url = server.url("/videoplayback/file/seg.mp4").toString()
            server.enqueue(MockResponse().setHeader("Content-Type", "video/mp4").setBody("0123456789"))
            client.newCall(policy.build(url, plan, 4, 20, range = RequestPlan.Range.HEADER)).execute().use {
                assertEquals(200, it.code)
                assertEquals(YoutubeMediaRequests.Window(10, null), policy.window(it, plan))
                assertEquals("0123456789", it.body!!.string())
            }
            server.enqueue(MockResponse().setHeader("Content-Type", "video/mp4").setBody("entire-file-too-big"))
            client.newCall(policy.build(url, plan, 4, 5, range = RequestPlan.Range.HEADER)).execute().use {
                assertEquals("entire-file-too-big", it.body!!.string())
            }
            server.enqueue(MockResponse().setHeader("Content-Type", "video/mp4").setChunkedBody("0123456789", 4))
            client.newCall(policy.build(url, plan, 4, 20, range = RequestPlan.Range.HEADER)).execute().use {
                assertEquals(-1L, it.body!!.contentLength())
                assertEquals("0123456789", it.body!!.string())
            }
            client.dispatcher.executorService.shutdown()
        } finally { server.shutdown() }
    }

    @Test fun serializedStreamCannotContainSessionCredentialsOrTokenHandles() {
        val plan = RequestPlan(session(), ClientProfile.WEB, RequestPlan.Protocol.HTTPS, RequestPlan.Range.HEADER, false)
        val stream = Stream().apply { formats = listOf(Format(url = "media", requestPlan = plan)); hlsRequestPlan = plan }
        val json = Gson().toJson(stream)
        assertFalse(json.contains("SECRET_COOKIE"))
        assertFalse(json.contains("requestPlan"))
        assertFalse(json.contains("session"))
    }

    @Test fun pageFlagsUseOnlyTheCurrentPlayerAndAccountLogo() {
        val html = "ytcfg.set({\"LOGGED_IN\":true});var ytInitialPlayerResponse={\"videoDetails\":{\"isLiveContent\":false}};" +
            "var ytInitialData={\"topbar\":{\"desktopTopbarRenderer\":{\"logo\":{\"topbarLogoRenderer\":{\"iconImage\":{\"iconType\":\"YOUTUBE_PREMIUM_LOGO\"}}}}},\"recommendation\":{\"isLiveContent\":true}};" +
            "window.ytAtN({\"R\":{\"bgChallenge\":{\"message\":\"a } brace\"}}});"
        val config = YoutubeSessionProvider.parseConfig(html)
        assertFalse(config.getBoolean("_IS_LIVE"))
        assertTrue(config.getBoolean("IS_PREMIUM"))
        assertEquals("a } brace", config.getObject("_BG_CHALLENGE").getString("message"))
    }

    @Test fun loosePageChallengeKeepsItsOwnEventAndDoesNotEvaluateJavascript() {
        val payload = "{R: '{\"bgChallenge\":{\"program\":\"a } brace\"}}',}" // Serialized R used by page challenges.
        val config = YoutubeSessionProvider.parseConfig("ytcfg.set({\"EVENT_ID\":\"paired-event\"});window.ytAtN($payload);throw Error('never execute');")
        assertEquals("paired-event", config.getString("EVENT_ID"))
        assertEquals(payload, config.getString("_BG_CHALLENGE_RAW"))
        assertFalse(config.has("_BG_CHALLENGE"))
    }

    @Test fun oversizedPageChallengeIsNotCaptured() {
        val payload = "{R:'" + "x".repeat(256 * 1024) + "'}"
        val config = YoutubeSessionProvider.parseConfig("window.ytAtN($payload)")
        assertFalse(config.has("_BG_CHALLENGE_RAW"))
    }

    @Test fun existingManifestBackupPreservesItsOwnSessionAndProfile() {
        val first = RequestPlan(session(), ClientProfile.WEB, RequestPlan.Protocol.HLS, RequestPlan.Range.NONE, false)
        val second = RequestPlan(session(), ClientProfile.WEB_SAFARI, RequestPlan.Protocol.HLS, RequestPlan.Range.NONE, false)
        val stream = Stream().apply {
            hlsUrl = "first"; hlsRequestPlan = first
            manifests = listOf(Manifest("first", RequestPlan.Protocol.HLS, first), Manifest("second", RequestPlan.Protocol.HLS, second))
        }
        val backup = stream.excluding(ClientProfile.WEB)
        assertEquals("second", backup.hlsUrl)
        assertSame(second, backup.hlsRequestPlan)
        assertEquals("first", stream.hlsUrl)
    }

    @Test fun changingSessionCancelsAnAlreadyOpenBody() {
        val server = MockWebServer()
        server.start()
        try {
            val current = java.util.concurrent.atomic.AtomicBoolean(true)
            val policy = YoutubeMediaRequests({ current.get() }, ExtractionDiagnostics())
            val plan = RequestPlan(session(), ClientProfile.WEB, RequestPlan.Protocol.HTTPS, RequestPlan.Range.QUERY, false)
            val client = OkHttpClient.Builder().addInterceptor(policy.interceptor()).build()
            server.enqueue(MockResponse().setBody("media").setBodyDelay(2, TimeUnit.SECONDS))
            val call = client.newCall(policy.build(server.url("/videoplayback").toString(), plan, 0, 5))
            call.execute().use { response ->
                current.set(false)
                val started = System.nanoTime()
                assertThrows(IOException::class.java) { response.body!!.string() }
                assertTrue(call.isCanceled())
                assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(3))
            }
            client.dispatcher.executorService.shutdown()
        } finally { server.shutdown() }
    }

    @Test fun expiredUrlIsRejectedBeforeAnHttpRequest() {
        val policy = YoutubeMediaRequests({ true }, ExtractionDiagnostics())
        val plan = RequestPlan(session(), ClientProfile.WEB, RequestPlan.Protocol.HTTPS, RequestPlan.Range.QUERY, false, 100, 1)
        val failure = assertThrows(IOException::class.java) { policy.build("https://rr.googlevideo.com/videoplayback", plan) }
        assertEquals("MEDIA_URL_EXPIRED", failure.message)
    }

    @Test fun accountIndexAcceptsThePageStringRepresentation() {
        val config = YoutubeSessionProvider.parseConfig("ytcfg.set({\"SESSION_INDEX\":\"2\"});")
        assertEquals(2, YoutubeSessionProvider.accountIndex(config))
        config["SESSION_INDEX"] = "invalid"
        assertThrows(IOException::class.java) { YoutubeSessionProvider.accountIndex(config) }
    }
}
