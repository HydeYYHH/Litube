package com.hhst.youtubelite.extractor

import com.hhst.youtubelite.diagnostics.DiagnosticContext
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class RecoveryCoordinationTest {
    private val id = "abcdefghijk"
    private class Host(val load: (Int) -> Unit = {}) : PlaybackHost {
        val calls = AtomicInteger()
        private val session = YoutubeWebSession("recovery", WebAccount.ANONYMOUS, 0, null, null, null,
            "visitor", "fixture", 0, null, "fixture", JsonObject(emptyMap())) { "" }
        override fun context(videoId: String, fresh: Boolean, catalog: Boolean, demand: PlaybackDemand?,
            taskCurrent: () -> Boolean, deadlineNanos: Long, playbackPriority: Boolean, diagnosticContext: DiagnosticContext) =
            ExtractionContext(session, object : ExtractionTransport {
                override fun get(context: ExtractionContext, url: String, headers: Map<String, List<String>>): PageResponse = error("Unexpected GET")
                override fun post(context: ExtractionContext, url: String, headers: Map<String, List<String>>, body: ByteArray): PageResponse = error("Unexpected POST")
            }, false, catalog, playbackPriority, "", deadlineNanos, taskCurrent, ExtractionDiagnostics())
        override fun resolve(videoId: String, context: ExtractionContext, demand: PlaybackDemand?, fresh: Boolean): MscPlayback.Resolved {
            assertTrue(fresh)
            load(calls.incrementAndGet())
            return MscPlayback.Resolved(Metadata().apply { id = videoId }, Stream())
        }
        override fun chapters(videoId: String, context: ExtractionContext) = emptyList<Chapter>()
        override fun deferredClients(videoId: String) = emptySet<StreamClient>()
        override fun cache(inner: Cache, context: () -> ExtractionContext) = inner
        override fun invalidateStream(videoId: String) = Unit
        override fun scopeHint() = "scope"
        override fun recoveryScope() = "scope"
    }

    @Test fun automaticRecoveryStillCoalescesButUserRetryStartsANewLoad() = runTest {
        val host = Host()
        val extractor = Extractor(FakeCache(), this, host)
        val first = extractor.extractFresh(id)
        first.stream.await()
        assertSame(first.stream, extractor.extractFresh(id).stream)
        assertEquals(1, host.calls.get())
        extractor.resetRecovery(id)
        val retry = extractor.extractFresh(id)
        assertNotSame(first.stream, retry.stream)
        retry.stream.await()
        assertSame(retry.stream, extractor.extractFresh(id).stream)
        assertEquals(2, host.calls.get())
    }

    @Test fun oldCompletionCannotReplaceTheNewRecovery() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val host = Host { call -> if (call == 1) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) } }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val extractor = Extractor(FakeCache(), scope, host)
            val first = extractor.extractFresh(id)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            extractor.resetRecovery(id)
            val retry = extractor.extractFresh(id)
            retry.stream.await()
            release.countDown()
            runCatching { first.stream.await() }
            assertSame(retry.stream, extractor.extractFresh(id).stream)
            assertEquals(2, host.calls.get())
        } finally { release.countDown(); scope.cancel() }
    }
}
