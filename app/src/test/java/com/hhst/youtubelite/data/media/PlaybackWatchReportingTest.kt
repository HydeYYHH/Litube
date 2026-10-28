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

/** Session-owned playback reporting: watch-time attribution and anonymous local history. */
class PlaybackWatchReportingTest {

    private var activeReporter: WatchTimeReporter? = null
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

    private fun video(id: String) = ReportingVideo(id, "Video $id", "Author", "channel-$id", "image", tracking)

    private fun reporting(): PlaybackWatchReporting {
        val client = MediaClient(okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            requests.add(HttpRequest(chain.request().method, chain.request().url.toString()))
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200).message("OK").body("".toResponseBody()).build()
        }.build(), { MediaSession.snapshot() })
        return PlaybackWatchReporting(client, { library }, CoroutineScope(Dispatchers.Default),
            WatchTimeReporter(client, CoroutineScope(Dispatchers.Default), { WatchHistory(http) }).also { activeReporter = it })
    }

    private fun awaitRequests(count: Int) {
        val deadline = System.currentTimeMillis() + 5_000
        while (requests.size < count && System.currentTimeMillis() < deadline) Thread.sleep(20)
        kotlinx.coroutines.runBlocking { activeReporter?.awaitPendingReport() }
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
        // The first update creates the playback record; watch time lands with the next.
        awaitRequests(1)
        assertEquals(0L, reporting.anonymousReportSignal.value)
        awaitHistory(1)
        assertEquals("v1", library.history().entries.single().videoId)
        assertEquals(0.1f, library.history().entries.single().progress!!, 0.001f)
        reporting.tick(PlaybackSample("v1", 20_000, 100_000, playing = true))
        awaitRequests(2)
        val deadline = System.currentTimeMillis() + 5_000
        while (reporting.anonymousReportSignal.value == 0L && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(1L, reporting.anonymousReportSignal.value)
        // The same entry tracks the latest position.
        val progressDeadline = System.currentTimeMillis() + 5_000
        while (library.history().entries.single().progress!! < 0.15f && System.currentTimeMillis() < progressDeadline) Thread.sleep(20)
        assertEquals(0.2f, library.history().entries.single().progress!!, 0.001f)
        reporting.detach()
    }

    @Test fun signedInPlaybackReportsWatchTimeButLeavesLocalHistoryAlone() {
        MediaSession.completeSignIn(SignInClient("id", "secret", "device"),
            AccessToken("access", "refresh", 4_000_000_000_000),
            listOf(Account("owner", "Owner", null, null, selected = true, disabled = false, pageId = null)))
        val reporting = reporting()
        reporting.publish(video("v1"))
        reporting.tick(PlaybackSample("v1", 10_000, 100_000, playing = true))
        awaitRequests(1)
        reporting.tick(PlaybackSample("v1", 20_000, 100_000, playing = true))
        awaitRequests(2)
        Thread.sleep(300)
        assertTrue(library.history().entries.isEmpty())
        reporting.detach()
    }

    @Test fun playbackResponseEndpointsArmReportingWhenThePageCarriesNone() {
        anonymousVisitor()
        val reporting = reporting()
        // The watch page publishes metadata without endpoints; the playback
        // response arms them afterwards and reporting activates.
        reporting.publish(video("v1").copy(tracking = null))
        reporting.armTracking("v1", tracking)
        reporting.tick(PlaybackSample("v1", 10_000, 100_000, playing = true))
        awaitRequests(1)
        reporting.detach()
    }

    @Test fun pagePublishWithoutEndpointsDoesNotDisarmThePlaybackSession() {
        anonymousVisitor()
        val reporting = reporting()
        reporting.armTracking("v1", tracking)
        reporting.publish(video("v1").copy(tracking = null))
        reporting.tick(PlaybackSample("v1", 10_000, 100_000, playing = true))
        awaitRequests(1)
        // A republish (page reload) must keep the armed session reporting.
        reporting.publish(video("v1").copy(tracking = null))
        reporting.tick(PlaybackSample("v1", 30_000, 100_000, playing = true))
        awaitRequests(2)
        reporting.detach()
    }

    @Test fun incompletePageTrackingDoesNotReplaceThePlaybackEndpoints() {
        anonymousVisitor()
        val reporting = reporting()
        try {
            reporting.armTracking("v1", tracking)
            reporting.publish(video("v1").copy(tracking = PlayerTracking(null, null)))
            reporting.tick(PlaybackSample("v1", 10_000, 100_000, playing = true))
            awaitRequests(1)
            reporting.publish(video("v1").copy(tracking = PlayerTracking(tracking.playbackUrl, "")))
            reporting.tick(PlaybackSample("v1", 20_000, 100_000, playing = true))
            awaitRequests(2)
            assertTrue(requests.last().url.contains("/stats/watchtime"))
        } finally { reporting.detach() }
    }

    @Test fun playbackEndpointsTakePriorityOverASeparatePageResponse() {
        anonymousVisitor()
        val reporting = reporting()
        try {
            reporting.armTracking("v1", tracking.copy(playbackUrl = tracking.playbackUrl + "&source=playback"))
            reporting.publish(video("v1"))
            reporting.tick(PlaybackSample("v1", 10_000, 100_000, playing = true))
            awaitRequests(1)
            assertTrue(requests.single().url.contains("source=playback"))
        } finally { reporting.detach() }
    }

    @Test fun armedEndpointsDoNotLeakToAnotherVideo() {
        anonymousVisitor()
        val reporting = reporting()
        reporting.armTracking("v1", tracking)
        reporting.publish(video("v2").copy(tracking = null))
        reporting.tick(PlaybackSample("v2", 10_000, 100_000, playing = true))
        Thread.sleep(300)
        assertEquals(0, requests.size)
        reporting.detach()
    }

    @Test fun metadataCannotRearmThePreviousIdentitysEndpoints() {
        anonymousVisitor()
        val reporting = reporting()
        reporting.armTracking("v1", tracking)
        reporting.publish(video("v1").copy(tracking = null))
        reporting.tick(PlaybackSample("v1", 0, 100_000, true))
        awaitRequests(1)
        MediaSession.signOut()
        MediaSession.rememberVisitor("next-visitor", MediaSession.snapshot().generation)
        reporting.publish(video("v1").copy(tracking = null))
        reporting.tick(PlaybackSample("v1", 5_000, 100_000, true))
        kotlinx.coroutines.runBlocking { activeReporter?.awaitPendingReport() }
        assertEquals(1, requests.size)
        reporting.detach()
    }

    @Test fun delayedPlaybackAndPageResponsesCannotReplaceTheCurrentIdentity() {
        anonymousVisitor()
        val previous = MediaSession.snapshot().generation
        val reporting = reporting()
        MediaSession.signOut()
        MediaSession.rememberVisitor("next-visitor", MediaSession.snapshot().generation)
        val currentTracking = PlayerTracking("https://www.youtube.com/api/stats/playback?ei=current",
            "https://www.youtube.com/api/stats/watchtime?ei=current")
        reporting.publish(video("v1").copy(tracking = currentTracking,
            sessionGeneration = MediaSession.snapshot().generation))
        reporting.armTracking("v1", tracking, previous)
        reporting.publish(video("v1").copy(sessionGeneration = previous))
        reporting.tick(PlaybackSample("v1", 5_000, 100_000, true))
        awaitRequests(1)
        assertTrue(requests.single().url.contains("ei=current"))
        reporting.detach()
    }

    @Test fun oldMetadataDoesNotCreateHistoryForANewAnonymousIdentity() {
        anonymousVisitor()
        val reporting = reporting()
        reporting.publish(video("v1"))
        MediaSession.signOut()
        MediaSession.rememberVisitor("next-visitor", MediaSession.snapshot().generation)
        reporting.tick(PlaybackSample("v1", 10_000, 100_000, true))
        kotlinx.coroutines.runBlocking { activeReporter?.awaitPendingReport() }
        assertTrue(library.history().entries.isEmpty())
        assertTrue(requests.isEmpty())
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
        awaitRequests(1)
        reporting.tick(PlaybackSample("v1", 20_000, 100_000, playing = true))
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
    @Test fun privacyChangesBetweenSamplesStillBreakTheReportingInterval() {
        var privateMode = false
        MediaSession.initialize(MemoryAuthStore()) { privateMode }
        MediaSession.rememberVisitor("visitor", MediaSession.snapshot().generation)
        val reporting = reporting()
        reporting.publish(video("v1"))
        reporting.tick(PlaybackSample("v1", 10_000, 100_000, playing = true))
        awaitRequests(1)
        privateMode = true
        reporting.onPrivacyChanged()
        privateMode = false
        reporting.onPrivacyChanged()
        reporting.tick(PlaybackSample("v1", 40_000, 100_000, playing = true))
        awaitRequests(2)
        assertTrue(requests.last().url.contains("/stats/playback"))
        reporting.detach()
    }

}
