package com.hhst.youtubelite.data.media

import com.hhst.youtubelite.watch.chat.MediaLiveChatGateway
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

/** Controlled scheduling fixtures; no public service or account is needed. */
class MediaClientSchedulingAndroidTest {
    @Test fun anIdempotentReadDoesNotRejoinTheQueueBehindLaterSlowReads() = runBlocking<Unit> {
        val entered = CountDownLatch(3)
        val captured = CountDownLatch(1)
        val finishCapture = CountDownLatch(1)
        val release = CountDownLatch(1)
        val reads = MediaClient(OkHttpClient())
        val active = (0 until 3).map {
            async(start = CoroutineStart.UNDISPATCHED) {
                reads.read { _, _ ->
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }
            }
        }
        val contexts = AtomicInteger()
        val client = MediaClient(OkHttpClient()) {
            if (contexts.incrementAndGet() == 1) {
                captured.countDown()
                check(finishCapture.await(10, TimeUnit.SECONDS))
            }
            RequestContext()
        }
        val read = async(start = CoroutineStart.UNDISPATCHED) { client.readIdempotent { _, _ -> "ready" } }
        var later: Deferred<Unit>? = null
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue(captured.await(5, TimeUnit.SECONDS))
            later = async(start = CoroutineStart.UNDISPATCHED) {
                reads.read { _, _ -> check(release.await(10, TimeUnit.SECONDS)); Unit }
            }
            finishCapture.countDown()
            assertEquals("ready", withTimeout(2_000) { read.await() })
        } finally {
            finishCapture.countDown()
            release.countDown()
            read.cancelAndJoin()
            later?.cancelAndJoin()
            active.forEach { it.cancelAndJoin() }
        }
    }

    @Test fun liveChatLeavesEveryReadWorkerAvailableBetweenPages() = pollingLeavesWorkersAvailable(replay = false)

    @Test fun chatReplayLeavesEveryReadWorkerAvailableBetweenPages() = pollingLeavesWorkersAvailable(replay = true)

    private fun pollingLeavesWorkersAvailable(replay: Boolean) = runBlocking<Unit> {
        val calls = AtomicInteger()
        val client = chatClient(calls, waitMs = 60_000)
        val gateway = MediaLiveChatGateway(client)
        val firstPage = CompletableDeferred<Unit>()
        val listener = launch {
            if (replay) gateway.replay("first", { 0L }) { firstPage.complete(Unit) }
            else gateway.listen("first") { firstPage.complete(Unit) }
        }
        val entered = CountDownLatch(4)
        val release = CountDownLatch(1)
        var readers = emptyList<Deferred<Unit>>()
        try {
            withTimeout(5_000) { firstPage.await() }
            readers = (0 until 4).map {
                async(start = CoroutineStart.UNDISPATCHED) {
                    client.read { _, _ ->
                        entered.countDown()
                        check(release.await(10, TimeUnit.SECONDS))
                        Unit
                    }
                }
            }
            assertTrue("Polling retained a MediaClient worker", entered.await(5, TimeUnit.SECONDS))
            listener.cancelAndJoin()
            assertEquals(1, calls.get())
        } finally {
            release.countDown()
            listener.cancelAndJoin()
            readers.forEach { it.cancelAndJoin() }
        }
    }

    @Test fun cancellingThePollDelayPreventsTheNextRequest() = runBlocking<Unit> {
        val calls = AtomicInteger()
        val gateway = MediaLiveChatGateway(chatClient(calls, waitMs = 100))
        val firstPage = CompletableDeferred<Unit>()
        val listener = launch { gateway.listen("first") { firstPage.complete(Unit) } }
        try {
            withTimeout(5_000) { firstPage.await() }
            listener.cancelAndJoin()
            delay(250)
            assertEquals(1, calls.get())
        } finally { listener.cancelAndJoin() }
    }

    @Test fun aSessionChangeBetweenPollsPreventsTheNextRequest() = runBlocking<Unit> {
        val calls = AtomicInteger()
        val generation = AtomicLong(1)
        val gateway = MediaLiveChatGateway(chatClient(calls, waitMs = 100) { generation.get() })
        val firstPage = CompletableDeferred<Unit>()
        val listener = async {
            runCatching { gateway.listen("first") { firstPage.complete(Unit) } }
        }
        try {
            withTimeout(5_000) { firstPage.await() }
            generation.set(2)
            assertTrue(withTimeout(5_000) { listener.await() }.exceptionOrNull() is CancellationException)
            assertEquals(1, calls.get())
        } finally { listener.cancelAndJoin() }
    }

    private fun chatClient(calls: AtomicInteger, waitMs: Long, generation: () -> Long = { 1 }): MediaClient {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            val body = """{"continuationContents":{"liveChatContinuation":{"continuations":[{"timedContinuationData":{"continuation":"next","timeoutMs":$waitMs}}]}}}"""
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(body.toResponseBody()).build()
        }.build()
        return MediaClient(http) { RequestContext(generation = generation()) }
    }
}
