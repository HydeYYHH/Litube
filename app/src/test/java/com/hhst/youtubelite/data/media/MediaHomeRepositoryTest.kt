package com.hhst.youtubelite.data.media

import com.hhst.youtubelite.home.domain.*
import com.hhst.youtubelite.watch.domain.SignInRequiredException
import com.hhst.youtubelite.watch.domain.UnconfirmedActionException
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestContext
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class MediaHomeRepositoryTest {
    private val home = """{"contents":[{"richItemRenderer":{"content":{"videoRenderer":{"videoId":"dismiss-target","title":{"runs":[{"text":"Dismissable video"}]},"ownerText":{"runs":[{"text":"Blender"}]},"menu":{"menuRenderer":{"items":[{"menuServiceItemRenderer":{"text":{"runs":[{"text":"Not interested"}]},"icon":{"iconType":"NOT_INTERESTED"},"serviceEndpoint":{"clickTrackingParams":"x","feedbackEndpoint":{"feedbackTokens":["dismiss-token"]}}}}]}}}}}},{"richItemRenderer":{"content":{"videoRenderer":{"videoId":"keep-target","title":{"runs":[{"text":"Kept video"}]},"ownerText":{"runs":[{"text":"Blender"}]}}}}}]}"""
    private val processed = """{"feedbackResponses":[{"isProcessed":true}]}"""


    private var session = RequestContext(visitor = "visitor", generation = 7)
    private val calls = mutableListOf<okhttp3.Request>()
    private fun repository(response: String = processed): MediaHomeRepository {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            calls += request
            Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                .body((if (request.url.encodedPath.endsWith("/feedback")) response else home)
                    .toResponseBody("application/json".toMediaType())).build()
        }.build()
        return MediaHomeRepository(MediaClient(http) { session })
    }
    private fun signIn() { session = session.copy(accessToken = "access", accountId = "account") }

    @Test fun anonymousFeedsNeverExposeFeedbackAndRejectForgedActions() = runBlocking {
        val repo = repository()
        val video = repo.loadHome("All").items.filterIsInstance<FeedItem.Video>().first()
        assertNull(video.notInterestedToken)
        assertFalse(FeedMenuAction.NotInterested in FeedMenuKind.Video.productionActions(false, video))
        assertTrue(runCatching { repo.feedback(video.copy(notInterestedToken = "token", feedbackGeneration = 7), FeedMenuAction.NotInterested) }
            .exceptionOrNull() is SignInRequiredException)
        assertTrue(calls.none { it.url.encodedPath.endsWith("/feedback") })
    }
    @Test fun signedInFeedbackUsesTheAuthenticatedEndpointAndKeepsServerOrder() = runBlocking {
        signIn()
        val repo = repository()
        val video = repo.loadHome("All").items.filterIsInstance<FeedItem.Video>().first()
        assertEquals("dismiss-token", video.notInterestedToken)
        repo.feedback(video, FeedMenuAction.NotInterested)
        assertEquals("Bearer access", calls.single { it.url.encodedPath.endsWith("/feedback") }.header("Authorization"))
        assertEquals(listOf("dismiss-target", "keep-target"), repo.loadHome("All").items.map { it.id })
    }
    @Test fun unconfirmedFeedbackFailsInsteadOfPretendingToHide() = runBlocking {
        signIn()
        val repo = repository("{}")
        val video = repo.loadHome("All").items.filterIsInstance<FeedItem.Video>().first()
        assertNotNull(runCatching { repo.feedback(video, FeedMenuAction.NotInterested) }.exceptionOrNull())
    }
    @Test fun staleTokensAndMissingTokensNeverReachTheServer() = runBlocking {
        signIn()
        val repo = repository()
        val video = repo.loadHome("All").items.filterIsInstance<FeedItem.Video>().first()
        session = session.copy(generation = 8)
        assertTrue(runCatching { repo.feedback(video, FeedMenuAction.NotInterested) }.exceptionOrNull() is UnconfirmedActionException)
        assertTrue(runCatching { repo.feedback(video.copy(feedbackGeneration = 8, notInterestedToken = null), FeedMenuAction.NotInterested) }
            .exceptionOrNull() is UnconfirmedActionException)
        assertTrue(calls.none { it.url.encodedPath.endsWith("/feedback") })
    }
}
