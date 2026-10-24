package com.hhst.youtubelite.data.media

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

/**
 * Routes every request offline by URL path: the seeded watch resolution answers, the global
 * shorts feed is rejected, so the tests prove which request the repository issues first and
 * that a rejected feed cannot block a valid seeded entry.
 */
class MediaShortsRepositoryTest {
    private class Routed(val routes: Map<String, Pair<Int, String>>, val calls: MutableList<String>) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val path = request.url.encodedPath
            synchronized(calls) { calls += path }
            val (code, body) = routes.entries.firstOrNull { path.endsWith(it.key) }
                ?.value ?: (502 to "")
            return Response.Builder()
                .request(request)
                .protocol(okhttp3.Protocol.HTTP_1_1)
                .code(code)
                .message(if (code == 200) "OK" else "Rejected")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }
    }

    private fun routedClient(routes: Map<String, Pair<Int, String>>): Pair<MediaClient, MutableList<String>> {
        val calls = mutableListOf<String>()
        return MediaClient(OkHttpClient.Builder().addInterceptor(Routed(routes, calls)).build()) to calls
    }

    @Test fun aSeededEntryResolvesFirstAndSurvivesARejectedGlobalFeed() = runBlocking {
        val (client, calls) = routedClient(mapOf(
            // The watch `next` resolution succeeds; every browse/reel request is rejected.
            "/next" to (200 to """{"videoDetails":{"videoId":"seedShort1","title":"Seeded short","author":"Blender"}}"""),
        ))
        val page = MediaShortsRepository(client, startId = "seedShort1").shorts()
        // Identity: the seeded video is the pager's first item, not a stand-in video.
        assertEquals("seedShort1", page.items.firstOrNull()?.id)
        assertEquals("Seeded short", page.items.first().title)
        // Order: the seeded watch resolution is the first request; the global feed never blocks it.
        assertTrue("expected the watch resolution first, got $calls", calls.first().endsWith("/next"))
    }

    @Test fun aFailedSeedFailsTheEntryInsteadOfPlayingAnotherVideo() = runBlocking {
        val (client, _) = routedClient(mapOf("/next" to (400 to "{}")))
        val failure = runCatching { MediaShortsRepository(client, startId = "seedShort1").shorts() }
            .exceptionOrNull()
        assertTrue("expected a MediaException for the failed seed", failure is MediaException)
    }

    @Test fun aSeedlessEntryStillReportsTheGlobalFeedFailure() = runBlocking {
        val (client, _) = routedClient(mapOf("/reel/reel_watch_sequence" to (400 to "{}")))
        val failure = runCatching { MediaShortsRepository(client).shorts() }.exceptionOrNull()
        assertTrue("seedless entries keep the honest failure", failure is MediaException)
    }
}
