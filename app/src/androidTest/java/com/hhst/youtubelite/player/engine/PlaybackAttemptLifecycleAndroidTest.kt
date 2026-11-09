@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hhst.youtubelite.player.engine

import androidx.activity.ComponentActivity
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.diagnostics.DiagnosticContext
import com.hhst.youtubelite.extension.ExtensionManager
import com.hhst.youtubelite.extension.PreferenceKeys
import com.hhst.youtubelite.extractor.Cache
import com.hhst.youtubelite.extractor.Chapter
import com.hhst.youtubelite.extractor.ChapterList
import com.hhst.youtubelite.extractor.ExtractionContext
import com.hhst.youtubelite.extractor.ExtractionDiagnostics
import com.hhst.youtubelite.extractor.ExtractionTransport
import com.hhst.youtubelite.extractor.Extractor
import com.hhst.youtubelite.extractor.Format
import com.hhst.youtubelite.extractor.MediaProtocol
import com.hhst.youtubelite.extractor.MediaRange
import com.hhst.youtubelite.extractor.MediaRejection
import com.hhst.youtubelite.extractor.MediaRequestPlan
import com.hhst.youtubelite.extractor.Metadata
import com.hhst.youtubelite.extractor.MscPlayback
import com.hhst.youtubelite.extractor.PageResponse
import com.hhst.youtubelite.extractor.PlaybackDemand
import com.hhst.youtubelite.extractor.PlaybackHost
import com.hhst.youtubelite.extractor.Stream
import com.hhst.youtubelite.extractor.StreamClient
import com.hhst.youtubelite.extractor.WebAccount
import com.hhst.youtubelite.extractor.YoutubeMediaRequests
import com.hhst.youtubelite.extractor.YoutubeWebSession
import com.hhst.youtubelite.player.datasource.MediaSourceResolver
import com.hhst.youtubelite.player.datasource.PlayerDataSource
import com.hhst.youtubelite.player.sponsor.SponsorBlockManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Exercises recovery through real refused responses, rather than invoking private error handlers. */
class PlaybackAttemptLifecycleAndroidTest {
    @get:Rule val activity = ActivityScenarioRule(ComponentActivity::class.java)

    @Test fun endingWhileOptionalChaptersAreBlockedAdvancesExactlyOnce() {
        RecoveryFixture(initialGood = true, blockChapters = true).use { fixture ->
            val advances = AtomicInteger()
            fixture.main { engine.onEnded = { advances.incrementAndGet() }; engine.play(VIDEO_A) }
            assertTrue(fixture.chaptersEntered.await(15, TimeUnit.SECONDS))
            fixture.await { it.isPlaying && it.durationMs > 0 }
            fixture.main { engine.seekTo(engine.snapshot.value.durationMs - 250) }
            fixture.await { it.ended }
            fixture.main { assertEquals(1, advances.get()) }
            fixture.releaseChapters.countDown()
            fixture.await { it.chapters.isNotEmpty() }
            fixture.main { assertEquals(1, advances.get()) }
        }
    }

    @Test fun replacingOrStoppingWhileChaptersAreBlockedDiscardsTheOldChapters() {
        for (stop in listOf(false, true)) {
            RecoveryFixture(initialGood = true, blockChapters = true).use { fixture ->
                val advances = AtomicInteger()
                fixture.main { engine.onEnded = { advances.incrementAndGet() }; engine.play(VIDEO_A) }
                assertTrue(fixture.chaptersEntered.await(15, TimeUnit.SECONDS))
                fixture.main { if (stop) engine.stop() else engine.play(VIDEO_B) }
                if (!stop) fixture.await { it.videoId == VIDEO_B && it.prepared }
                fixture.releaseChapters.countDown()
                fixture.extractor.extract(VIDEO_A).chapters.get(15, TimeUnit.SECONDS)
                fixture.main {
                    assertEquals(if (stop) null else VIDEO_B, engine.snapshot.value.videoId)
                    assertTrue(engine.snapshot.value.chapters.none { it.title == VIDEO_A })
                    assertEquals(0, advances.get())
                }
            }
        }
    }

    @Test fun aLateCastRefreshCannotPublishIntoAReopenedSessionOfTheSameVideo() {
        RecoveryFixture(initialGood = true, blockRecovery = true, castRefresh = true).use { fixture ->
            val reports = AtomicInteger()
            fixture.main { engine.onWatchContext = { _, _, _ -> reports.incrementAndGet() }; engine.play(VIDEO_A) }
            fixture.await { it.prepared }
            val worker = Executors.newSingleThreadExecutor()
            try {
                val refresh = worker.submit(Callable { fixture.engine.refreshCastUrl("a", VIDEO_A, null) })
                assertTrue(fixture.recoveryEntered.await(15, TimeUnit.SECONDS))
                fixture.main { engine.stop(); engine.play(VIDEO_A) }
                fixture.await { it.prepared }
                val expected = reports.get()
                fixture.releaseRecovery.countDown()
                assertEquals("The old proxy still receives its refreshed URL", fixture.castAudioUrl,
                    refresh.get(15, TimeUnit.SECONDS))
                fixture.main {
                    assertEquals("An old proxy refresh cannot report for the reopened local source", expected, reports.get())
                }
            } finally {
                fixture.releaseRecovery.countDown()
                worker.shutdownNow()
            }
        }
    }

    @Test fun replacingVideoWhileRecoveryIsBlockedDiscardsItsLateResult() =
        interruptRecovery(Interruption.REPLACE)

    @Test fun cancellingPendingWhileRecoveryIsBlockedDoesNotSubmitItsLateResult() =
        interruptRecovery(Interruption.CANCEL)

    @Test fun stoppingWhileRecoveryIsBlockedDoesNotRevivePlayback() =
        interruptRecovery(Interruption.STOP)

    @Test fun replacingVideoWhileRecoveryIsBlockedDiscardsItsLateFailure() =
        interruptRecovery(Interruption.REPLACE, failRecovery = true)

    @Test fun stoppingWhileRecoveryIsBlockedDiscardsItsLateFailure() =
        interruptRecovery(Interruption.STOP, failRecovery = true)

    @Test fun explicitRetryHasANewBudgetAfterThePreviousAttemptWasExhausted() {
        RecoveryFixture(persistent = true).use { fixture ->
            fixture.main { engine.play(VIDEO_A) }
            fixture.awaitFailure(VIDEO_A)
            fixture.assertExhausted(VIDEO_A, expectedRefusals = 2)
            val preparedBeforeRetry = fixture.engine.mediaPrepareCount

            fixture.main { engine.retry() }
            fixture.awaitFailure(VIDEO_A)

            // Retry starts with a fresh extraction. Its automatic recovery may join that
            // extraction, but must still submit one new source before becoming terminal.
            fixture.assertExhausted(VIDEO_A, expectedRefusals = 4)
            // Completed fresh results are shared for ten seconds. A slow device may
            // legitimately resolve once more after that window, within the same budget.
            assertTrue(fixture.freshReads.count { it == VIDEO_A } in 2..3)
            assertEquals(preparedBeforeRetry + 2, fixture.engine.mediaPrepareCount)
        }
    }

    @Test fun anotherVideoHasANewBudgetAfterThePreviousAttemptWasExhausted() {
        RecoveryFixture(persistent = true).use { fixture ->
            fixture.main { engine.play(VIDEO_A) }
            fixture.awaitFailure(VIDEO_A)
            fixture.assertExhausted(VIDEO_A, expectedRefusals = 2)
            val preparedBeforeReplacement = fixture.engine.mediaPrepareCount

            fixture.main { engine.play(VIDEO_B) }
            fixture.awaitFailure(VIDEO_B)

            fixture.assertExhausted(VIDEO_B, expectedRefusals = 2)
            assertEquals(listOf(VIDEO_A, VIDEO_B), fixture.freshReads.toList())
            assertEquals(preparedBeforeReplacement + 2, fixture.engine.mediaPrepareCount)
        }
    }

    private fun interruptRecovery(interruption: Interruption, failRecovery: Boolean = false) {
        RecoveryFixture(blockRecovery = true, failRecovery = failRecovery).use { fixture ->
            fixture.main { engine.play(VIDEO_A) }
            assertTrue("The real media refusal never entered fresh recovery",
                fixture.recoveryEntered.await(15, TimeUnit.SECONDS))
            assertEquals(listOf(VIDEO_A), fixture.freshReads.toList())
            assertEquals(1, fixture.refusals.count { it == VIDEO_A })

            // This joins the blocked producer only to observe its eventual settlement.
            // Cancelling playback must not need to cancel this shared extraction.
            val pending = fixture.extractor.extractFresh(VIDEO_A)
            val oldRecoveryJob = fixture.playbackJob()
            assertTrue("The recovery waiter must still be blocked", oldRecoveryJob.isActive)
            fixture.main {
                when (interruption) {
                    Interruption.REPLACE -> engine.play(VIDEO_B)
                    Interruption.CANCEL -> engine.cancelPending(pausePlayback = true)
                    Interruption.STOP -> engine.stop()
                }
            }
            if (interruption == Interruption.REPLACE) fixture.await {
                it.videoId == VIDEO_B && it.prepared
            }
            val preparesAfterInterruption = fixture.engine.mediaPrepareCount

            fixture.releaseRecovery.countDown()
            val producerResult = runCatching { pending.stream.get(15, TimeUnit.SECONDS) }
            if (failRecovery) {
                assertTrue("The independent extraction must settle with the controlled failure",
                    producerResult.exceptionOrNull() is IOException)
                assertEquals("Controlled late recovery failure", producerResult.exceptionOrNull()?.message)
            } else producerResult.getOrThrow()
            pending.metadata.get(15, TimeUnit.SECONDS)
            // Producer completion alone cannot order the Engine's IO -> Main cleanup.
            // Join its captured waiter before checking that neither success nor catch committed.
            runBlocking { withTimeout(15_000) { oldRecoveryJob.join() } }
            fixture.main {
                val state = engine.snapshot.value
                assertEquals("A late recovery must not submit another source",
                    preparesAfterInterruption, engine.mediaPrepareCount)
                assertNull(state.error)
                assertNull(state.failureReport)
                when (interruption) {
                    Interruption.REPLACE -> {
                        assertEquals(VIDEO_B, state.videoId)
                        assertTrue(state.prepared)
                        assertEquals(VIDEO_B, state.title)
                    }
                    Interruption.CANCEL -> {
                        assertEquals(VIDEO_A, state.videoId)
                        assertFalse(state.prepared)
                    }
                    Interruption.STOP -> {
                        assertNull(state.videoId)
                        assertEquals("", state.title)
                        assertFalse(state.prepared)
                        assertFalse(state.userWantsPlay)
                    }
                }
            }
            assertEquals(listOf(VIDEO_A), fixture.freshReads.toList())
        }
    }

    private enum class Interruption { REPLACE, CANCEL, STOP }

    private class RecoveryFixture(
        private val persistent: Boolean = false,
        private val blockRecovery: Boolean = false,
        private val failRecovery: Boolean = false,
        private val initialGood: Boolean = false,
        private val blockChapters: Boolean = false,
        private val castRefresh: Boolean = false,
    ) : Closeable {
        private val instrumentation = InstrumentationRegistry.getInstrumentation()
        private val app = instrumentation.targetContext
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val http = OkHttpClient()
        private val prefs = GlobalContext.get().get<ExtensionManager>()
        private val previous = listOf(PreferenceKeys.SKIP_SPONSORS, PreferenceKeys.SKIP_SELF_PROMO,
            PreferenceKeys.SKIP_POI_HIGHLIGHT).associateWith(prefs::isEnabled)
        val freshReads = ConcurrentLinkedQueue<String>()
        val refusals = ConcurrentLinkedQueue<String>()
        val recoveryEntered = CountDownLatch(1)
        val releaseRecovery = CountDownLatch(1)
        val chaptersEntered = CountDownLatch(1)
        val releaseChapters = CountDownLatch(1)
        private val audio = instrumentation.context.assets.open("player/audio-original/audio.ts").use { it.readBytes() }
        private val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
                    "/refused.mp4" -> MockResponse().setHeader("Content-Type", "application/json").setBody("error")
                    "/audio.ts" -> MockResponse().setHeader("Content-Type", "video/mp2t").setBody(Buffer().write(audio))
                    else -> MockResponse().setHeader("Content-Type", "application/vnd.apple.mpegurl").setBody("""
                        #EXTM3U
                        #EXT-X-TARGETDURATION:21
                        #EXTINF:20.0,
                        audio.ts
                        #EXT-X-ENDLIST
                    """.trimIndent())
                }
            }
            start()
        }
        private val session = YoutubeWebSession("attempt-fixture", WebAccount.ANONYMOUS, 0, null, null, null,
            "visitor", "fixture-UA", 0, null, "fixture", JsonObject(emptyMap())) { "" }
        private val bad = Stream().apply {
            formats = listOf(Format(url = server.url("/refused.mp4").toString(), height = 360, itag = 18,
                codec = "avc1.42001e,mp4a.40.2", mimeType = "video/mp4",
                requestPlan = MediaRequestPlan(session, StreamClient.WEB, MediaProtocol.HTTPS, MediaRange.HEADER)))
        }
        private val good = Stream().apply {
            hlsUrl = server.url("/master.m3u8").toString()
            hlsRequestPlan = MediaRequestPlan(session, StreamClient.VISIONOS, MediaProtocol.HLS, MediaRange.NONE)
        }
        val castAudioUrl: String get() = server.url("/fresh-audio.m4a").toString()
        private val plans = YoutubeMediaRequests({ true }, ExtractionDiagnostics())
        private val host = object : PlaybackHost {
            override fun context(videoId: String, fresh: Boolean, catalog: Boolean, demand: PlaybackDemand?,
                                 taskCurrent: () -> Boolean, deadlineNanos: Long, playbackPriority: Boolean,
                                 diagnosticContext: DiagnosticContext): ExtractionContext =
                ExtractionContext(session, object : ExtractionTransport {
                    override fun get(context: ExtractionContext, url: String, headers: Map<String, List<String>>): PageResponse =
                        error("Unexpected extraction HTTP")
                    override fun post(context: ExtractionContext, url: String, headers: Map<String, List<String>>, body: ByteArray): PageResponse =
                        error("Unexpected extraction HTTP")
                }, false, catalog, playbackPriority, "", deadlineNanos, taskCurrent, ExtractionDiagnostics())

            override fun resolve(videoId: String, context: ExtractionContext, demand: PlaybackDemand?, fresh: Boolean): MscPlayback.Resolved {
                check(fresh) { "Only fresh recovery may leave the cache fixture" }
                freshReads.add(videoId)
                if (blockRecovery) {
                    recoveryEntered.countDown()
                    check(releaseRecovery.await(15, TimeUnit.SECONDS)) { "Recovery gate was not released" }
                }
                if (failRecovery) throw IOException("Controlled late recovery failure")
                val stream = if (castRefresh) Stream().apply {
                    hlsUrl = good.hlsUrl
                    hlsRequestPlan = good.hlsRequestPlan
                    formats = listOf(Format(url = castAudioUrl, audioOnly = true, itag = 140,
                        codec = "mp4a.40.2", mimeType = "audio/mp4"))
                } else if (persistent) bad else good
                return MscPlayback.Resolved(Metadata().apply { id = videoId; title = videoId; duration = 20 }, stream)
            }
            override fun noteMediaRejected(videoId: String, url: String, reason: MediaRejection, diagnosticContext: DiagnosticContext?): StreamClient? {
                check(reason == MediaRejection.INVALID_BODY)
                refusals.add(videoId)
                return plans.plan(url)?.client
            }
            override fun chapters(videoId: String, context: ExtractionContext): List<Chapter> {
                if (!blockChapters || videoId != VIDEO_A) return emptyList()
                chaptersEntered.countDown()
                check(releaseChapters.await(30, TimeUnit.SECONDS)) { "Chapter gate was not released" }
                return listOf(Chapter(title = VIDEO_A, startSeconds = 0))
            }
            override fun deferredClients(videoId: String): Set<StreamClient> = emptySet()
            override fun cache(inner: Cache, context: () -> ExtractionContext): Cache = inner
            override fun invalidateStream(videoId: String) = Unit
            override fun scopeHint(): String = "attempt-fixture"
            override fun recoveryScope(): String = "attempt-fixture"
        }
        val extractor = Extractor(object : Cache by streamCache(bad) {
            override fun getMetadata(videoId: String): Metadata =
                Metadata().apply { id = videoId; title = videoId; duration = 20 }
            override fun getStream(videoId: String): Stream = if (!initialGood && (persistent || videoId == VIDEO_A)) bad else good
            override fun getChapters(videoId: String): ChapterList? = if (blockChapters) null else ChapterList()
        }, scope, host)
        lateinit var engine: PlaybackEngine
            private set
        private var player: ExoPlayer? = null

        init {
            previous.keys.forEach { prefs.setEnabled(it, false) }
            instrumentation.runOnMainSync {
                engine = PlaybackEngine(app, extractor, MediaSourceResolver(PlayerDataSource.create(app, http, plans)),
                    MemoryJsonCache(), prefs, SponsorBlockManager(http, prefs, scope))
                // Existing engine fixtures release the private ExoPlayer in teardown.
                player = engine.javaClass.getDeclaredField("player").apply { isAccessible = true }.get(engine) as ExoPlayer
            }
        }

        fun main(action: RecoveryFixture.() -> Unit) = instrumentation.runOnMainSync { action() }

        fun playbackJob(): Job {
            lateinit var captured: Job
            main {
                captured = engine.javaClass.getDeclaredField("playJob").apply { isAccessible = true }.get(engine) as Job
            }
            return captured
        }

        fun await(predicate: (PlaybackSnapshot) -> Boolean): PlaybackSnapshot = runBlocking {
            withTimeout(15_000) { engine.snapshot.first(predicate) }
        }

        fun awaitFailure(videoId: String) = await { it.videoId == videoId && it.error != null }

        fun assertExhausted(videoId: String, expectedRefusals: Int) {
            val state = engine.snapshot.value
            assertEquals(videoId, state.videoId)
            assertFalse(state.prepared)
            assertTrue(requireNotNull(state.failureReport).text.contains("full_refreshes: 1"))
            assertEquals(expectedRefusals, refusals.count { it == videoId })
        }

        override fun close() {
            releaseRecovery.countDown()
            releaseChapters.countDown()
            main { engine.stop(); player?.release() }
            scope.cancel()
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdown()
            server.shutdown()
            previous.forEach { (key, enabled) -> prefs.setEnabled(key, enabled) }
        }
    }

    private companion object {
        const val VIDEO_A = "attemptA001"
        const val VIDEO_B = "attemptB001"
    }
}
