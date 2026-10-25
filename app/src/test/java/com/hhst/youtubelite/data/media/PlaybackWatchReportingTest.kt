package com.hhst.youtubelite.data.media

import com.hhst.youtubelite.watch.domain.WatchComment
import com.hhst.youtubelite.watch.domain.WatchVideo
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

/** Session-owned playback reporting: watch-time attribution and anonymous local history. */
class PlaybackWatchReportingTest {

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
    private val library = LocalLibraryStore(MemoryAuthStore())

    @Before fun setUp() {
        MediaSession.initialize(MemoryAuthStore())
        requests.clear()
    }

    @After fun tearDown() = MediaSession.initialize(MemoryAuthStore())

    private fun video(id: String) = WatchVideo(id, "Video $id", "Author", "@author", "channel-$id", "image",
        "1", "1", "now", "0", WatchComment("a", "b", "c"), "d", tracking = tracking)

    private fun reporting(): PlaybackWatchReporting {
        val client = MediaClient(okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            requests.add(HttpRequest(chain.request().method, chain.request().url.toString()))
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200).message("OK").body("".toResponseBody()).build()
        }.build(), { MediaSession.snapshot() })
        return PlaybackWatchReporting(client, { library }, CoroutineScope(Dispatchers.Default),
            WatchTimeReporter(client, CoroutineScope(Dispatchers.Default), WatchHistory(http, "cpn")))
    }

    private fun awaitRequests(count: Int) {
        val deadline = System.currentTimeMillis() + 5_000
        while (requests.size < count && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(count, requests.size)
    }

    private fun awaitHistory(size: Int) {
        val deadline = System.currentTimeMillis() + 5_000
        while (library.history().entries.size < size && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(size, library.history().entries.size)
    }

    private fun anonymousVisitor() {
        MediaSession.initialize(MemoryAuthStore()) { false }
        MediaSession.rememberVisitor("visitor", MediaSession.snapshot().generation)
    }

    @Test fun anonymousVisitorPlaybackReportsWatchTimeAndRecordsLocalHistory() {
        anonymousVisitor()
        val reporting = reporting()
        reporting.publish(video("v1"))
        reporting.tick(PlaybackSample("v1", 10_000, 100_000, playing = true))
        awaitRequests(2)
        awaitHistory(1)
        assertEquals("v1", library.history().entries.single().videoId)
        assertEquals(0.1f, library.history().entries.single().progress!!, 0.001f)
        reporting.detach()
    }

    @Test fun signedInPlaybackReportsWatchTimeButLeavesLocalHistoryAlone() {
        MediaSession.completeSignIn(SignInClient("id", "secret", "device"),
            AccessToken("access", "refresh", 4_000_000_000_000),
            listOf(Account("owner", "Owner", null, null, selected = true, disabled = false, pageId = null)))
        val reporting = reporting()
        reporting.publish(video("v1"))
        reporting.tick(PlaybackSample("v1", 10_000, 100_000, playing = true))
        awaitRequests(2)
        Thread.sleep(300)
        assertTrue(library.history().entries.isEmpty())
        reporting.detach()
    }

    @Test fun privateBrowsingReportsNothing() {
        MediaSession.initialize(MemoryAuthStore()) { true }
        MediaSession.rememberVisitor("visitor", MediaSession.snapshot().generation)
        val reporting = reporting()
        reporting.publish(video("v1"))
        reporting.tick(PlaybackSample("v1", 10_000, 100_000, playing = true))
        Thread.sleep(300)
        assertEquals(0, requests.size)
        assertTrue(library.history().entries.isEmpty())
        reporting.detach()
    }

    @Test fun pausedSamplesMissingSamplesAndStoppedMetadataRecordNothing() {
        anonymousVisitor()
        val reporting = reporting()
        reporting.publish(video("v1"))
        reporting.tick(PlaybackSample("v1", 10_000, 100_000, playing = false))
        reporting.tick(null)
        reporting.publish(null)
        reporting.tick(PlaybackSample("v1", 20_000, 100_000, playing = true))
        Thread.sleep(300)
        assertEquals(0, requests.size)
        assertTrue(library.history().entries.isEmpty())
        reporting.detach()
    }

    @Test fun progressFromAnotherVideoCannotBeAttributedToThePublishedOne() {
        anonymousVisitor()
        val reporting = reporting()
        reporting.publish(video("v1"))
        reporting.tick(PlaybackSample("v2", 10_000, 100_000, playing = true))
        Thread.sleep(300)
        assertEquals(0, requests.size)
        assertTrue(library.history().entries.isEmpty())
        reporting.tick(PlaybackSample("v1", 10_000, 100_000, playing = true))
        awaitRequests(2)
        awaitHistory(1)
        assertEquals("v1", library.history().entries.single().videoId)
        reporting.detach()
    }

    @Test fun zeroDurationSamplesRecordWithoutProgress() {
        anonymousVisitor()
        val reporting = reporting()
        reporting.publish(video("v1"))
        reporting.tick(PlaybackSample("v1", 10_000, 0, playing = true))
        awaitHistory(1)
        assertNull(library.history().entries.single().progress)
        reporting.detach()
    }
}
