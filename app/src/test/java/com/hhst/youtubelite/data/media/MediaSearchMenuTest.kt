package com.hhst.youtubelite.data.media

import com.hhst.youtubelite.home.domain.FeedMenuEndpoint
import com.hhst.youtubelite.search.domain.SearchFilters
import com.hhst.youtubelite.search.domain.SearchResult
import com.hhst.youtubelite.search.domain.SearchVideoMenuAction
import com.hhst.youtubelite.watch.domain.SignInRequiredException
import com.hhst.youtubelite.watch.domain.UnconfirmedActionException
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class MediaSearchMenuTest {
    private val processed = """{"feedbackResponses":[{"isProcessed":true}]}"""
    private val searchWithToken = """{"contents":[{"videoRenderer":{"videoId":"v1","title":{"simpleText":"Raw"},
        "ownerText":{"runs":[{"text":"Blender"}]},"menu":{"menuRenderer":{"items":[{"menuServiceItemRenderer":{
        "text":{"runs":[{"text":"Not interested"}]},"icon":{"iconType":"NOT_INTERESTED"},"serviceEndpoint":{
        "clickTrackingParams":"x","feedbackEndpoint":{"feedbackTokens":["dismiss-token"]}}}}]}}}}]}"""
    private val panel = """{"content":{"engagementPanelSectionListRenderer":{"content":{"listViewModel":{"listItems":
        [{"listItemViewModel":{"rendererContext":{"commandContext":{"onTap":{"innertubeCommand":{"feedbackEndpoint":{
        "feedbackToken":"panel-token","uiActions":{"hideEnclosingContainer":true}}}}}}}}]}}}}}"""

    private var session = RequestContext(accountId = "a", accessToken = "access", generation = 7)
    private var now = 10L
    private val calls = mutableListOf<okhttp3.Request>()

    private fun client(responses: Map<String, String>): MediaClient =
        MediaClient(OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            calls += request
            val payload = responses.entries.firstOrNull { request.url.encodedPath.endsWith(it.key) }?.value
                ?: error("unexpected call to ${request.url.encodedPath}")
            Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                .body(payload.toResponseBody("application/json".toMediaType())).build()
        }.build()) { session }

    private fun repository(responses: Map<String, String> = mapOf("get_panel" to panel, "feedback" to processed)) =
        MediaSearchRepository(client(responses)) { now }

    private fun result() = SearchResult("v1", "Title", "Author", "channel", "", "", "", "",
        feedbackGeneration = 7, menuEndpoint = FeedMenuEndpoint("menu", "opaque"))

    @Test fun menuCapabilitiesCacheBrieflyAndNeverShareAcrossCardsOrIdentities() = runBlocking {
        val repo = repository()
        assertEquals("panel-token", repo.menu(result()).notInterestedToken)
        assertEquals("panel-token", repo.menu(result()).notInterestedToken)
        assertEquals(1, calls.size)
        assertEquals("panel-token", repo.menu(result().copy(id = "other")).notInterestedToken)
        assertEquals(2, calls.size)
        now += 60_001
        assertEquals("panel-token", repo.menu(result()).notInterestedToken)
        assertEquals(3, calls.size)
        session = session.copy(accountId = "b", generation = 8)
        assertEquals("panel-token", repo.menu(result().copy(feedbackGeneration = 8)).notInterestedToken)
        assertEquals(4, calls.size)
        assertTrue(calls.all { it.url.encodedPath.endsWith("/get_panel") && it.header("Authorization") == "Bearer access" })
    }

    @Test fun staleOrUnsignedMenusCancelInsteadOfReadingTheEndpoint() = runBlocking {
        val repo = repository()
        session = session.copy(generation = 8)
        assertTrue(runCatching { repo.menu(result()) }.exceptionOrNull() is CancellationException)
        session = RequestContext(generation = 8)
        assertTrue(runCatching { repo.menu(result().copy(feedbackGeneration = 8)) }.exceptionOrNull() is CancellationException)
        assertTrue(calls.none { it.url.encodedPath.endsWith("/get_panel") })
    }

    @Test fun feedbackSendsTheCardsOwnTokenForTheCapturedGeneration() = runBlocking {
        val repo = repository()
        repo.feedback(result().copy(notInterestedToken = "dismiss-token"), SearchVideoMenuAction.NotInterested)
        assertEquals("Bearer access", calls.single { it.url.encodedPath.endsWith("/feedback") }.header("Authorization"))
    }

    @Test fun unsignedOrStaleOrTokenlessFeedbackNeverReachesTheServer() = runBlocking {
        val repo = repository()
        session = RequestContext(generation = 8)
        assertTrue(runCatching { repo.feedback(result().copy(feedbackGeneration = 8), SearchVideoMenuAction.NotInterested) }
            .exceptionOrNull() is SignInRequiredException)
        session = RequestContext(accessToken = "access", generation = 8)
        assertTrue(runCatching { repo.feedback(result(), SearchVideoMenuAction.NotInterested) }
            .exceptionOrNull() is UnconfirmedActionException)
        assertTrue(runCatching { repo.feedback(result().copy(feedbackGeneration = 8), SearchVideoMenuAction.NotInterested) }
            .exceptionOrNull() is UnconfirmedActionException)
        assertTrue(runCatching { repo.feedback(result().copy(feedbackGeneration = 8), SearchVideoMenuAction.Share) }
            .exceptionOrNull() is UnconfirmedActionException)
        assertTrue(calls.none { it.url.encodedPath.endsWith("/feedback") })
    }

    @Test fun signedInSearchResultsCarryInlineTokensAndGenerationOnlyThen() = runBlocking {
        val signed = MediaSearchRepository(client(mapOf("search" to searchWithToken))) { now }
        val video = signed.search("raw").results.single()
        assertEquals("dismiss-token", video.notInterestedToken)
        assertEquals(7L, video.feedbackGeneration)
        // Anonymous results never carry capabilities, so a forged action cannot even be built.
        session = RequestContext(generation = 7)
        val anonymous = MediaSearchRepository(client(mapOf("search" to searchWithToken))) { now }
        val bare = anonymous.search("raw").results.single()
        assertNull(bare.notInterestedToken)
        assertNull(bare.dontRecommendToken)
        assertNull(bare.menuEndpoint)
        assertNull(bare.feedbackGeneration)
    }
}
