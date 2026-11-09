package com.hhst.youtubelite.data.media

import com.hhst.youtubelite.search.domain.SearchFilters
import com.hhst.youtubelite.search.domain.SearchResultType
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSearchRepositoryTest {
    private class Recording(val bodies: Map<Int, Pair<Int, String>>, val calls: MutableList<String>) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val body = request.body?.let { okio.Buffer().also(it::writeTo).readUtf8() }.orEmpty()
            synchronized(calls) { calls += body }
            val (code, payload) = bodies[calls.size - 1] ?: (502 to "")
            return Response.Builder()
                .request(request)
                .protocol(okhttp3.Protocol.HTTP_1_1)
                .code(code)
                .message(if (code == 200) "OK" else "Rejected")
                .body(payload.toResponseBody("application/json".toMediaType()))
                .build()
        }
    }

    private fun client(bodies: Map<Int, Pair<Int, String>>): Pair<MediaClient, MutableList<String>> {
        val calls = mutableListOf<String>()
        return MediaClient(OkHttpClient.Builder().addInterceptor(Recording(bodies, calls)).build()) to calls
    }

    private fun panel() = """,{"searchFilterGroupRenderer":{"title":{"simpleText":"Type"},"contents":[
        {"searchFilterRenderer":{"label":{"simpleText":"Playlist"},"status":"FILTER_STATUS_OFF",
        "navigationEndpoint":{"searchEndpoint":{"params":"EGgQBRAA"}}}}]}}"""

    @Test fun aFilteredSearchAppliesTheChipWithoutReReadingTheResponse() = runBlocking {
        val (client, calls) = client(mapOf(
            0 to (200 to """{"contents":[{"videoRenderer":{"videoId":"v1","title":{"simpleText":"Raw"}}}""" + panel() + "]}"),
            1 to (200 to """{"contents":[{"gridPlaylistRenderer":{"playlistId":"PL1","title":{"simpleText":"Open Movies"}}}]}"""),
        ))
        val page = MediaSearchRepository(client).search("open movie", SearchFilters(type = SearchResultType.Playlists))
        assertEquals(2, calls.size)
        assertTrue("chip params must ride the filtered request: ${calls[1]}", calls[1].contains("EGgQBRAA"))
        assertEquals(listOf("PL1"), page.playlists.map { it.id })
    }

    @Test fun aMissingFilterPanelFailsTypedInsteadOfRequestingAgain() = runBlocking {
        val (client, calls) = client(mapOf(
            0 to (200 to """{"contents":[{"videoRenderer":{"videoId":"v1"}}]}"""),
        ))
        val failure = runCatching {
            MediaSearchRepository(client).search("open movie", SearchFilters(type = SearchResultType.Playlists))
        }.exceptionOrNull()
        assertTrue("expected a typed failure for a missing chip", failure is MediaException)
        assertEquals(1, calls.size)
    }
}
