package com.hhst.youtubelite.extractor

import kotlinx.serialization.json.JsonObject
import com.hhst.youtubelite.diagnostics.DiagnosticContext
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class ExtractionFailureTest {
    private fun assertOriginal(failure: Throwable?) {
        assertTrue(failure is IOException)
        assertEquals("original_failure", failure?.message)
    }
    private class Host(private val failContext: Boolean, private val entered: CountDownLatch? = null,
        private val release: CountDownLatch? = null) : PlaybackHost {
        @Volatile var captured: ExtractionContext? = null
        val contextCalls = AtomicInteger()
        val resolveCalls = AtomicInteger()
        val failure = IOException("original_failure")
        override fun context(videoId: String, fresh: Boolean, catalog: Boolean, demand: PlaybackDemand?,
            taskCurrent: () -> Boolean, deadlineNanos: Long, playbackPriority: Boolean, diagnosticContext: DiagnosticContext): ExtractionContext {
            contextCalls.incrementAndGet()
            if (failContext) throw failure
            val session = YoutubeWebSession("test", WebAccount.ANONYMOUS, 0, null, null, null, null,
                "agent", 0, null, "test", JsonObject(emptyMap())) { "" }
            val transport = object : ExtractionTransport {
                override fun get(context: ExtractionContext, url: String, headers: Map<String, List<String>>): PageResponse = throw failure
                override fun post(context: ExtractionContext, url: String, headers: Map<String, List<String>>, body: ByteArray): PageResponse = throw failure
            }
            return ExtractionContext(session, transport, false, catalog, playbackPriority, "", deadlineNanos,
                taskCurrent, object : EngineDiagnostics {
                    override fun event(stage: String, profile: String, detail: String, elapsedMillis: Long, httpStatus: Int) = Unit
                }).also { captured = it }
        }
        override fun resolve(videoId: String, context: ExtractionContext, demand: PlaybackDemand?, fresh: Boolean): MscPlayback.Resolved {
            resolveCalls.incrementAndGet()
            entered?.countDown()
            release?.let { check(it.await(5, TimeUnit.SECONDS)) }
            throw failure
        }
        override fun chapters(videoId: String, context: ExtractionContext) = emptyList<Chapter>()
        override fun deferredClients(videoId: String) = emptySet<StreamClient>()
        override fun cache(inner: Cache, context: () -> ExtractionContext): Cache = object : Cache by inner {
            override fun getMetadata(videoId: String): Metadata? { context(); return inner.getMetadata(videoId) }
            override fun getStream(videoId: String): Stream? { context(); return inner.getStream(videoId) }
            override fun getChapters(videoId: String): ChapterList? { context(); return inner.getChapters(videoId) }
        }
        override fun invalidateStream(videoId: String) = Unit
        override fun scopeHint() = "test"
        override fun recoveryScope() = "test"
    }

    @Test fun contextFailureIsSharedByAllDependentReads() = runBlocking {
        val host = Host(failContext = true)
        val extraction = Extractor(FakeCache(), host = host).extract("abcdefghijk")
        assertOriginal(runCatching { extraction.metadata.await() }.exceptionOrNull())
        assertOriginal(runCatching { extraction.stream.await() }.exceptionOrNull())
        assertOriginal(runCatching { extraction.chapters.await() }.exceptionOrNull())
        assertEquals(1, host.contextCalls.get())
        assertEquals(0, host.resolveCalls.get())
    }

    @Test fun resolutionFailureCannotBeRetriedByACompanionPromise() = runBlocking {
        val host = Host(failContext = false)
        val extraction = Extractor(FakeCache(), host = host).extract("abcdefghijk")
        assertOriginal(runCatching { extraction.metadata.await() }.exceptionOrNull())
        assertOriginal(runCatching { extraction.stream.await() }.exceptionOrNull())
        extraction.chapters.await()
        assertEquals(1, host.contextCalls.get())
        assertEquals(1, host.resolveCalls.get())
    }

    @Test fun anExplicitFreshAttemptCanRetryTheFailure() = runBlocking {
        val host = Host(failContext = false)
        val extractor = Extractor(FakeCache(), host = host)
        val first = extractor.extract("abcdefghijk")
        runCatching { first.stream.await() }
        runCatching { first.metadata.await() }
        val fresh = extractor.extractFresh("abcdefghijk")
        assertOriginal(runCatching { fresh.stream.await() }.exceptionOrNull())
        assertOriginal(runCatching { fresh.metadata.await() }.exceptionOrNull())
        assertEquals(2, host.resolveCalls.get())
    }

    @Test fun playbackPromotesTheSharedContextOfAnActivePrefetch() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val host = Host(false, entered, release)
        val extractor = Extractor(FakeCache(), host = host)
        val prefetch = extractor.prefetch("abcdefghijk", null)
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val context = requireNotNull(host.captured)
            assertFalse(context.playbackPriority)
            val playback = extractor.extract("abcdefghijk")
            assertSame(prefetch.extraction.stream, playback.stream)
            assertTrue(context.playbackPriority)
            release.countDown()
            assertOriginal(runCatching { playback.stream.await() }.exceptionOrNull())
            assertEquals(1, host.resolveCalls.get())
        } finally { release.countDown(); prefetch.cancel() }
    }
}
