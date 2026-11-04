package com.hhst.youtubelite.data.media

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

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

    @Test fun aSeededEntryAttachesWithoutReadingMetadataOrTheGlobalFeed() = runBlocking {
        val (client, calls) = routedClient(emptyMap())
        val page = MediaShortsRepository(client, startId = "seedShort1").shorts()
        assertEquals("seedShort1", page.items.firstOrNull()?.id)
        assertTrue(page.items.first().title.isEmpty())
        assertEquals("https://i.ytimg.com/vi/seedShort1/hqdefault.jpg", page.items.first().artwork)
        assertNotNull(page.nextKey)
        assertTrue("seeded entry must not wait for an API read: $calls", calls.isEmpty())
    }

    @Test fun rejectedMetadataAndFeedNeverReplaceTheRequestedSeed() = runBlocking {
        val (client, _) = routedClient(mapOf("/next" to (400 to "{}")))
        val repository = MediaShortsRepository(client, startId = "seedShort1")
        val page = repository.shorts()
        assertNull(repository.hydrate("seedShort1"))
        assertTrue(runCatching { repository.shortsNext(requireNotNull(page.nextKey)) }.exceptionOrNull() is MediaException)
        assertEquals(listOf("seedShort1"), page.items.map { it.id })
    }

    @Test fun aSeedlessEntryStillReportsTheGlobalFeedFailure() = runBlocking {
        val (client, _) = routedClient(mapOf("/reel/reel_watch_sequence" to (400 to "{}")))
        val failure = runCatching { MediaShortsRepository(client).shorts() }.exceptionOrNull()
        assertTrue("seedless entries keep the honest failure", failure is MediaException)
    }

    @Test fun hydrationPreservesTheViewerRatingIncludingUnknown() = runBlocking {
        val expected = mapOf("LIKE" to com.hhst.youtubelite.watch.domain.VideoRating.Like,
            "DISLIKE" to com.hhst.youtubelite.watch.domain.VideoRating.Dislike,
            "INDIFFERENT" to com.hhst.youtubelite.watch.domain.VideoRating.None, "UNRECOGNIZED" to null)
        for ((rating, value) in expected) {
            val (client, _) = routedClient(mapOf(
                "/next" to (200 to """{"videoDetails":{"videoId":"abcdefghijk","title":"Short"},"likeStatusEntity":{"likeStatus":"$rating"}}"""),
                "/player" to (200 to """{"videoDetails":{"videoId":"abcdefghijk","title":"Short"}}"""),
            ))
            assertEquals(value, MediaShortsRepository(client).hydrate("abcdefghijk")?.rating)
        }
    }
}
