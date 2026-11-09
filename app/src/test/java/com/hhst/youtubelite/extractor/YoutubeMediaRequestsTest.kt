package com.hhst.youtubelite.extractor

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class YoutubeMediaRequestsTest {
    private fun session() = YoutubeWebSession("scope", WebAccount.AUTHENTICATED, 0, null, "user",
        "user||", "visitor", "actual-browser-UA", 1, null, "test", JsonObject(emptyMap()), { "SECRET_COOKIE" })

    @Test fun anAnnouncedReadinessHoldDoesNotIssueAnExtraPostAfter403() {
        val server = MockWebServer().apply { enqueue(MockResponse().setResponseCode(403)); start() }
        val policy = YoutubeMediaRequests({ true }, ExtractionDiagnostics())
        val client = OkHttpClient.Builder().addInterceptor(policy.interceptor()).build()
        try {
            val plan = MediaRequestPlan(session(), StreamClient.VISIONOS, MediaProtocol.HTTPS,
                MediaRange.QUERY, allowPostFallback = true, availableAfterMillis = 5_000)
            client.newCall(policy.build(server.url("/videoplayback").toString(), plan)).execute().use {
                assertEquals(403, it.code)
            }
            assertEquals(1, server.requestCount)
        } finally {
            client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
            server.shutdown()
        }
    }

    @Test fun get403RetriesPostOnceAndRetainsProfileUa() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(403))
            server.enqueue(MockResponse().setHeader("Content-Type", "video/mp4").setBody("media"))
            val policy = YoutubeMediaRequests({ true }, ExtractionDiagnostics())
            val plan = MediaRequestPlan(session(), StreamClient.WEB_SAFARI, MediaProtocol.HTTPS, MediaRange.QUERY, true)
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
            assertEquals("POST", policy.build(url, plan).method)
            assertFalse(policy.build(url, plan).header("User-Agent").orEmpty().contains("ANDROID"))
            client.dispatcher.executorService.shutdown()
        } finally { server.shutdown() }
    }

    @Test fun rangeIsBoundedAndIdentityChangeRejectsRequest() {
        var current = true
        val policy = YoutubeMediaRequests({ current }, ExtractionDiagnostics())
        val plan = MediaRequestPlan(session(), StreamClient.WEB, MediaProtocol.HTTPS, MediaRange.HEADER, true)
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
            val plan = MediaRequestPlan(session(), StreamClient.WEB, MediaProtocol.HLS, MediaRange.NONE, false)
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "30"))
            val client = OkHttpClient.Builder().addInterceptor(policy.interceptor()).build()
            client.newCall(policy.build(server.url("/manifest.m3u8").toString(), plan)).execute().use { assertEquals(429, it.code) }
            assertEquals(1, server.requestCount)
            client.dispatcher.executorService.shutdown()
        } finally { server.shutdown() }
    }

    @Test fun child403KeepsThePlanActuallyUsedInsteadOfAPrefetchedClient() {
        val server = MockWebServer()
        server.start()
        val policy = YoutubeMediaRequests({ true }, ExtractionDiagnostics())
        val actual = MediaRequestPlan(session(), StreamClient.VISIONOS, MediaProtocol.HLS, MediaRange.NONE, false)
        val prefetched = MediaRequestPlan(session(), StreamClient.WEB_SAFARI, MediaProtocol.HLS, MediaRange.NONE, false)
        val client = OkHttpClient.Builder().addInterceptor(policy.interceptor()).build()
        try {
            val url = server.url("/videoplayback/itag/95/segment.ts").toString()
            policy.register(url, prefetched)
            server.enqueue(MockResponse().setResponseCode(403))
            client.newCall(policy.build(url, actual)).execute().use { assertEquals(403, it.code) }
            assertSame(actual, policy.plan("$url?range=0-10&rn=2"))
            assertEquals(1, server.requestCount)
        } finally {
            client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
            server.shutdown()
        }
    }

    @Test fun staleOrUnplannedRequestsCannotInventAClientBinding() {
        val server = MockWebServer()
        server.start()
        var current = true
        val policy = YoutubeMediaRequests({ current }, ExtractionDiagnostics())
        val plan = MediaRequestPlan(session(), StreamClient.WEB, MediaProtocol.HLS, MediaRange.NONE, false)
        val client = OkHttpClient.Builder().addInterceptor(policy.interceptor()).build()
        try {
            val url = server.url("/unknown.ts").toString()
            val stale = policy.build(url, plan)
            current = false
            assertThrows(IOException::class.java) { client.newCall(stale).execute() }
            assertNull(policy.plan(url))
            assertEquals(0, server.requestCount)
            server.enqueue(MockResponse().setResponseCode(403))
            client.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { assertEquals(403, it.code) }
            assertNull(policy.plan(url))
        } finally {
            client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
            server.shutdown()
        }
    }

    @Test fun configurationParserHandlesBracesInsideStringsAndMergesSets() {
        val config = YoutubeSessionProvider.parseConfig("ytcfg.set({\"EVENT_ID\":\"{value}\"});ytcfg.set({\"SESSION_INDEX\":2});")
        assertEquals("{value}", config.text("EVENT_ID"))
        assertEquals(2, config.raw("SESSION_INDEX")?.toInt())
    }

    @Test fun partialResponsesProvePositionAndObjectLength() {
        val policy = YoutubeMediaRequests({ true }, ExtractionDiagnostics())
        val plan = MediaRequestPlan(session(), StreamClient.WEB, MediaProtocol.HTTPS, MediaRange.HEADER, true, 200)
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
        val plan = MediaRequestPlan(session(), StreamClient.WEB, MediaProtocol.HTTPS, MediaRange.QUERY, true, 200)
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
            val plan = MediaRequestPlan(session(), StreamClient.WEB, MediaProtocol.HTTPS, MediaRange.HEADER, false)
            val client = OkHttpClient.Builder().addInterceptor(policy.interceptor()).build()
            server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("sign in"))
            assertThrows(IOException::class.java) { client.newCall(policy.build(server.url("/videoplayback").toString(), plan, 0, 5)).execute() }
            server.enqueue(MockResponse().setHeader("Content-Type", "video/mp4").setBody("entire-file"))
            assertThrows(IOException::class.java) { client.newCall(policy.build(server.url("/videoplayback").toString(), plan, 100, 5)).execute() }
            assertEquals(2, server.requestCount)
            client.dispatcher.executorService.shutdown()
        } finally { server.shutdown() }
    }

    @Test fun rejectedPostResponsesNeverBecomeALearnedCompatibilityMethod() {
        for (contentType in listOf("text/html; charset=utf-8", "application/json")) {
            val server = MockWebServer()
            server.start()
            val policy = YoutubeMediaRequests({ true }, ExtractionDiagnostics())
            val plan = MediaRequestPlan(session(), StreamClient.WEB, MediaProtocol.HTTPS, MediaRange.HEADER, true)
            val client = OkHttpClient.Builder().addInterceptor(policy.interceptor()).build()
            try {
                val url = server.url("/videoplayback").toString()
                server.enqueue(MockResponse().setResponseCode(403))
                server.enqueue(MockResponse().setHeader("Content-Type", contentType).setBody("error"))
                val failure = assertThrows(IOException::class.java) {
                    client.newCall(policy.build(url, plan, 0, 5)).execute()
                }
                assertEquals("MEDIA_ERROR_BODY", failure.message)
                val responseFailure = failure as InvalidMediaResponseException
                assertEquals(200, responseFailure.statusCode)
                assertEquals(contentType.substringBefore(';'), responseFailure.contentType)
                assertEquals("POST", responseFailure.method)
                assertEquals(StreamClient.WEB, responseFailure.client)
                assertEquals("GET", server.takeRequest().method)
                assertEquals("POST", server.takeRequest().method)
                assertEquals("GET", policy.build(url, plan, 0, 5).method)
                server.enqueue(MockResponse().setHeader("Content-Type", "video/mp4").setBody("media"))
                client.newCall(policy.build(url, plan, 0, 5)).execute().use {
                    assertEquals("media", requireNotNull(it.body).string())
                }
                assertEquals("GET", server.takeRequest().method)
            } finally {
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
                server.shutdown()
            }
        }
    }

    @Test fun unrangedPlanKeepsTheWholeBodyWhenAHeaderRangeIsIgnored() {
        val server = MockWebServer()
        server.start()
        try {
            val policy = YoutubeMediaRequests({ true }, ExtractionDiagnostics())
            val plan = MediaRequestPlan(session(), StreamClient.WEB, MediaProtocol.HLS, MediaRange.NONE, false)
            val client = OkHttpClient.Builder().addInterceptor(policy.interceptor()).build()
            val url = server.url("/videoplayback/file/seg.mp4").toString()
            server.enqueue(MockResponse().setHeader("Content-Type", "video/mp4").setBody("0123456789"))
            client.newCall(policy.build(url, plan, 4, 20, range = MediaRange.HEADER)).execute().use {
                assertEquals(200, it.code)
                assertEquals(YoutubeMediaRequests.Window(10, null), policy.window(it, plan))
                assertEquals("0123456789", it.body!!.string())
            }
            server.enqueue(MockResponse().setHeader("Content-Type", "video/mp4").setBody("entire-file-too-big"))
            client.newCall(policy.build(url, plan, 4, 5, range = MediaRange.HEADER)).execute().use {
                assertEquals("entire-file-too-big", it.body!!.string())
            }
            server.enqueue(MockResponse().setHeader("Content-Type", "video/mp4").setChunkedBody("0123456789", 4))
            client.newCall(policy.build(url, plan, 4, 20, range = MediaRange.HEADER)).execute().use {
                assertEquals(-1L, it.body!!.contentLength())
                assertEquals("0123456789", it.body!!.string())
            }
            client.dispatcher.executorService.shutdown()
        } finally { server.shutdown() }
    }

    @Test fun serializedStreamCannotContainSessionCredentialsOrTokenHandles() {
        val plan = MediaRequestPlan(session(), StreamClient.WEB, MediaProtocol.HTTPS, MediaRange.HEADER, false)
        val stream = Stream().apply { formats = listOf(Format(url = "media", requestPlan = plan)); hlsRequestPlan = plan }
        val json = kotlinx.serialization.json.Json { encodeDefaults = true }.encodeToString(
            kotlinx.serialization.serializer(Stream::class.java), stream)
        assertFalse(json.contains("SECRET_COOKIE"))
        assertFalse(json.contains("requestPlan"))
        assertFalse(json.contains("session"))
    }

    @Test fun pageFlagsUseOnlyTheCurrentPlayerAndAccountLogo() {
        val html = "ytcfg.set({\"LOGGED_IN\":true});var ytInitialPlayerResponse={\"videoDetails\":{\"isLiveContent\":false}};" +
            "var ytInitialData={\"topbar\":{\"desktopTopbarRenderer\":{\"logo\":{\"topbarLogoRenderer\":{\"iconImage\":{\"iconType\":\"YOUTUBE_PREMIUM_LOGO\"}}}}},\"recommendation\":{\"isLiveContent\":true}};" +
            "window.ytAtN({\"R\":{\"bgChallenge\":{\"message\":\"a } brace\"}}});"
        val config = YoutubeSessionProvider.parseConfig(html)
        assertFalse(config.flag("_IS_LIVE", false))
        assertTrue(config.flag("IS_PREMIUM", false))
        assertEquals("a } brace", config.obj("_BG_CHALLENGE").text("message"))
    }

    @Test fun loosePageChallengeKeepsItsOwnEventAndDoesNotEvaluateJavascript() {
        val payload = "{R: '{\"bgChallenge\":{\"program\":\"a } brace\"}}',}" // Serialized R used by page challenges.
        val config = YoutubeSessionProvider.parseConfig("ytcfg.set({\"EVENT_ID\":\"paired-event\"});window.ytAtN($payload);throw Error('never execute');")
        assertEquals("paired-event", config.text("EVENT_ID"))
        assertEquals(payload, config.text("_BG_CHALLENGE_RAW"))
        assertFalse(config.containsKey("_BG_CHALLENGE"))
    }

    @Test fun oversizedPageChallengeIsNotCaptured() {
        val payload = "{R:'" + "x".repeat(256 * 1024) + "'}"
        val config = YoutubeSessionProvider.parseConfig("window.ytAtN($payload)")
        assertFalse(config.containsKey("_BG_CHALLENGE_RAW"))
    }

    @Test fun existingManifestBackupPreservesItsOwnSessionAndProfile() {
        val first = MediaRequestPlan(session(), StreamClient.WEB, MediaProtocol.HLS, MediaRange.NONE, false)
        val second = MediaRequestPlan(session(), StreamClient.WEB_SAFARI, MediaProtocol.HLS, MediaRange.NONE, false)
        val stream = Stream().apply {
            hlsUrl = "first"; hlsRequestPlan = first
            manifests = listOf(Manifest("first", MediaProtocol.HLS, first), Manifest("second", MediaProtocol.HLS, second))
        }
        val backup = stream.excluding(StreamClient.WEB)
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
            val plan = MediaRequestPlan(session(), StreamClient.WEB, MediaProtocol.HTTPS, MediaRange.QUERY, false)
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
        val plan = MediaRequestPlan(session(), StreamClient.WEB, MediaProtocol.HTTPS, MediaRange.QUERY, false, 100, 1)
        val failure = assertThrows(IOException::class.java) { policy.build("https://rr.googlevideo.com/videoplayback", plan) }
        assertEquals("MEDIA_URL_EXPIRED", failure.message)
    }

    @Test fun accountIndexAcceptsThePageStringRepresentation() {
        val config = YoutubeSessionProvider.parseConfig("ytcfg.set({\"SESSION_INDEX\":\"2\"});")
        assertEquals(2, YoutubeSessionProvider.accountIndex(config))
        val invalid = JsonObject(LinkedHashMap(config).apply { put("SESSION_INDEX", JsonPrimitive("invalid")) })
        assertThrows(IOException::class.java) { YoutubeSessionProvider.accountIndex(invalid) }
    }
}
