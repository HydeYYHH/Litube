package com.hhst.youtubelite.data.media

import com.liskovsoft.youtubeapi.videoinfo.V2.AccessToken
import com.liskovsoft.youtubeapi.videoinfo.V2.Account
import com.liskovsoft.youtubeapi.videoinfo.V2.HttpRequest
import com.liskovsoft.youtubeapi.videoinfo.V2.HttpClient
import com.liskovsoft.youtubeapi.videoinfo.V2.PlayerTracking
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestContext
import com.liskovsoft.youtubeapi.videoinfo.V2.SignInClient
import com.liskovsoft.youtubeapi.videoinfo.V2.WatchHistory
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import okhttp3.ResponseBody.Companion.toResponseBody

class WatchTimeReporterTest {

    private var activeReporter: WatchTimeReporter? = null
    private val requests = ConcurrentLinkedQueue<HttpRequest>()
    private var failRequests = false
    private var hangRequests = false
    private val tracking = PlayerTracking(
        "https://www.youtube.com/api/stats/playback?ei=e",
        "https://www.youtube.com/api/stats/watchtime?ei=e")
    private val fakeNow = AtomicLong(0)

    @Before fun setUp() {
        failRequests = false
        hangRequests = false
        fakeNow.set(0)
        MediaSession.initialize(MemoryAuthStore())
        MediaSession.completeSignIn(SignInClient("id", "secret", "device"),
            AccessToken("access", "refresh", 4_000_000_000_000),
            listOf(Account("owner", "Owner", null, null, selected = true, disabled = false, pageId = null)))
        requests.clear()
    }

    @After fun tearDown() {
        activeReporter?.stop()
        MediaSession.initialize(MemoryAuthStore())
    }

    private fun reporter(loadAccountTracking: (String, RequestContext, HttpClient) -> PlayerTracking = { _, _, _ -> tracking }): WatchTimeReporter {
        val client = MediaClient(okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            requests.add(HttpRequest(chain.request().method, chain.request().url.toString()))
            if (hangRequests) Thread.sleep(2_000)
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(if (failRequests) 500 else 200).message("X").body("".toResponseBody()).build()
        }.build(), { MediaSession.snapshot() })
        return WatchTimeReporter(client, CoroutineScope(Dispatchers.Default), now = fakeNow::get,
            loadAccountTracking = loadAccountTracking).also { activeReporter = it }
    }

    private fun awaitRequests(count: Int) {
        val deadline = System.currentTimeMillis() + 5_000
        while (requests.size < count && System.currentTimeMillis() < deadline) Thread.sleep(20)
        kotlinx.coroutines.runBlocking { activeReporter?.awaitPendingReport() }
        assertEquals(count, requests.size)
    }

    @Test fun ticksReportWhilePlayingAndThrottleToTenSecondsOfProgress() {
        val reporter = reporter()
        reporter.watch("video", tracking)
        reporter.tick(0, 100_000, playing = true)
        // The first update creates the playback record; watch time needs a real interval.
        awaitRequests(1)
        reporter.tick(5_000, 100_000, playing = true)
        Thread.sleep(300)
        assertEquals(1, requests.size)
        reporter.tick(11_000, 100_000, playing = true)
        awaitRequests(2)
    }

    @Test fun aFailedTailRetriesAfterSwitchingToAnotherVideo() = kotlinx.coroutines.runBlocking {
        val reporter = reporter()
        reporter.watch("first", tracking)
        reporter.tick(0, 30_000, true)
        reporter.awaitPendingReport()
        reporter.tick(3_000, 30_000, true)
        failRequests = true
        reporter.finish()
        reporter.awaitPendingReport()
        assertEquals(2, requests.size)
        failRequests = false
        fakeNow.addAndGet(60_000)
        reporter.watch("second", tracking)
        reporter.tick(0, 30_000, true)
        reporter.awaitPendingReport()
        assertEquals(4, requests.size)
        val tail = requests.last { it.url.contains("/watchtime") }
        assertTrue(tail.url.contains("docid=first"))
        assertTrue(tail.url.contains("et=3.0"))
    }

    @Test fun aBackwardSeekResetsTheRangeWithoutAZeroLengthReport() {
        val reporter = reporter()
        reporter.watch("video", tracking)
        reporter.tick(20_000, 100_000, playing = true)
        awaitRequests(1)
        reporter.tick(2_000, 100_000, playing = true)
        Thread.sleep(300)
        assertEquals(1, requests.size)
        reporter.tick(12_000, 100_000, playing = true)
        awaitRequests(2)
        val watchtime = requests.last().url
        assertTrue(watchtime.contains("st=2.0"))
        assertTrue(watchtime.contains("et=12.0"))
    }

    @Test fun aForwardSeekReanchorsWithoutCountingTheSkippedSpan() {
        val reporter = reporter()
        reporter.watch("video", tracking)
        reporter.tick(10_000, 200_000, playing = true, discontinuityEpoch = 0)
        awaitRequests(1)
        reporter.tick(100_000, 200_000, playing = true, discontinuityEpoch = 1)
        Thread.sleep(300)
        assertEquals(1, requests.size)
        reporter.tick(110_000, 200_000, playing = true, discontinuityEpoch = 1)
        awaitRequests(2)
        val watchtime = requests.last().url
        assertTrue(watchtime.contains("st=100.0"))
        assertTrue(watchtime.contains("et=110.0"))
    }

    @Test fun pausedOrStoppedPlaybackDoesNotReport() {
        val reporter = reporter()
        reporter.watch("video", tracking)
        reporter.tick(5_000, 100_000, playing = false)
        Thread.sleep(300)
        assertEquals(0, requests.size)
        reporter.tick(5_000, 100_000, playing = true)
        awaitRequests(1)
        reporter.stop()
        requests.clear()
        reporter.tick(20_000, 100_000, playing = true)
        Thread.sleep(300)
        assertEquals(0, requests.size)
    }

    @Test fun anonymousSessionsDoNotReport() {
        MediaSession.initialize(MemoryAuthStore())
        val reporter = reporter()
        reporter.watch("video", tracking)
        reporter.tick(5_000, 100_000, playing = true)
        Thread.sleep(300)
        assertEquals(0, requests.size)
    }

    @Test fun anAnonymousVisitorCanReportButPrivateBrowsingCannot() {
        var privateBrowsing = false
        MediaSession.initialize(MemoryAuthStore()) { privateBrowsing }
        MediaSession.rememberVisitor("visitor", MediaSession.snapshot().generation)
        val reporter = reporter()
        reporter.watch("video", tracking)
        reporter.tick(5_000, 100_000, playing = true)
        awaitRequests(1)
        privateBrowsing = true
        reporter.tick(20_000, 100_000, playing = true)
        Thread.sleep(300)
        assertEquals(1, requests.size)
        reporter.stop()
    }

    @Test fun privacyToggledMidSessionCancelsTheQueuedReport() {
        var privateBrowsing = false
        MediaSession.initialize(MemoryAuthStore()) { privateBrowsing }
        MediaSession.rememberVisitor("visitor", MediaSession.snapshot().generation)
        val reporter = reporter()
        hangRequests = true // the wire stays busy long enough to toggle mid-flight
        reporter.watch("video", tracking)
        reporter.tick(5_000, 100_000, playing = true)
        val deadline = System.currentTimeMillis() + 5_000
        while (requests.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(1, requests.size) // already on the wire; not claimed to be retractable
        privateBrowsing = true
        reporter.cancelPending()
        reporter.tick(30_000, 100_000, playing = true)
        hangRequests = false
        privateBrowsing = false
        reporter.tick(41_000, 100_000, playing = true)
        awaitRequests(2)
        reporter.stop()
    }

    @Test fun failedReportsBackOffAndRecoverAfterABoundedProbeInterval() {
        failRequests = true
        val reporter = reporter()
        reporter.watch("video", tracking)
        reporter.tick(5_000, 100_000, playing = true)
        awaitRequests(1)
        reporter.tick(16_000, 100_000, playing = true)
        Thread.sleep(300)
        assertEquals(1, requests.size)
        repeat(4) {
            fakeNow.addAndGet(120_000)
            reporter.tick(30_000 + it * 15_000L, 200_000, playing = true)
            val deadline = System.currentTimeMillis() + 5_000
            val expected = requests.size + 1
            while (requests.size < expected && System.currentTimeMillis() < deadline) Thread.sleep(20)
        }
        assertEquals(5, requests.size)
        fakeNow.addAndGet(299_999)
        reporter.tick(120_000, 200_000, playing = true)
        Thread.sleep(300)
        assertEquals(5, requests.size)
        failRequests = false
        fakeNow.incrementAndGet()
        reporter.tick(121_000, 200_000, playing = true)
        awaitRequests(7)
        reporter.tick(132_000, 200_000, playing = true)
        awaitRequests(8)
        reporter.stop()
    }

    @Test fun anonymousSuccessSignalsARefreshOnlyAfterAWatchedInterval() = kotlinx.coroutines.runBlocking {
        MediaSession.initialize(MemoryAuthStore()) { false }
        MediaSession.rememberVisitor("visitor", MediaSession.snapshot().generation)
        val reporter = reporter()
        var signals = 0
        reporter.onAnonymousReport = { signals += 1 }
        reporter.watch("video", tracking)
        reporter.tick(5_000, 100_000, playing = true)
        reporter.awaitPendingReport()
        assertEquals(0, signals)
        reporter.tick(15_000, 100_000, playing = true)
        reporter.awaitPendingReport()
        assertEquals(1, signals)
        reporter.stop()
    }

    @Test fun seekDuringBackoffReanchorsWithoutResettingTheRetryDeadline() = kotlinx.coroutines.runBlocking {
        val reporter = reporter()
        reporter.watch("video", tracking)
        reporter.tick(0, 200_000, true, discontinuityEpoch = 0)
        reporter.awaitPendingReport()
        failRequests = true
        reporter.tick(11_000, 200_000, true, discontinuityEpoch = 0)
        reporter.awaitPendingReport()
        assertEquals(2, requests.size)
        reporter.tick(80_000, 200_000, false, discontinuityEpoch = 1)
        reporter.awaitPendingReport()
        assertEquals(2, requests.size)
        failRequests = false
        reporter.tick(90_000, 200_000, true, discontinuityEpoch = 1)
        reporter.awaitPendingReport()
        assertEquals(2, requests.size)
        fakeNow.set(70_000)
        reporter.tick(90_000, 200_000, true, discontinuityEpoch = 1)
        reporter.awaitPendingReport()
        assertTrue(requests.last().url.contains("st=80.0"))
        assertTrue(requests.last().url.contains("et=90.0"))
    }

    @Test fun seekingBeforePlaybackDoesNotCreateAView() = kotlinx.coroutines.runBlocking {
        val reporter = reporter()
        reporter.watch("video", tracking)
        reporter.tick(80_000, 200_000, false, discontinuityEpoch = 1)
        reporter.awaitPendingReport()
        assertTrue(requests.isEmpty())
        reporter.tick(81_000, 200_000, true, discontinuityEpoch = 1)
        reporter.awaitPendingReport()
        assertEquals(1, requests.size)
        assertTrue(requests.last().url.contains("cmt=81.0"))
    }

    @Test fun anotherVideosSeekDoesNotMoveTheActiveWatchAnchor() = kotlinx.coroutines.runBlocking {
        val reporter = reporter()
        reporter.watch("video", tracking)
        reporter.tick(5_000, 200_000, true)
        reporter.awaitPendingReport()
        reporter.tick(80_000, 200_000, false, playingVideoId = "other", discontinuityEpoch = 1)
        reporter.awaitPendingReport()
        reporter.tick(15_000, 200_000, true)
        reporter.awaitPendingReport()
        assertTrue(requests.last().url.contains("st=5.0"))
    }

    @Test fun trackingFromThePreviousIdentityCannotBeSentAfterSignOut() {
        val reporter = reporter()
        reporter.watch("video", tracking)
        reporter.tick(5_000, 100_000, playing = true)
        awaitRequests(1)
        MediaSession.signOut()
        MediaSession.rememberVisitor("visitor", MediaSession.snapshot().generation)
        reporter.tick(20_000, 100_000, playing = true)
        Thread.sleep(300)
        assertEquals(1, requests.size)
        reporter.stop()
    }

    @Test fun missingAnonymousTrackingDisablesReporting() {
        MediaSession.initialize(MemoryAuthStore())
        MediaSession.rememberVisitor("visitor", MediaSession.snapshot().generation)
        val reporter = reporter()
        reporter.watch("video", null)
        reporter.tick(5_000, 100_000, playing = true)
        Thread.sleep(300)
        assertEquals(0, requests.size)
    }

    @Test fun signedInPlaybackResolvesAccountTrackingOnceInsteadOfUsingAnonymousEndpoints() = kotlinx.coroutines.runBlocking {
        var resolutions = 0
        val accountTracking = PlayerTracking(tracking.playbackUrl + "&source=account", tracking.watchTimeUrl + "&source=account")
        val reporter = reporter { _, context, _ ->
            assertTrue(context.signedIn)
            resolutions++
            accountTracking
        }
        reporter.watch("video", null)
        reporter.tick(0, 100_000, true)
        reporter.awaitPendingReport()
        reporter.watch("video", tracking.copy(playbackUrl = tracking.playbackUrl + "&source=anonymous"))
        reporter.tick(11_000, 100_000, true)
        reporter.awaitPendingReport()
        assertEquals(1, resolutions)
        assertEquals(2, requests.size)
        assertTrue(requests.all { it.url.contains("source=account") && it.url.contains("ver=2") })
    }

    @Test fun aFailedAccountTrackingReadDoesNotFallBackToAnonymousEndpoints() = kotlinx.coroutines.runBlocking {
        val reporter = reporter { _, _, _ -> throw java.io.IOException("offline") }
        reporter.watch("video", tracking)
        reporter.tick(0, 100_000, true)
        reporter.awaitPendingReport()
        assertTrue(requests.isEmpty())
    }

    @Test fun progressFromAnotherVideoCannotBeAttributedToThePreviousOne() {
        val reporter = reporter()
        reporter.watch("previous", tracking)
        reporter.tick(10_000, 100_000, playing = true, playingVideoId = "next")
        Thread.sleep(300)
        assertTrue(requests.isEmpty())
    }
    @Test fun switchingVideosFlushesTheObservedShortTailWithItsOwnNonce() = kotlinx.coroutines.runBlocking {
        val reporter = reporter()
        reporter.watch("first", tracking)
        reporter.tick(0, 30_000, true)
        reporter.awaitPendingReport()
        reporter.tick(3_000, 30_000, true)
        reporter.watch("second", tracking)
        reporter.tick(0, 30_000, true)
        reporter.awaitPendingReport()
        val first = requests.filter { it.url.contains("docid=first") }
        assertEquals(2, first.size)
        assertTrue(first.last().url.contains("st=0.0"))
        assertTrue(first.last().url.contains("et=3.0"))
        fun nonce(url: String) = Regex("[?&]cpn=([^&]+)").find(url)?.groupValues?.get(1)
        assertEquals(nonce(first.first().url), nonce(first.last().url))
        assertNotEquals(nonce(first.first().url), nonce(requests.single { it.url.contains("docid=second") }.url))
    }

    @Test fun pausingFlushesTheTailWithoutRepeatingIt() = kotlinx.coroutines.runBlocking {
        val reporter = reporter()
        reporter.watch("video", tracking)
        reporter.tick(0, 30_000, true)
        reporter.awaitPendingReport()
        reporter.tick(2_500, 30_000, false)
        reporter.awaitPendingReport()
        reporter.tick(2_500, 30_000, false)
        reporter.finish()
        reporter.awaitPendingReport()
        assertEquals(2, requests.size)
        assertTrue(requests.last().url.contains("et=2.5"))
    }

    @Test fun shortIntervalsOnBothSidesOfASeekNeverIncludeTheSkippedSpan() = kotlinx.coroutines.runBlocking {
        val reporter = reporter()
        reporter.watch("video", tracking)
        reporter.tick(0, 100_000, true, discontinuityEpoch = 0)
        reporter.awaitPendingReport()
        reporter.tick(2_000, 100_000, true, discontinuityEpoch = 0)
        reporter.tick(50_000, 100_000, true, discontinuityEpoch = 1)
        reporter.tick(54_000, 100_000, true, discontinuityEpoch = 1)
        reporter.finish()
        reporter.awaitPendingReport()
        val intervals = requests.filter { it.url.contains("/stats/watchtime") }
        assertEquals(2, intervals.size)
        assertTrue(intervals[0].url.contains("st=0.0") && intervals[0].url.contains("et=2.0"))
        assertTrue(intervals[1].url.contains("st=50.0") && intervals[1].url.contains("et=54.0"))
    }

    @Test fun anEndedShortReportsItsLastInterval() = kotlinx.coroutines.runBlocking {
        val reporter = reporter()
        reporter.watch("video", tracking)
        reporter.tick(0, 5_000, true)
        reporter.awaitPendingReport()
        reporter.tick(5_000, 5_000, false)
        reporter.finish()
        reporter.awaitPendingReport()
        assertEquals(2, requests.size)
        assertTrue(requests.last().url.contains("et=5.0"))
    }

    @Test fun leavingPrivateModeDoesNotReportThePrivateInterval() {
        var privateBrowsing = false
        MediaSession.initialize(MemoryAuthStore()) { privateBrowsing }
        MediaSession.rememberVisitor("visitor", MediaSession.snapshot().generation)
        val reporter = reporter()
        reporter.watch("video", tracking)
        reporter.tick(5_000, 100_000, playing = true)
        awaitRequests(1)
        Thread.sleep(100)
        privateBrowsing = true
        reporter.tick(20_000, 100_000, playing = true)
        privateBrowsing = false
        reporter.tick(30_000, 100_000, playing = true)
        awaitRequests(2)
        assertTrue(requests.last().url.contains("/stats/playback"))
        Thread.sleep(100)
        reporter.tick(40_000, 100_000, playing = true)
        awaitRequests(3)
        assertTrue(requests.last().url.contains("st=30.0"))
        reporter.stop()
    }

}
