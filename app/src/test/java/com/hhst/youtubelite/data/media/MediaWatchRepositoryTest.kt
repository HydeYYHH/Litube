package com.hhst.youtubelite.data.media

import com.liskovsoft.youtubeapi.videoinfo.V2.RequestContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Page and player-details run in parallel on one session; first paint pays the slower call, not their sum. */
class MediaWatchRepositoryTest {
    @Test fun aCancelledWatchInterruptsBothReads() = interruptedPair(false)
    @Test fun aFailedPageInterruptsItsPendingDetailsRead() = interruptedPair(true)

    private fun interruptedPair(failPage: Boolean) = runBlocking {
        val started = CountDownLatch(2)
        val interrupted = CountDownLatch(if (failPage) 1 else 2)
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            started.countDown()
            try {
                check(started.await(3, TimeUnit.SECONDS))
                val fails = failPage && chain.request().url.encodedPath.endsWith("/next")
                if (!fails) Thread.sleep(10_000)
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(400).message("Rejected")
                    .body("{}".toResponseBody("application/json".toMediaType())).build()
            } catch (cancelled: InterruptedException) {
                interrupted.countDown()
                throw IOException(cancelled)
            }
        }.build()
        val request = async { runCatching { MediaWatchRepository(MediaClient(http)).watch("bunny") } }
        assertTrue(withContext(Dispatchers.IO) { started.await(3, TimeUnit.SECONDS) })
        if (failPage) assertTrue(request.await().exceptionOrNull() is MediaException) else request.cancelAndJoin()
        assertTrue("Cancelled reads must not keep a worker occupied", withContext(Dispatchers.IO) { interrupted.await(3, TimeUnit.SECONDS) })
    }
    private class Routed(
        val routes: Map<String, Pair<Int, String>>,
        val calls: MutableList<String>,
        val visitors: MutableList<String?>,
        /** Holds every request until a second is in flight; proves the reads overlap. */
        val rendezvous: Boolean = false,
    ) : Interceptor {
        var maxInFlight = 0
            private set
        private var inFlight = 0
        private val bothInFlight = CountDownLatch(2)

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val path = request.url.encodedPath
            synchronized(calls) {
                calls += path
                visitors += request.header("X-Goog-Visitor-Id")
                inFlight++
                if (inFlight > maxInFlight) maxInFlight = inFlight
            }
            try {
                if (rendezvous) bothInFlight.await(5, TimeUnit.SECONDS)
                val (code, body) = routes.entries.firstOrNull { path.endsWith(it.key) }?.value ?: (502 to "")
                return Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message(if (code == 200) "OK" else "Rejected")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            } finally {
                synchronized(calls) { inFlight-- }
            }
        }
    }

    private val nextPage = """{"videoDetails":{"videoId":"bunny","title":"Big Buck Bunny","author":"Blender","channelId":"UCblender"}}"""
    private val playerDetails = """{"videoDetails":{"videoId":"bunny","title":"Player title",
        "thumbnail":{"thumbnails":[{"url":"https://i.ytimg.com/small.jpg"},{"url":"https://i.ytimg.com/thumb.jpg"}]},
        "isLive":true},
        "playbackTracking":{"videostatsPlaybackUrl":{"baseUrl":"https://www.youtube.com/api/stats/playback"},
        "videostatsWatchtimeUrl":{"baseUrl":"https://s.youtube.com/api/stats/watchtime"}}}"""

    private fun routedClient(routes: Map<String, Pair<Int, String>>, rendezvous: Boolean = false):
        Quad<MediaWatchRepository, MutableList<String>, MutableList<String?>, Routed> {
        val calls = mutableListOf<String>()
        val visitors = mutableListOf<String?>()
        val interceptor = Routed(routes, calls, visitors, rendezvous)
        val client = MediaClient(OkHttpClient.Builder().addInterceptor(interceptor).build()) {
            RequestContext(visitor = "visitor-token", generation = 7)
        }
        return Quad(MediaWatchRepository(client), calls, visitors, interceptor)
    }

    private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    @Test fun theWatchPageCombinesBothReadsOnTheOneSession() = runBlocking {
        val (repository, calls, visitors, _) = routedClient(mapOf("/next" to (200 to nextPage), "/player" to (200 to playerDetails)))
        val page = repository.watch("bunny")
        // The page read owns identity fields; details only fill the gaps.
        assertEquals("Big Buck Bunny", page.video.title)
        assertEquals("Blender", page.video.channel)
        assertEquals("https://i.ytimg.com/thumb.jpg", page.video.artwork)
        assertTrue(page.video.live)
        assertEquals("https://www.youtube.com/api/stats/playback", page.video.tracking?.playbackUrl)
        assertEquals("https://s.youtube.com/api/stats/watchtime", page.video.tracking?.watchTimeUrl)
        assertEquals(listOf("next", "player").sorted(), calls.map { it.substringAfterLast('/') }.sorted())
        assertEquals(listOf("visitor-token", "visitor-token"), visitors)
    }

    @Test fun thePageAndDetailsRequestsOverlap() = runBlocking {
        val (repository, calls, _, interceptor) = routedClient(mapOf("/next" to (200 to nextPage), "/player" to (200 to playerDetails)),
            rendezvous = true)
        val page = repository.watch("bunny")
        assertEquals("Big Buck Bunny", page.video.title)
        assertEquals("https://i.ytimg.com/thumb.jpg", page.video.artwork)
        assertEquals(setOf("next", "player"), calls.map { it.substringAfterLast('/') }.toSet())
        assertEquals(2, interceptor.maxInFlight)
    }

    @Test fun aFailedPageReadFailsEvenWhenDetailsSucceed() = runBlocking {
        val (repository, _, _, _) = routedClient(mapOf("/next" to (400 to "{}"), "/player" to (200 to playerDetails)))
        val failure = runCatching { repository.watch("bunny") }.exceptionOrNull()
        assertTrue("expected a MediaException for the failed page read", failure is MediaException)
    }

    @Test fun aRejectedDetailsResponseStaysTolerated() = runBlocking {
        val (repository, _, _, _) = routedClient(mapOf("/next" to (200 to nextPage), "/player" to (400 to "{}")))
        val page = repository.watch("bunny")
        assertEquals("Big Buck Bunny", page.video.title)
        assertEquals("", page.video.artwork)
        assertEquals(false, page.video.live)
        assertNull(page.video.tracking)
    }
}
