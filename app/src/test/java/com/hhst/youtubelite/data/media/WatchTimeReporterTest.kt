package com.hhst.youtubelite.data.media

import com.liskovsoft.youtubeapi.videoinfo.V2.AccessToken
import com.liskovsoft.youtubeapi.videoinfo.V2.Account
import com.liskovsoft.youtubeapi.videoinfo.V2.HttpClient
import com.liskovsoft.youtubeapi.videoinfo.V2.HttpReply
import com.liskovsoft.youtubeapi.videoinfo.V2.HttpRequest
import com.liskovsoft.youtubeapi.videoinfo.V2.PlayerTracking
import com.liskovsoft.youtubeapi.videoinfo.V2.SignInClient
import com.liskovsoft.youtubeapi.videoinfo.V2.WatchHistory
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import okhttp3.ResponseBody.Companion.toResponseBody

class WatchTimeReporterTest {

    private val requests = ConcurrentLinkedQueue<HttpRequest>()
    private val http = object : HttpClient {
        override fun execute(request: HttpRequest): HttpReply {
            requests.add(request)
            return HttpReply(200, "", emptyMap())
        }
    }
    private val tracking = PlayerTracking(
        "https://www.youtube.com/api/stats/playback?ei=e",
        "https://www.youtube.com/api/stats/watchtime?ei=e")

    @Before fun setUp() {
        MediaSession.initialize(MemoryAuthStore())
        MediaSession.completeSignIn(SignInClient("id", "secret", "device"),
            AccessToken("access", "refresh", 4_000_000_000_000),
            listOf(Account("owner", "Owner", null, null, selected = true, disabled = false, pageId = null)))
        requests.clear()
    }

    @After fun tearDown() = MediaSession.initialize(MemoryAuthStore())

    private fun reporter(): WatchTimeReporter {
        val client = MediaClient(okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            requests.add(HttpRequest(chain.request().method, chain.request().url.toString()))
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200).message("OK").body("".toResponseBody()).build()
        }.build(), { MediaSession.snapshot() })
        return WatchTimeReporter(client, CoroutineScope(Dispatchers.Default), WatchHistory(http, "cpn"))
    }

    private fun awaitRequests(count: Int) {
        val deadline = System.currentTimeMillis() + 5_000
        while (requests.size < count && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(count, requests.size)
    }

    @Test fun ticksReportWhilePlayingAndThrottleToTenSecondsOfProgress() {
        val reporter = reporter()
        reporter.watch("video", tracking)
        reporter.tick(0, 100_000, playing = true)
        awaitRequests(2) // first update creates the watch record, then reports watch time
        reporter.tick(5_000, 100_000, playing = true) // throttled
        Thread.sleep(300)
        assertEquals(2, requests.size)
        reporter.tick(11_000, 100_000, playing = true)
        awaitRequests(3)
    }

    @Test fun aBackwardSeekStartsANewRangeImmediately() {
        val reporter = reporter()
        reporter.watch("video", tracking)
        reporter.tick(20_000, 100_000, playing = true)
        awaitRequests(2)
        reporter.tick(2_000, 100_000, playing = true)
        awaitRequests(3)
    }

    @Test fun pausedOrStoppedPlaybackDoesNotReport() {
        val reporter = reporter()
        reporter.watch("video", tracking)
        reporter.tick(5_000, 100_000, playing = false)
        Thread.sleep(300)
        assertEquals(0, requests.size)
        reporter.tick(5_000, 100_000, playing = true)
        awaitRequests(2)
        reporter.stop()
        requests.clear()
        reporter.tick(20_000, 100_000, playing = true)
        Thread.sleep(300)
        assertEquals(0, requests.size)
    }

    @Test fun anonymousSessionsDoNotReport() {
        MediaSession.initialize(MemoryAuthStore()) // signed out
        val reporter = reporter()
        reporter.watch("video", tracking)
        reporter.tick(5_000, 100_000, playing = true)
        Thread.sleep(300)
        assertEquals(0, requests.size)
    }

    @Test fun missingTrackingDisablesReporting() {
        val reporter = reporter()
        reporter.watch("video", null)
        reporter.tick(5_000, 100_000, playing = true)
        Thread.sleep(300)
        assertEquals(0, requests.size)
    }

    @Test fun progressFromAnotherVideoCannotBeAttributedToThePreviousOne() {
        val reporter = reporter()
        reporter.watch("previous", tracking)
        reporter.tick(10_000, 100_000, playing = true, playingVideoId = "next")
        Thread.sleep(300)
        assertTrue(requests.isEmpty())
    }
}
