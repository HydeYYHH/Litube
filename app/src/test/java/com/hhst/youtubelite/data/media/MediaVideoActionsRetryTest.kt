package com.hhst.youtubelite.data.media

import com.hhst.youtubelite.watch.domain.UnconfirmedActionException
import com.hhst.youtubelite.watch.domain.VideoRating
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class MediaVideoActionsRetryTest {
    private val initial = RequestContext(accountId = "primary", accessToken = "access", generation = 7)
    private val ambiguous = """{"responseContext":{},"frameworkUpdates":{}}"""
    private fun response(request: Request, body: String, status: Int = 200) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(status).message("test").body(body.toResponseBody()).build()
    private fun client(context: () -> RequestContext = { initial }, reply: (Request) -> Response) =
        MediaClient(OkHttpClient.Builder().addInterceptor { chain -> reply(chain.request()) }.build(), context)
    private fun observation(subscribe: Boolean, reached: Boolean) = if (subscribe)
        """{"subscribeButtonRenderer":{"channelId":"UCabcdefghijk","subscribed":$reached}}"""
    else """{"likeStatusEntity":{"likeStatus":"${if (reached) "LIKE" else "INDIFFERENT"}"}}"""

    @Test fun oneBusinessRetryCanSetEitherDesiredStateWithoutHttpReplay() = runBlocking {
        for (subscribe in listOf(false, true)) {
            val writes = AtomicInteger()
            val reads = AtomicInteger()
            val bodies = CopyOnWriteArrayList<String>()
            var invalidations = 0
            val actions = MediaVideoActions(client { request ->
                if (request.url.encodedPath.endsWith(if (subscribe) "/browse" else "/next")) {
                    reads.incrementAndGet()
                    response(request, observation(subscribe, false))
                } else {
                    assertTrue(request.body!!.isOneShot())
                    val buffer = okio.Buffer()
                    request.body!!.writeTo(buffer)
                    bodies += buffer.readUtf8()
                    response(request, if (writes.incrementAndGet() == 1) ambiguous else """{"success":true}""")
                }
            }) { invalidations++ }
            if (subscribe) actions.subscribe("UCabcdefghijk", true)
            else actions.rate("abcdefghijk", VideoRating.Like, VideoRating.None)
            assertEquals(2, writes.get())
            assertEquals(2, reads.get())
            assertEquals(1, invalidations)
            assertEquals(bodies.first(), bodies.last())
        }
    }

    @Test fun aDelayedVisibleChangeSettlesBeforeAnyBusinessResend() = runBlocking {
        val writes = AtomicInteger()
        val reads = AtomicInteger()
        val actions = MediaVideoActions(client { request ->
            if (request.url.encodedPath.endsWith("/next")) response(request, observation(false, reads.incrementAndGet() == 2))
            else { writes.incrementAndGet(); response(request, ambiguous) }
        })
        actions.rate("abcdefghijk", VideoRating.Like, VideoRating.None)
        assertEquals(1, writes.get())
        assertEquals(2, reads.get())
    }

    @Test fun exhaustedOldStateStaysUnconfirmedWithBoundedAttempts() = runBlocking {
        val writes = AtomicInteger()
        val reads = AtomicInteger()
        var invalidations = 0
        val actions = MediaVideoActions(client { request ->
            if (request.url.encodedPath.endsWith("/next")) {
                reads.incrementAndGet(); response(request, observation(false, false))
            } else { writes.incrementAndGet(); response(request, ambiguous) }
        }) { invalidations++ }
        val failure = runCatching { actions.rate("abcdefghijk", VideoRating.Like, VideoRating.None) }.exceptionOrNull() as UnconfirmedActionException
        assertEquals(2, writes.get())
        assertEquals(4, reads.get())
        assertEquals(0, invalidations)
        assertEquals("2", failure.detail["write_attempts"])
        assertEquals("4", failure.detail["readback_attempts"])
        assertEquals("INDIFFERENT", failure.detail["observed_rating"])
    }

    @Test fun anAmbiguousTransportWriteReadsBeforeDecidingWhetherToResend() = runBlocking {
        for (committed in listOf(false, true)) {
            val writes = AtomicInteger()
            val reads = AtomicInteger()
            val actions = MediaVideoActions(client { request ->
                if (request.url.encodedPath.endsWith("/next")) {
                    reads.incrementAndGet(); response(request, observation(false, committed))
                } else if (writes.incrementAndGet() == 1) throw SocketException("connection reset")
                else response(request, """{"success":true}""")
            })
            actions.rate("abcdefghijk", VideoRating.Like, VideoRating.None)
            assertEquals(if (committed) 1 else 2, writes.get())
            assertEquals(if (committed) 1 else 2, reads.get())
        }
    }

    @Test fun changingAccountsDuringConfirmationCannotRetryUnderTheNewIdentity() = runBlocking {
        val context = AtomicReference(initial)
        val writes = AtomicInteger()
        val actions = MediaVideoActions(client(context::get) { request ->
            if (request.url.encodedPath.endsWith("/next")) {
                context.set(initial.copy(accountId = "other", accessToken = "other-access", generation = 8))
                response(request, observation(false, false))
            } else { writes.incrementAndGet(); response(request, ambiguous) }
        })
        val failure = runCatching { actions.rate("abcdefghijk", VideoRating.Like, VideoRating.None) }.exceptionOrNull()
        assertTrue(failure.toString(), failure is CancellationException)
        assertEquals(1, writes.get())
    }

    @Test fun cancellationDoesNotRetryOrInvalidateAnAmbiguousWrite() = runBlocking {
        val reading = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val writes = AtomicInteger()
        var invalidations = 0
        val actions = MediaVideoActions(client { request ->
            if (request.url.encodedPath.endsWith("/next")) {
                reading.complete(Unit)
                release.await(2, TimeUnit.SECONDS)
                response(request, observation(false, false))
            } else { writes.incrementAndGet(); response(request, ambiguous) }
        }) { invalidations++ }
        val task = async(Dispatchers.Default) { runCatching { actions.rate("abcdefghijk", VideoRating.Like, VideoRating.None) } }
        withTimeout(5_000) { reading.await() }
        task.cancel()
        release.countDown()
        task.join()
        assertEquals(1, writes.get())
        assertEquals(0, invalidations)
    }

    @Test fun aNewerIntentSupersedesAnOldRetryAcrossCallers() = runBlocking {
        val firstRead = CompletableDeferred<Unit>()
        val writes = CopyOnWriteArrayList<String>()
        var invalidations = 0
        val actions = MediaVideoActions(client { request ->
            if (request.url.encodedPath.endsWith("/next")) {
                firstRead.complete(Unit); response(request, observation(false, false))
            } else {
                writes += request.url.encodedPath
                response(request, if (request.url.encodedPath.endsWith("/like/like")) ambiguous else """{"success":true}""")
            }
        }) { invalidations++ }
        val old = async(Dispatchers.Default) { runCatching { actions.rate("abcdefghijk", VideoRating.Like, VideoRating.None) } }
        withTimeout(5_000) { firstRead.await() }
        val newer = async(start = CoroutineStart.UNDISPATCHED) { actions.rate("abcdefghijk", VideoRating.Dislike, VideoRating.None) }
        withTimeout(5_000) { newer.await() }
        assertTrue(old.await().exceptionOrNull() is CancellationException)
        assertEquals(listOf("/youtubei/v1/like/like", "/youtubei/v1/like/dislike"), writes)
        assertEquals(1, invalidations)
    }

    @Test fun anOldLateConfirmationCannotInvalidateTheNewIntentsCache() = runBlocking {
        val reading = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val writes = AtomicInteger()
        var invalidations = 0
        val sharedClient = client { request ->
            if (request.url.encodedPath.endsWith("/next")) {
                reading.complete(Unit)
                release.await(2, TimeUnit.SECONDS)
                response(request, observation(false, true))
            } else response(request, if (writes.incrementAndGet() == 1) ambiguous else """{"success":true}""")
        }
        val actions = MediaVideoActions(sharedClient) { invalidations++ }
        val otherCaller = MediaVideoActions(sharedClient) { invalidations++ }
        val old = async(Dispatchers.Default) { runCatching { actions.rate("abcdefghijk", VideoRating.Like, VideoRating.None) } }
        withTimeout(5_000) { reading.await() }
        val newer = async(start = CoroutineStart.UNDISPATCHED) { otherCaller.rate("abcdefghijk", VideoRating.Dislike, VideoRating.None) }
        release.countDown()
        withTimeout(5_000) { newer.await() }
        assertTrue(old.await().exceptionOrNull() is CancellationException)
        assertEquals(2, writes.get())
        assertEquals(1, invalidations)
    }

    @Test fun explicitRefusalRateLimitAndTrafficChallengeStaySingleAttempt() = runBlocking {
        for (status in listOf(400, 401, 403, 429)) {
            val calls = AtomicInteger()
            val actions = MediaVideoActions(client { request -> calls.incrementAndGet(); response(request, "{}", status) })
            assertTrue(runCatching { actions.subscribe("UCabcdefghijk", true) }.isFailure)
            assertEquals(1, calls.get())
        }
        val calls = AtomicInteger()
        val actions = MediaVideoActions(client { request ->
            calls.incrementAndGet()
            response(request, "{}", 302).newBuilder().header("Location", "https://www.google.com/sorry/index").build()
        })
        assertTrue(runCatching { actions.subscribe("UCabcdefghijk", true) }.isFailure)
        assertEquals(1, calls.get())
    }

    @Test fun anUnknownReadbackIsNotTreatedAsAnExplicitOldState() = runBlocking {
        val writes = AtomicInteger()
        val reads = AtomicInteger()
        val actions = MediaVideoActions(client { request ->
            if (request.url.encodedPath.endsWith("/next")) {
                reads.incrementAndGet(); response(request, "{}")
            } else { writes.incrementAndGet(); response(request, ambiguous) }
        })
        val failure = runCatching { actions.rate("abcdefghijk", VideoRating.Like, VideoRating.None) }.exceptionOrNull() as UnconfirmedActionException
        assertEquals(1, writes.get())
        assertEquals(2, reads.get())
        assertEquals("unknown", failure.detail["observed_rating"])
    }

    @Test fun aNewAccountDoesNotWaitForTheOldAccountsNonCooperativeHttpCall() = runBlocking {
        val context = AtomicReference(initial)
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val client = client(context::get) { request ->
            if (request.header("Authorization") == "Bearer access") {
                entered.complete(Unit)
                while (release.count > 0) try { release.await(2, TimeUnit.SECONDS) } catch (_: InterruptedException) { }
            }
            response(request, """{"success":true}""")
        }
        val actions = MediaVideoActions(client)
        val old = async(Dispatchers.Default) { runCatching { actions.rate("abcdefghijk", VideoRating.Like, VideoRating.None) } }
        withTimeout(5_000) { entered.await() }
        context.set(initial.copy(accountId = "other", accessToken = "other-access", generation = 8))
        try {
            withTimeout(1_000) { actions.rate("abcdefghijk", VideoRating.Dislike, VideoRating.None) }
        } finally { release.countDown() }
        assertTrue(old.await().exceptionOrNull() is CancellationException)
    }

    @Test fun responseSizePolicyErrorsNeverBecomeRetryableTransportFailures() = runBlocking {
        for (duringReadback in listOf(false, true)) {
            val writes = AtomicInteger()
            val calls = AtomicInteger()
            val actions = MediaVideoActions(client { request ->
                calls.incrementAndGet()
                val reading = request.url.encodedPath.endsWith("/next")
                if (!reading) writes.incrementAndGet()
                response(request, if (duringReadback && !reading) ambiguous else "x".repeat(8 * 1024 * 1024 + 1))
            })
            val failure = runCatching { actions.rate("abcdefghijk", VideoRating.Like, VideoRating.None) }.exceptionOrNull() as MediaException
            assertEquals(com.liskovsoft.youtubeapi.videoinfo.V2.RequestFailure.PARSE, failure.failure.kind)
            assertEquals("response_too_large", failure.failure.code)
            assertEquals(1, writes.get())
            assertEquals(if (duringReadback) 2 else 1, calls.get())
        }
    }
}
