package com.hhst.youtubelite.data.media

import com.hhst.youtubelite.home.domain.*
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class MediaHomeMenuTest {
    private fun video() = FeedItem.Video("video", "Title", "Author", "", "", "", "channel",
        feedbackGeneration = 7, menuEndpoint = FeedMenuEndpoint("menu", "opaque"))
    private fun response(token: String) = """{"content":{"engagementPanelSectionListRenderer":{"content":{"listViewModel":{"listItems":[{"listItemViewModel":{"rendererContext":{"commandContext":{"onTap":{"innertubeCommand":{"feedbackEndpoint":{"feedbackToken":"$token","uiActions":{"hideEnclosingContainer":true}}}}}}}}]}}}}}"""

    @Test fun successfulCapabilitiesAreCachedBrieflyAndNeverSharedAcrossCardsOrIdentities() = runBlocking {
        var session = RequestContext(accountId = "a", accessToken = "access", generation = 7)
        var now = 10L
        val requests = mutableListOf<Request>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(response("token-${requests.size}").toResponseBody("application/json".toMediaType())).build()
        }.build()
        val repository = MediaHomeRepository(MediaClient(http) { session }) { now }
        assertEquals("token-1", repository.menu(video()).notInterestedToken)
        assertEquals("token-1", repository.menu(video()).notInterestedToken)
        assertEquals(1, requests.size)
        assertEquals("token-2", repository.menu(video().copy(id = "other")).notInterestedToken)
        now += 60_001
        assertEquals("token-3", repository.menu(video()).notInterestedToken)
        session = session.copy(accountId = "b", generation = 8)
        assertEquals("token-4", repository.menu(video().copy(feedbackGeneration = 8)).notInterestedToken)
        assertEquals(4, requests.size)
        assertTrue(requests.all { it.url.encodedPath.endsWith("/get_panel") && it.header("Authorization") == "Bearer access" })
        assertTrue(runCatching { repository.menu(video()) }.exceptionOrNull() is CancellationException)
        session = RequestContext(generation = 8)
        assertTrue(runCatching { repository.menu(video().copy(feedbackGeneration = 8)) }.exceptionOrNull() is CancellationException)
        assertEquals(4, requests.size)
    }

    @Test fun failuresAreNotCachedAndCanBeRetried() = runBlocking {
        var calls = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body((if (++calls == 1) "{}" else response("retry-token")).toResponseBody("application/json".toMediaType())).build()
        }.build()
        val repo = MediaHomeRepository(MediaClient(http) { RequestContext(accessToken = "access", generation = 7) })
        assertTrue(runCatching { repo.menu(video()) }.exceptionOrNull() is MediaException)
        assertEquals("retry-token", repo.menu(video()).notInterestedToken)
        assertEquals(2, calls)
    }

    @Test fun contentSnapshotsExcludeSessionCapabilities() {
        val original = video().copy(notInterestedToken = "secret", dontRecommendToken = "channel-secret")
        val encoded = Json.encodeToString(FeedItem.Video.serializer(), original)
        assertFalse(encoded.contains("secret"))
        assertFalse(encoded.contains("opaque"))
        val restored = Json.decodeFromString(FeedItem.Video.serializer(), encoded)
        assertEquals(original.title, restored.title)
        assertNull(restored.notInterestedToken)
        assertNull(restored.feedbackGeneration)
        assertNull(restored.menuEndpoint)
    }
}
