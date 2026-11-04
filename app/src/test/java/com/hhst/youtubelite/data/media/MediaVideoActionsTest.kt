package com.hhst.youtubelite.data.media

import com.hhst.youtubelite.watch.domain.UnconfirmedActionException
import com.hhst.youtubelite.watch.domain.VideoRating
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestContext
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class MediaVideoActionsTest {
    private fun client(server: MockWebServer) = MediaClient(OkHttpClient.Builder().addInterceptor { chain ->
        chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
    }.build()) { RequestContext(accountId = "account", accessToken = "access", generation = 7) }

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
                val failure = runCatching {
                    MediaVideoActions(client(server)).rate("abcdefghijk", VideoRating.Like, VideoRating.None)
                }.exceptionOrNull() as UnconfirmedActionException
                assertEquals(200, failure.httpStatus)
                assertEquals("LIKE", failure.detail["expected_rating"])
                assertEquals("responseContext", failure.detail["response_shape"])
                assertFalse(failure.detail.toString().contains("secret"))
                assertEquals(2, server.requestCount)
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
                val result = runCatching { MediaVideoActions(client(server)).subscribe("UCabcdefghijk", false) }
                assertEquals(confirmed, result.isSuccess)
                assertEquals(2, server.requestCount)
            }
        }
    }
}
