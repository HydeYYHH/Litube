package com.hhst.youtubelite.data.media

import com.hhst.youtubelite.search.domain.*
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestContext
import com.liskovsoft.youtubeapi.videoinfo.V2.WebSession
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class MediaSearchNavigationTest {
    private class Fixture(initial: RequestContext = RequestContext(visitor = "visitor")) {
        var context = initial
        var now = 1_000L
        var rejectFirst = false
        var selectedDateMissing = false
        var omitDateGroup = false
        val params = mutableListOf<String?>()
        val authorizations = mutableListOf<String?>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = okio.Buffer().also { request.body!!.writeTo(it) }.readUtf8()
            val param = Json.parseToJsonElement(body).jsonObject["params"]?.jsonPrimitive?.contentOrNull
            params += param
            authorizations += request.header("Authorization")
            val rejected = rejectFirst.also { rejectFirst = false }
            Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(if (rejected) 403 else 200).message("fixture")
                .body((if (rejected) "{}" else document(param, selectedDateMissing, omitDateGroup)).toResponseBody("application/json".toMediaType())).build()
        }.build()
        val repository = MediaSearchRepository(MediaClient(http) { context }) { now }
    }

    @Test fun changingOneFilterUsesItsCurrentCumulativeEndpointAndPreservesTheOtherGroup() = runBlocking {
        val f = Fixture()
        f.repository.search("q", SearchFilters())
        val today = SearchFilters(uploaded = SearchUploadDate.Today)
        f.repository.search("q", today)
        f.repository.search("q", today.copy(type = SearchResultType.Channels))
        f.repository.search("q", today.copy(type = SearchResultType.Playlists))
        f.repository.search("q", SearchFilters(SearchResultType.Playlists, SearchUploadDate.ThisWeek))
        assertEquals(listOf(null, "Today|", "Today|Channel", "Today|Playlist", "This week|Playlist"), f.params)
    }

    @Test fun resetsAndTwoGroupChangesKeepTheColdSearchRecipe() = runBlocking {
        val f = Fixture()
        val todayChannels = SearchFilters(SearchResultType.Channels, SearchUploadDate.Today)
        f.repository.search("q", todayChannels)
        assertEquals(listOf(null, "Today|", "Today|Channel"), f.params)
        f.repository.search("q", SearchFilters(SearchResultType.Playlists, SearchUploadDate.ThisWeek))
        assertEquals(listOf(null, "This week|", "This week|Playlist"), f.params.takeLast(3))
        f.repository.search("q", SearchFilters(type = SearchResultType.Playlists))
        assertEquals(listOf(null, "|Playlist"), f.params.takeLast(2))
        f.repository.search("q", SearchFilters())
        assertNull(f.params.last())
    }

    @Test fun anUnprovenRetainedSelectionCannotReuseThePanel() = runBlocking {
        val f = Fixture().apply { selectedDateMissing = true }
        val today = SearchFilters(uploaded = SearchUploadDate.Today)
        f.repository.search("q", today)
        f.repository.search("q", today.copy(type = SearchResultType.Channels))
        assertEquals(listOf(null, "Today|", "Today|Channel"), f.params.takeLast(3))
    }

    @Test fun aMissingRetainedGroupCannotProveThatTheDefaultSelectionIsPreserved() = runBlocking {
        val f = Fixture().apply { omitDateGroup = true }
        f.repository.search("q", SearchFilters())
        f.repository.search("q", SearchFilters(type = SearchResultType.Playlists))
        assertEquals(listOf(null, null, "|Playlist"), f.params)
    }

    @Test fun queryExpiryAndEveryRequestIdentityDimensionPreventPanelReuse() = runBlocking {
        val initial = RequestContext(accountId = "account", accessToken = "token", visitor = "visitor", generation = 7)
        val changed = listOf(
            initial.copy(accountId = "other"), initial.copy(onBehalfOfUser = "channel"), initial.copy(generation = 8),
            initial.copy(accessToken = null), initial.copy(visitor = "other"), initial.copy(language = "zh"), initial.copy(region = "CN"),
        )
        for (context in changed) {
            val f = Fixture(initial)
            f.repository.search("q", SearchFilters())
            f.context = context
            f.repository.search("q", SearchFilters(type = SearchResultType.Playlists))
            assertEquals("Changed context $context", listOf(null, null, "|Playlist"), f.params)
        }
        for (web in listOf(WebSession("SAPISID=other"), WebSession("SAPISID=one", 1))) {
            val f = Fixture(RequestContext(accountId = "web", webSession = WebSession("SAPISID=one")))
            f.repository.search("q", SearchFilters())
            f.context = f.context.copy(webSession = web)
            f.repository.search("q", SearchFilters(type = SearchResultType.Playlists))
            assertEquals(listOf(null, null, "|Playlist"), f.params)
        }
        val cookie = Fixture(RequestContext(visitor = "visitor", visitorCookie = "VISITOR_INFO1_LIVE=one"))
        cookie.repository.search("q", SearchFilters())
        cookie.context = cookie.context.copy(visitorCookie = "VISITOR_INFO1_LIVE=two")
        cookie.repository.search("q", SearchFilters(type = SearchResultType.Playlists))
        assertEquals(listOf(null, null, "|Playlist"), cookie.params)
        val f = Fixture()
        f.repository.search("q", SearchFilters())
        f.repository.search("other", SearchFilters(type = SearchResultType.Playlists))
        assertEquals(listOf(null, null, "|Playlist"), f.params)
        f.now += 60_001
        f.repository.search("other", SearchFilters(uploaded = SearchUploadDate.Today, type = SearchResultType.Playlists))
        assertEquals(listOf(null, "Today|", "Today|Playlist"), f.params.takeLast(3))
    }

    @Test fun oauthTokenRotationPreservesTheSameProfileButAnAnonymousAuthFallbackDoesNot() = runBlocking {
        val f = Fixture(RequestContext(accountId = "account", accessToken = "old", visitor = "visitor"))
        f.repository.search("q", SearchFilters())
        f.context = f.context.copy(accessToken = "fresh")
        f.repository.search("q", SearchFilters(type = SearchResultType.Playlists))
        assertEquals(listOf(null, "|Playlist"), f.params)
        assertEquals("Bearer fresh", f.authorizations.last())
        val fallback = Fixture(f.context).apply { rejectFirst = true }
        fallback.repository.search("q", SearchFilters())
        fallback.repository.search("q", SearchFilters(type = SearchResultType.Playlists))
        assertEquals(listOf(null, null, null, "|Playlist"), fallback.params)
        assertNull(fallback.authorizations[1])
        val intermediate = Fixture(f.context).apply { rejectFirst = true }
        val today = SearchFilters(uploaded = SearchUploadDate.Today)
        intermediate.repository.search("q", today)
        intermediate.repository.search("q", today.copy(type = SearchResultType.Channels))
        assertEquals(listOf(null, null, "Today|", null, "Today|", "Today|Channel"), intermediate.params)
    }

    companion object {
        private fun document(params: String?, selectedDateMissing: Boolean, omitDateGroup: Boolean): String {
            val date = params?.substringBefore('|').orEmpty()
            val type = params?.substringAfter('|').orEmpty()
            fun option(label: String, value: String, selected: Boolean) = """{"searchFilterRenderer":{
                "label":{"simpleText":"$label"},"status":"${if (selected) "FILTER_STATUS_SELECTED" else "FILTER_STATUS_OFF"}",
                "navigationEndpoint":{"searchEndpoint":{"params":"$value"}}}}"""
            val types = listOf("Channel", "Playlist", "Shorts").joinToString(",") { option(it, "$date|$it", type == it) }
            val dates = listOf("Today", "This week", "This month", "This year").joinToString(",") {
                option(it, "$it|$type", date == it && !selectedDateMissing)
            }
            val dateGroup = if (omitDateGroup) "" else """,
                {"searchFilterGroupRenderer":{"title":{"simpleText":"Upload date"},"contents":[$dates]}}"""
            return """{"contents":[{"videoRenderer":{"videoId":"abcdefghijk","title":{"simpleText":"Result"}}},
                {"searchFilterGroupRenderer":{"title":{"simpleText":"Type"},"contents":[$types]}}$dateGroup]}"""
        }
    }
}
