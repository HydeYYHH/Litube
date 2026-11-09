package com.hhst.youtubelite.data.media

import com.hhst.youtubelite.watch.domain.UnconfirmedActionException
import com.hhst.youtubelite.watch.domain.VideoRating
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestContext
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class MediaVideoActionsTest {
    @Test fun confirmedVotesAndSubscriptionsInvalidateBothWatchCacheLayers() = runBlocking {
        var rating = "INDIFFERENT"
        var subscribed = false
        val nextReads = java.util.concurrent.atomic.AtomicInteger()
        val transport = OkHttpClient.Builder().addInterceptor { chain ->
            val endpoint = chain.request().url.encodedPath
            val body = when {
                endpoint.endsWith("/next") -> {
                    nextReads.incrementAndGet()
                    """{"videoDetails":{"title":"Video","author":"Channel"},
                        "likeStatusEntity":{"likeStatus":"$rating"},"subscribeButtonRenderer":{"subscribed":$subscribed}}"""
                }
                endpoint.endsWith("/like/like") -> { rating = "LIKE"; """{"success":true}""" }
                endpoint.endsWith("/subscription/subscribe") -> { subscribed = true; """{"success":true}""" }
                endpoint.endsWith("/player") -> """{"videoDetails":{"videoId":"abcdefghijk","title":"Video","author":"Channel"},
                    "microformat":{"playerMicroformatRenderer":{"publishDate":"2025-01-01"}}}"""
                else -> error("Unexpected endpoint: $endpoint")
            }
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200).message("OK").body(body.toResponseBody()).build()
        }.build()
        val unusedPreferences = java.lang.reflect.Proxy.newProxyInstance(
            android.content.SharedPreferences::class.java.classLoader,
            arrayOf(android.content.SharedPreferences::class.java),
        ) { _, method, _ -> error("Video actions must not access local library preferences: ${method.name}") }
            as android.content.SharedPreferences
        val preferences = com.hhst.youtubelite.core.PreferenceStore.of(object : android.content.ContextWrapper(null) {
            override fun getSharedPreferences(name: String?, mode: Int) = unusedPreferences
        }, "unused-library")
        val sources = ContentRepositories(MediaClient(transport) {
            RequestContext(accountId = "account", accessToken = "access", generation = 7)
        }, kv = preferences, sharedWatchReads = SharedWatchReads())
        assertEquals(VideoRating.None, sources.watch.watch("abcdefghijk").video.rating)
        sources.videoActions.rate("abcdefghijk", VideoRating.Like, VideoRating.None)
        assertEquals(VideoRating.Like, sources.watch.watch("abcdefghijk").video.rating)
        sources.videoActions.subscribe("UCabcdefghijk", true)
        assertTrue(sources.watch.watch("abcdefghijk").video.subscribed)
        assertEquals(3, nextReads.get())
    }

    private fun client(server: MockWebServer) = MediaClient(OkHttpClient.Builder().addInterceptor { chain ->
        chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
    }.build()) { RequestContext(accountId = "account", accessToken = "access", generation = 7) }

    @Test fun removingEitherRatingUsesTheNeutralEndpointAndConfirmsTheServerState() = runBlocking {
        for (previous in listOf(VideoRating.Like, VideoRating.Dislike)) {
            MockWebServer().use { server ->
                server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                    override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse = when {
                        request.path.orEmpty().endsWith("/like/removelike") -> MockResponse().setBody("""{"responseContext":{}}""")
                        request.path.orEmpty().endsWith("/next") -> MockResponse().setBody("""{"likeStatusEntity":{"likeStatus":"INDIFFERENT"}}""")
                        else -> MockResponse().setResponseCode(404)
                    }
                }
                MediaVideoActions(client(server)).rate("abcdefghijk", VideoRating.None, previous)
                assertTrue(server.takeRequest().path.orEmpty().endsWith("/like/removelike"))
                assertTrue(server.takeRequest().path.orEmpty().endsWith("/next"))
                assertEquals(2, server.requestCount)
            }
        }
    }

    @Test fun anAmbiguousVoteIsConfirmedByOneUncachedReadWithoutRepeatingTheWrite() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"responseContext":{}}"""))
            server.enqueue(MockResponse().setBody("""{"likeStatusEntity":{"likeStatus":"LIKE"}}"""))
            var invalidations = 0
            MediaVideoActions(client(server), onConfirmed = { invalidations++ })
                .rate("abcdefghijk", VideoRating.Like, VideoRating.None)
            assertEquals(1, invalidations)
            val write = server.takeRequest()
            val read = server.takeRequest()
            assertTrue(write.path.orEmpty().endsWith("/like/like"))
            assertTrue(read.path.orEmpty().endsWith("/next"))
            assertEquals("Bearer access", write.getHeader("Authorization"))
            assertEquals(write.getHeader("Authorization"), read.getHeader("Authorization"))
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun aMismatchedOrMissingVoteRemainsUnconfirmedWithSafeResponseDetails() = runBlocking {
        for (body in listOf("{}", """{"likeStatusEntity":{"likeStatus":"DISLIKE"}}""")) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody("""{"responseContext":{},"privateValue":"secret"}"""))
                server.enqueue(MockResponse().setBody(body))
                server.enqueue(MockResponse().setBody(body))
                if (body != "{}") {
                    server.enqueue(MockResponse().setBody("""{"responseContext":{}}"""))
                    server.enqueue(MockResponse().setBody(body))
                    server.enqueue(MockResponse().setBody(body))
                }
                val failure = runCatching {
                    MediaVideoActions(client(server)).rate("abcdefghijk", VideoRating.Like, VideoRating.None)
                }.exceptionOrNull() as UnconfirmedActionException
                assertEquals(200, failure.httpStatus)
                assertEquals("LIKE", failure.detail["expected_rating"])
                assertEquals("responseContext", failure.detail["response_shape"])
                assertFalse(failure.detail.toString().contains("secret"))
                assertEquals(if (body == "{}") 3 else 6, server.requestCount)
            }
        }
    }

    @Test fun anExplicitConfirmationOrRefusalDoesNotTriggerReadback() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"success":true}"""))
            var invalidations = 0
            val actions = MediaVideoActions(client(server), onConfirmed = { invalidations++ })
            actions.rate("abcdefghijk", VideoRating.Dislike, VideoRating.Like)
            server.enqueue(MockResponse().setResponseCode(403).setBody("{}"))
            assertTrue(runCatching { actions.rate("abcdefghijk", VideoRating.Like, VideoRating.None) }.isFailure)
            assertEquals(1, invalidations)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun anAmbiguousUnsubscribeRequiresAnExplicitFalseState() = runBlocking {
        for (confirmed in listOf(false, true)) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody("{}"))
                server.enqueue(MockResponse().setBody(if (confirmed)
                    """{"subscribeButtonRenderer":{"subscribed":false}}""" else "{}"))
                if (!confirmed) server.enqueue(MockResponse().setBody("{}"))
                val result = runCatching { MediaVideoActions(client(server)).subscribe("UCabcdefghijk", false) }
                assertEquals(confirmed, result.isSuccess)
                assertEquals(if (confirmed) 2 else 3, server.requestCount)
            }
        }
    }
}
