package com.hhst.youtubelite.extractor

import com.liskovsoft.youtubeapi.videoinfo.V2.RequestContext
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class PlaybackSharedCancellationTest {
    private val id = "abcdefghijk"
    private val solver = object : SignatureSolver {
        override fun signatureTimestamp(playerUrl: String, context: ExtractionContext) = 1
        override fun playerScript(playerUrl: String, context: ExtractionContext) = "signatureTimestamp:1"
        override fun solve(playerUrl: String, signatures: Set<String>, throttles: Set<String>, context: ExtractionContext) =
            SignatureSolver.Solutions(emptyMap(), emptyMap())
    }
    private val tokens = object : PoTokenMinter {
        override fun mint(request: PoTokenMinter.TokenRequest, context: ExtractionContext): PoTokenMinter.Token =
            error("Direct playback must not mint a token")
        override fun invalidate(scope: String) = Unit
    }

    @Test fun cancellingASharedPrefetchDoesNotFailTheStillCurrentForeground() {
        val playback = MscPlayback(solver, tokens, { RequestContext(visitor = "visitor") })
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val producerCurrent = AtomicBoolean(true)
        val producerCalls = AtomicInteger()
        val foregroundCalls = AtomicInteger()
        val foregroundThread = AtomicReference<Thread>()
        val producer = context({ producerCurrent.get() }) { task ->
            producerCalls.incrementAndGet()
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            task.check()
            playable()
        }
        val foreground = context({ true }) { foregroundCalls.incrementAndGet(); playable() }
        val workers = Executors.newFixedThreadPool(2)
        try {
            val prefetched = workers.submit<Result<MscPlayback.Resolved>> {
                runCatching { playback.resolve(id, producer, PlaybackDemand(0, null, false, emptySet()), false, emptySet()) }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val selected = workers.submit<Result<MscPlayback.Resolved>> {
                foregroundThread.set(Thread.currentThread())
                runCatching { playback.resolve(id, foreground, PlaybackDemand(1080, null, false, emptySet()), false, emptySet()) }
            }
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (System.nanoTime() < until && foregroundThread.get()?.stackTrace?.none {
                    it.className.endsWith("PlaybackLock\$Slot") && it.methodName == "await"
                } != false) Thread.sleep(1)
            assertTrue("foreground must have joined the producer", foregroundThread.get()?.stackTrace?.any {
                it.className.endsWith("PlaybackLock\$Slot") && it.methodName == "await"
            } == true)
            producerCurrent.set(false)
            release.countDown()
            assertTrue(prefetched.get(5, TimeUnit.SECONDS).isFailure)
            val result = selected.get(5, TimeUnit.SECONDS)
            assertTrue("foreground inherited cancelled producer: ${result.exceptionOrNull()}", result.isSuccess)
            assertEquals(setOf(StreamClient.VISIONOS), result.getOrThrow().stream.formats.map { it.requestPlan?.client }.toSet())
            assertEquals(1, producerCalls.get())
            assertEquals(1, foregroundCalls.get())
            // The recovered result remains reusable; recovery must not disable the result cache.
            playback.resolve(id, context({ true }) { error("successful stream should be cached") }, null, false, emptySet())
        } finally {
            release.countDown()
            workers.shutdownNow()
        }
    }

    private fun playable() = PageResponse(200, emptyMap(), """{
        "playabilityStatus":{"status":"OK"},"streamingData":{"adaptiveFormats":[
            {"itag":137,"mimeType":"video/mp4","height":1080,"url":"https://media.example.test/video"},
            {"itag":140,"mimeType":"audio/mp4","url":"https://media.example.test/audio"}]}}""",
        "https://www.youtube.com/youtubei/v1/player")

    private fun context(current: () -> Boolean, response: (ExtractionContext) -> PageResponse): ExtractionContext {
        val session = YoutubeWebSession("same-session", WebAccount.ANONYMOUS, 0, null, null, null, "visitor",
            "agent", 0, "https://www.youtube.com/s/player/test/base.js", "test", JsonObject(emptyMap())) { "" }
        val transport = object : ExtractionTransport {
            override fun get(context: ExtractionContext, url: String, headers: Map<String, List<String>>) = response(context)
            override fun post(context: ExtractionContext, url: String, headers: Map<String, List<String>>, body: ByteArray) = response(context)
        }
        return ExtractionContext(session, transport, false, false, true, "",
            System.nanoTime() + TimeUnit.SECONDS.toNanos(10), current, object : EngineDiagnostics {
                override fun event(stage: String, profile: String, detail: String, elapsedMillis: Long, httpStatus: Int) = Unit
            })
    }
}
