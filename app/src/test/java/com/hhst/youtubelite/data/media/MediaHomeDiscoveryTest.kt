package com.hhst.youtubelite.data.media

import com.hhst.youtubelite.home.domain.FeedItem
import com.hhst.youtubelite.home.domain.HomeCatalog
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestContext
import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class MediaHomeDiscoveryTest {
    private var session = RequestContext(visitor = "visitor", generation = 7)
    private val requests = Collections.synchronizedList(mutableListOf<Request>())
    private fun repository(home: String = "{}", status: Int = 200) = MediaHomeRepository(MediaClient(
        OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            val body = okio.Buffer().also { request.body?.writeTo(it) }.readUtf8()
            val id = runCatching { Json.parseToJsonElement(body).jsonObject["browseId"]?.jsonPrimitive?.content }.getOrNull()
            val directory = when (id) {
                "FEtopics_music" -> shelf("Top charts", "playlistRenderer", "playlistId", "chart")
                "FEtopics_gaming" -> shelf("Trending videos", "videoRenderer", "videoId", "gaming")
                "FEtopics_sports" -> shelf("Trending", "videoRenderer", "videoId", "sports")
                "FEtopics_news" -> shelf("Top news", "videoRenderer", "videoId", "news")
                else -> null
            }
            Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(if (directory != null || request.method == "GET") 200 else status).message("Response")
                .body((directory ?: home).toResponseBody("application/json".toMediaType())).build()
        }.build(),
    ) { session })

    private fun shelf(title: String, renderer: String, idKey: String, id: String) = """
        {"contents":[{"shelfRenderer":{"title":{"simpleText":"$title"},"content":{"horizontalListRenderer":{"items":[
        {"$renderer":{"$idKey":"$id","title":{"simpleText":"$id"}}}]}}}}]}
    """.trimIndent()

    @Test fun emptyAnonymousHomeLoadsOnlyTheSelectedPublicShelves() = runBlocking {
        val page = repository().loadHome("All")
        assertEquals(HomeCatalog.Popular, page.catalog)
        assertEquals(listOf("gaming", "sports", "news"), page.items.map { it.id })
        assertTrue(page.items.all { it is FeedItem.Video })
        assertFalse(page.partialFailure)
        assertNull(page.nextKey)
        assertTrue(requests.none { it.header("Authorization") != null })
    }

    @Test fun realRecommendationsTakePriorityOverPublicDirectories() = runBlocking {
        val page = repository(shelf("Recommended", "videoRenderer", "videoId", "recommended")).loadHome("All")
        assertEquals(listOf("recommended"), page.items.map { it.id })
        assertNull(page.catalog)
        assertEquals(1, requests.count { it.method == "POST" })
    }

    @Test fun signedInEmptyHomeStaysInItsOwnState() = runBlocking {
        session = session.copy(accessToken = "access", accountId = "account")
        assertTrue(repository().loadHome("All").items.isEmpty())
        assertEquals(1, requests.size)
    }

    @Test fun failedHomeDoesNotSilentlyTurnIntoPublicContent() = runBlocking {
        assertTrue(runCatching { repository(status = 403).loadHome("All") }.exceptionOrNull() is MediaException)
        assertTrue(requests.filter { it.method == "POST" }.all { request ->
            val body = okio.Buffer().also { request.body?.writeTo(it) }.readUtf8()
            !body.contains("FEtopics_")
        })
    }

    @Test fun categorySelectionReadsItsTrendingShelfWithoutProbingTheHome() = runBlocking {
        val page = repository().loadHome("Sports")
        assertEquals(HomeCatalog.Sports, page.catalog)
        assertEquals(listOf("sports"), page.items.map { it.id })
        assertEquals(1, requests.count { it.method == "POST" })
    }

    @Test fun musicChartsArePlaylistsAndNeverMergedIntoAllVideos() = runBlocking {
        val page = repository().loadHome("Music charts")
        assertEquals(HomeCatalog.MusicCharts, page.catalog)
        assertEquals("chart", (page.items.single() as FeedItem.Playlist).summary.id)
        assertEquals(1, requests.count { it.method == "POST" })
    }

    @Test fun authenticatedIdentityRejectsPublicContinuationsWithoutNetworkIo() = runBlocking {
        session = session.copy(accessToken = "access", accountId = "account")
        assertTrue(runCatching { repository().loadHome("All", "discovery:any") }.exceptionOrNull() is CancellationException)
        assertTrue(requests.isEmpty())
    }
}
