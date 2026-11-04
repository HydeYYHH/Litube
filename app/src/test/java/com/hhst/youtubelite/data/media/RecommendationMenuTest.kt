package com.hhst.youtubelite.data.media

import com.hhst.youtubelite.home.domain.FeedItem
import com.hhst.youtubelite.home.domain.FeedMenuAction
import com.hhst.youtubelite.search.domain.SearchResult
import com.hhst.youtubelite.watch.domain.WatchRelated
import com.hhst.youtubelite.watch.domain.WatchVideoMenuAction
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import kotlinx.serialization.json.*

class RecommendationMenuTest {
    private val panel = """{"content":{"engagementPanelSectionListRenderer":{"content":{"listViewModel":{"listItems":[
        {"listItemViewModel":{"rendererContext":{"commandContext":{"onTap":{"innertubeCommand":{"feedbackEndpoint":{
        "feedbackToken":"video-token","uiActions":{"hideUnhideUis":[]}}}}}}}},
        {"listItemViewModel":{"rendererContext":{"commandContext":{"onTap":{"innertubeCommand":{"feedbackEndpoint":{
        "feedbackToken":"channel-token","uiActions":{"hideEnclosingContainerUis":[]}}}}}}}}
    ]}}}}}"""
    private var session = RequestContext(accountId = "a", accessToken = "access", generation = 7)
    private val calls = mutableListOf<Request>()
    private val client = MediaClient(OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        calls += request
        val reply = when (request.url.encodedPath.substringAfterLast('/')) {
            "get_panel" -> panel
            "feedback" -> """{"feedbackResponses":[{"isProcessed":true}]}"""
            else -> error("Unexpected request")
        }
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(reply.toResponseBody("application/json".toMediaType())).build()
    }.build()) { session }

    private fun related(id: String = "abcdefghijk") = WatchRelated(id, "Video", "Channel", "UCchannel", "", "", "", "",
        feedbackGeneration = 7)
    private fun home() = FeedItem.Video("abcdefghijk", "Video", "Channel", "", "", "", "UCchannel", feedbackGeneration = 7)

    @Test fun relatedMenuWithoutAnEndpointLoadsBothRealCapabilitiesAndCachesPerVideo() = runBlocking {
        val repository = MediaWatchRepository(client)
        val loaded = repository.menu(related())
        assertEquals("video-token", loaded.notInterestedToken)
        assertEquals("channel-token", loaded.dontRecommendToken)
        assertEquals(loaded, repository.menu(related()))
        assertEquals(1, calls.size)
        repository.menu(related("lmnopqrstuv"))
        assertEquals(2, calls.size)
        calls.forEachIndexed { index, request ->
            assertEquals("Bearer access", request.header("Authorization"))
            val buffer = okio.Buffer().also { request.body!!.writeTo(it) }
            val body = Json.parseToJsonElement(buffer.readUtf8()).jsonObject
            assertEquals("PAcontext_menu", body.getValue("panelId").jsonPrimitive.content)
            val bytes = Base64.getUrlDecoder().decode(body.getValue("params").jsonPrimitive.content)
            val text = bytes.toString(Charsets.UTF_8)
            assertTrue(text.contains(if (index == 0) "abcdefghijk" else "lmnopqrstuv"))
            assertTrue(text.contains("FEwhat_to_watch"))
        }
        repository.feedback(loaded, WatchVideoMenuAction.DontRecommend)
        assertEquals("feedback", calls.last().url.encodedPath.substringAfterLast('/'))
        val body = okio.Buffer().also { calls.last().body!!.writeTo(it) }.readUtf8()
        assertTrue(body.contains("channel-token"))
        assertFalse(body.contains("video-token"))
    }

    @Test fun homeRecommendationsUseTheSameReadAndOnlySendFeedbackOnSelection() = runBlocking {
        val repository = MediaHomeRepository(client)
        val loaded = repository.menu(home())
        assertEquals("video-token", loaded.notInterestedToken)
        assertEquals("channel-token", loaded.dontRecommendToken)
        assertTrue(calls.all { it.url.encodedPath.endsWith("/get_panel") })
        repository.feedback(loaded, FeedMenuAction.NotInterested)
        assertTrue(calls.last().url.encodedPath.endsWith("/feedback"))
    }

    @Test fun staleAndAnonymousRecommendationsCannotRequestAccountCapabilities() = runBlocking {
        val repository = MediaWatchRepository(client)
        session = session.copy(generation = 8)
        assertTrue(runCatching { repository.menu(related()) }.exceptionOrNull() is CancellationException)
        session = RequestContext(generation = 7)
        assertTrue(runCatching { repository.menu(related()) }.exceptionOrNull() is CancellationException)
        assertEquals(related().copy(feedbackGeneration = null), repository.menu(related().copy(feedbackGeneration = null)))
        assertTrue(calls.isEmpty())
    }

    @Test fun searchResultsDoNotGainRecommendationActionsByVideoId() = runBlocking {
        val result = SearchResult("abcdefghijk", "Video", "Channel", "UCchannel", "", "", "", "", feedbackGeneration = 7)
        assertEquals(result, MediaSearchRepository(client).menu(result))
        assertTrue(calls.isEmpty())
        assertNull(recommendationMenuEndpoint("invalid"))
    }
}
