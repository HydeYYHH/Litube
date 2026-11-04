package com.hhst.youtubelite.data.media

import com.hhst.youtubelite.explore.domain.ExploreCategory
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestContext
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class MediaExplorePaginationTest {
    private fun repository(body: String) = MediaExploreRepository(MediaClient(
        OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                .message("OK").body(body.toResponseBody()).build()
        }.build(),
    ) { RequestContext(visitor = "visitor", generation = 1) })

    @Test fun aPlaylistShelfKeepsItsContentAndContinuation() = runBlocking {
        val body = """{"contents":[{"shelfRenderer":{"title":{"simpleText":"Charts"},
            "content":{"horizontalListRenderer":{"items":[{"playlistRenderer":{"playlistId":"chart"}}],
            "continuations":[{"nextContinuationData":{"continuation":"shelf-next"}}]}}}}]}"""
        val page = repository(body).explore(ExploreCategory.Music)
        val shelf = page.sections.single()
        assertTrue(shelf.videos.isEmpty())
        assertEquals(listOf("chart"), shelf.playlists.map { it.id })
        assertEquals("shelf-next", shelf.nextKey)
    }

    @Test fun aShelfContinuationUsesItsOwnCursor() = runBlocking {
        val body = """{"contents":[{"shelfRenderer":{"title":{"simpleText":"Trending"},
            "content":{"horizontalListRenderer":{"items":[{"videoRenderer":{"videoId":"video"}}],
            "continuations":[{"nextContinuationData":{"continuation":"shelf-next"}}]}}}}]}"""
        val shelf = repository(body).exploreShelf(ExploreCategory.Sports, "shelf-current")
        assertEquals(listOf("video"), shelf.videos.map { it.id })
        assertEquals("shelf-next", shelf.nextKey)
    }

    @Test fun aFilteredPageKeepsThePageContinuation() = runBlocking {
        val body = """{"continuationContents":{"horizontalListContinuation":{"items":[
            {"playlistRenderer":{"playlistId":"chart"}}],
            "continuations":[{"nextContinuationData":{"continuation":"page-next"}}]}}}"""
        val page = repository(body).exploreNext(ExploreCategory.Music, "page-current")
        assertTrue(page.videos.isEmpty())
        assertEquals(listOf("chart"), page.playlists.map { it.id })
        assertEquals("page-next", page.nextKey)
    }
}
