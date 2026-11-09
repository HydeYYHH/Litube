package com.hhst.youtubelite.extractor

import com.hhst.youtubelite.diagnostics.DiagnosticContext
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Speculative chapter reads stay behind the stream promise so they never share the foreground request window; an explicit chapter consumer still starts the read on demand. */
class ExtractorChapterSchedulingTest {
    private class GatedHost : PlaybackHost {
        val streamGate = CompletableDeferred<Unit>()
        val chaptersCalls = AtomicInteger(0)
        private val diagnostics = object : EngineDiagnostics {
            override fun event(stage: String, profile: String, detail: String, elapsedMillis: Long, httpStatus: Int) {}
        }
        override fun context(videoId: String, fresh: Boolean, catalog: Boolean, demand: PlaybackDemand?,
                             taskCurrent: () -> Boolean, deadlineNanos: Long, playbackPriority: Boolean,
                             diagnosticContext: DiagnosticContext): ExtractionContext =
            ExtractionContext(YoutubeWebSession("offline", WebAccount.ANONYMOUS, 0, null, null, null, null, "UA", 0, null, "test", kotlinx.serialization.json.JsonObject(emptyMap())) { "" },
                object : ExtractionTransport {
                    override fun get(context: ExtractionContext, url: String, headers: Map<String, List<String>>) = throw IOException("offline scheduling test")
                    override fun post(context: ExtractionContext, url: String, headers: Map<String, List<String>>, body: ByteArray) = throw IOException("offline scheduling test")
                },
                false, catalog, playbackPriority, "", deadlineNanos, taskCurrent, diagnostics)
        override fun resolve(videoId: String, context: ExtractionContext, demand: PlaybackDemand?, fresh: Boolean): MscPlayback.Resolved {
            kotlinx.coroutines.runBlocking { streamGate.await() }
            val stream = Stream(formats = emptyList())
            return MscPlayback.Resolved(Metadata(videoId), stream)
        }
        override fun chapters(videoId: String, context: ExtractionContext): List<Chapter> {
            chaptersCalls.incrementAndGet()
            return listOf(Chapter("c", 0, null))
        }
        override fun deferredClients(videoId: String): Set<StreamClient> = emptySet()
        override fun cache(inner: Cache, context: () -> ExtractionContext): Cache = inner
        override fun invalidateStream(videoId: String) {}
        override fun scopeHint(): String = "offline"
        override fun recoveryScope(): String = "offline"
    }

    @Test fun speculativeChapterReadsWaitForTheStreamPromise() {
        val host = GatedHost()
        val extraction = Extractor(FakeCache(), host = host).extract("abcdefghijk")
        // The stream is still gated: no chapter read may have started behind it.
        Thread.sleep(200)
        assertEquals(0, host.chaptersCalls.get())
        assertFalse(extraction.chapters.done)
        host.streamGate.complete(Unit)
        val chapters = extraction.chapters.get(5, TimeUnit.SECONDS)
        assertEquals(1, host.chaptersCalls.get())
        assertEquals(1, chapters.chapters.size)
    }

    @Test fun anExplicitChapterConsumerStillStartsTheReadOnDemand() {
        val host = GatedHost()
        val extraction = Extractor(FakeCache(), host = host).extract("abcdefghijk")
        val chapters = extraction.chapters.get(5, TimeUnit.SECONDS)
        assertEquals(1, host.chaptersCalls.get())
        assertEquals(1, chapters.chapters.size)
        host.streamGate.complete(Unit)
        assertTrue(extraction.stream.get(5, TimeUnit.SECONDS).formats.isEmpty())
    }
}
