package com.hhst.youtubelite.data.media

import com.liskovsoft.youtubeapi.videoinfo.V2.AccessToken
import com.liskovsoft.youtubeapi.videoinfo.V2.Account
import com.liskovsoft.youtubeapi.videoinfo.V2.HttpClient
import com.liskovsoft.youtubeapi.videoinfo.V2.HttpReply
import com.liskovsoft.youtubeapi.videoinfo.V2.HttpRequest
import com.liskovsoft.youtubeapi.videoinfo.V2.PlayerTracking
import com.liskovsoft.youtubeapi.videoinfo.V2.SignInClient
import com.liskovsoft.youtubeapi.videoinfo.V2.WatchHistory
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

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

    private fun reporting(clock: () -> Long = { System.nanoTime() / 1_000_000L },
        reloadVideo: suspend (String) -> ReportingVideo? = { null }): PlaybackWatchReporting {
        val client = MediaClient(OkHttpClient.Builder().addInterceptor { chain ->
            requests.add(HttpRequest(chain.request().method, chain.request().url.toString()))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body("".toResponseBody()).build()
        }.build(), { MediaSession.snapshot() })
        return PlaybackWatchReporting(client, { library }, CoroutineScope(Dispatchers.Default),
            WatchTimeReporter(client, CoroutineScope(Dispatchers.Default), { WatchHistory(http) },
                loadAccountTracking = { _, _, _ -> tracking }).also { activeReporter = it },
            reloadVideo, clock)
    }

    @Test fun identitySwitchDuringPlaybackReadsFreshEndpointsBeforeReportingAgain() = runBlocking {
        val previousFixtures = ContentSource.fixtures
        ContentSource.fixtures = false
        anonymousVisitor()
        var reads = 0
        val refreshAllowed = CompletableDeferred<Unit>()
        val reporting = reporting { id ->
            refreshAllowed.await()
            reads++
            video(id).copy(tracking = PlayerTracking(
                "https://www.youtube.com/api/stats/playback?ei=new",
                "https://www.youtube.com/api/stats/watchtime?ei=new"),
                sessionGeneration = MediaSession.snapshot().generation)
        }
        try {
            reporting.armTracking("v1", tracking)
            reporting.tick(PlaybackSample("v1", 0, 100_000, true))
            awaitRequests(1)
            MediaSession.signOut()
            MediaSession.rememberVisitor("next-visitor", MediaSession.snapshot().generation)
            reporting.tick(PlaybackSample("v1", 5_000, 100_000, true))
            refreshAllowed.complete(Unit)
            reporting.awaitContextRefresh()
            reporting.tick(PlaybackSample("v1", 6_000, 100_000, true))
            awaitRequests(2)
            assertTrue(requests.last().url.contains("ei=new"))
            assertTrue(requests.last().url.contains("cmt=6.0"))
            reporting.tick(PlaybackSample("v1", 16_000, 100_000, true))
            awaitRequests(3)
            assertTrue(requests.last().url.contains("st=6.0"))
            assertEquals(1, reads)
        } finally { refreshAllowed.complete(Unit); reporting.detach(); ContentSource.fixtures = previousFixtures }
    }

    @Test fun aLateIdentityRefreshCannotReplaceTheNextVideo() = runBlocking {
        val previousFixtures = ContentSource.fixtures
        ContentSource.fixtures = false
        anonymousVisitor()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val reporting = reporting { id ->
            started.complete(Unit)
            release.await()
            video(id).copy(sessionGeneration = MediaSession.snapshot().generation)
        }
        try {
            reporting.armTracking("v1", tracking)
            MediaSession.signOut()
            MediaSession.rememberVisitor("next-visitor", MediaSession.snapshot().generation)
            reporting.tick(PlaybackSample("v1", 5_000, 100_000, true))
            started.await()
            reporting.armTracking("v2", tracking.copy(playbackUrl = tracking.playbackUrl + "&source=current"))
            reporting.tick(PlaybackSample("v2", 0, 100_000, true))
            release.complete(Unit)
            reporting.awaitContextRefresh()
            awaitRequests(1)
            reporting.tick(PlaybackSample("v2", 10_000, 100_000, true))
            awaitRequests(2)
            assertTrue(requests.first().url.contains("source=current"))
            assertTrue(requests.all { it.url.contains("docid=v2") })
        } finally { release.complete(Unit); reporting.detach(); ContentSource.fixtures = previousFixtures }
    }

    @Test fun aFailedIdentityRefreshRetriesAfterTheBackoff() = runBlocking {
        val previousFixtures = ContentSource.fixtures
        ContentSource.fixtures = false
        anonymousVisitor()
        var now = 0L
        var reads = 0
        val reporting = reporting(clock = { now }) { id ->
            reads++
            if (reads == 1) throw IOException("offline")
            video(id).copy(sessionGeneration = MediaSession.snapshot().generation)
        }
        try {
            reporting.armTracking("v1", tracking)
            MediaSession.signOut()
            MediaSession.rememberVisitor("next-visitor", MediaSession.snapshot().generation)
            reporting.tick(PlaybackSample("v1", 0, 100_000, true))
            reporting.awaitContextRefresh()
            assertEquals(1, reads)
            now = 29_999
            reporting.tick(PlaybackSample("v1", 5_000, 100_000, true))
            reporting.awaitContextRefresh()
            assertEquals(1, reads)
            now = 30_000
            reporting.tick(PlaybackSample("v1", 10_000, 100_000, true))
            reporting.awaitContextRefresh()
            reporting.tick(PlaybackSample("v1", 11_000, 100_000, true))
            awaitRequests(1)
            assertEquals(2, reads)
            assertTrue(requests.single().url.contains("cmt=11.0"))
        } finally { reporting.detach(); ContentSource.fixtures = previousFixtures }
    }

    @Test fun detachAllowsAnIdentityRefreshWhenPlaybackReattaches() = runBlocking {
        val previousFixtures = ContentSource.fixtures
        ContentSource.fixtures = false
        anonymousVisitor()
        var reads = 0
        val reporting = reporting { id ->
            reads++
            if (reads == 1) null else video(id).copy(sessionGeneration = MediaSession.snapshot().generation)
        }
        try {
            reporting.armTracking("v1", tracking)
            MediaSession.signOut()
            MediaSession.rememberVisitor("next-visitor", MediaSession.snapshot().generation)
            reporting.tick(PlaybackSample("v1", 0, 100_000, true))
            reporting.awaitContextRefresh()
            reporting.detach()
            reporting.tick(PlaybackSample("v1", 10_000, 100_000, true))
            reporting.awaitContextRefresh()
            reporting.tick(PlaybackSample("v1", 11_000, 100_000, true))
            awaitRequests(1)
            assertEquals(2, reads)
        } finally { reporting.detach(); ContentSource.fixtures = previousFixtures }
    }

    private fun awaitRequests(count: Int) {
        val deadline = System.currentTimeMillis() + 5_000
        while (requests.size < count && System.currentTimeMillis() < deadline) Thread.sleep(20)
        runBlocking { activeReporter?.awaitPendingReport() }
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
        runBlocking { activeReporter?.awaitPendingReport() }
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
        runBlocking { activeReporter?.awaitPendingReport() }
        assertTrue(library.history().entries.isEmpty())
        assertTrue(requests.isEmpty())
        reporting.detach()
    }

    @Test fun playbackReportsWithoutPageMetadataAndKeepsHistoryEmpty() {
        anonymousVisitor()
        val reporting = reporting()
        reporting.armTracking("v1", tracking, MediaSession.snapshot().generation)
        reporting.tick(PlaybackSample("v1", 0, 30_000, true))
        awaitRequests(1)
        reporting.tick(PlaybackSample("v1", 3_000, 30_000, true))
        reporting.detach()
        runBlocking { activeReporter?.awaitPendingReport() }
        assertEquals(2, requests.size)
        assertTrue(requests.last().url.contains("et=3.0"))
        assertTrue(library.history().entries.isEmpty())
        assertEquals(1L, reporting.anonymousReportSignal.value)
    }

    @Test fun clearingPageMetadataDoesNotDisarmAnActivePlayer() {
        anonymousVisitor()
        val reporting = reporting()
        reporting.armTracking("v1", tracking, MediaSession.snapshot().generation)
        reporting.publish(video("v1"))
        reporting.tick(PlaybackSample("v1", 0, 30_000, true))
        awaitRequests(1)
        reporting.publish(null)
        reporting.tick(PlaybackSample("v1", 10_000, 30_000, true))
        awaitRequests(2)
        assertEquals(1L, reporting.anonymousReportSignal.value)
        reporting.detach()
    }

    @Test fun missingEngineTrackingPreservesUsablePageEndpoints() {
        anonymousVisitor()
        val reporting = reporting()
        reporting.armTracking("v1", null, MediaSession.snapshot().generation)
        reporting.publish(video("v1"))
        reporting.tick(PlaybackSample("v1", 0, 30_000, true))
        awaitRequests(1)
        reporting.tick(PlaybackSample("v1", 3_000, 30_000, true))
        reporting.finishPlayback(PlaybackSample("v1", 3_500, 30_000, false))
        runBlocking { activeReporter?.awaitPendingReport() }
        assertEquals(2, requests.size)
        assertTrue(requests.last().url.contains("et=3.5"))
        reporting.detach()
    }

    @Test fun aPlaybackBoundaryIncludesProgressAfterTheLastSamplerTick() {
        anonymousVisitor()
        val reporting = reporting()
        reporting.armTracking("v1", tracking, MediaSession.snapshot().generation)
        reporting.tick(PlaybackSample("v1", 0, 30_000, true))
        awaitRequests(1)
        reporting.tick(PlaybackSample("v1", 2_000, 30_000, true))
        reporting.finishPlayback(PlaybackSample("v1", 3_750, 30_000, false))
        runBlocking { activeReporter?.awaitPendingReport() }
        assertEquals(2, requests.size)
        assertTrue(requests.last().url.contains("et=3.75"))
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
