package com.hhst.youtubelite.data.media

import com.liskovsoft.youtubeapi.videoinfo.V2.RequestContext
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountContentReadTest {
    private val requests = mutableListOf<String>()
    private val client = MediaClient(OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        val body = request.body?.let { okio.Buffer().also(it::writeTo).readUtf8() }.orEmpty()
        assertEquals("Bearer test-token", request.header("Authorization"))
        assertTrue(body.contains("\"clientName\":\"TVHTML5\""))
        synchronized(requests) { requests += request.url.encodedPath }
        val response = when (request.url.encodedPath.substringAfterLast('/')) {
            "next" -> watchBody
            "player" -> """{"playabilityStatus":{"status":"OK"}}"""
            "browse" -> playlistBody(if (body.contains("VLWL")) "Watch later" else "Liked videos")
            else -> error("Unexpected request: ${request.url.encodedPath}")
        }
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(response.toResponseBody("application/json".toMediaType())).build()
    }.build()) { RequestContext(accountId = "test-account", accessToken = "test-token", generation = 7) }

    @Test fun watchMetadataDoesNotDependOnPlayerDetails() = runBlocking {
        val page = MediaWatchRepository(client).watch("aqz-KE-bpKQ")
        assertEquals("A film", page.video.title)
        assertEquals("Ada", page.video.channel)
        assertEquals("UCada", page.video.channelId)
        assertEquals("12K views", page.video.views)
        assertEquals(7L, page.video.sessionGeneration)
        assertEquals(listOf("next"), page.related.map { it.id })
        assertEquals("PIVOT", page.nextKey)
        assertEquals(2, requests.size)
    }

    @Test fun shortsHydrationUsesTheAccountMetadata() = runBlocking {
        val detail = requireNotNull(MediaShortsRepository(client).hydrate("aqz-KE-bpKQ"))
        assertEquals("A film", detail.item.title)
        assertEquals("Ada", detail.item.author)
        assertEquals("UCada", detail.item.channelId)
        assertEquals(7L, detail.sessionGeneration)
    }

    @Test fun emptySystemPlaylistsRemainAvailable() = runBlocking {
        val repository = MediaPlaylistRepository(client)
        for ((id, title) in listOf("WL" to "Watch later", "LL" to "Liked videos")) {
            val page = requireNotNull(repository.playlist(id))
            assertEquals(title, page.summary.title)
            assertTrue(page.entries.isEmpty())
        }
    }

    private fun playlistBody(title: String) = """{"contents":{"tvBrowseRenderer":{"content":{"tvSurfaceContentRenderer":{"content":{
        "twoColumnRenderer":{"leftColumn":{"entityMetadataRenderer":{"title":{"simpleText":"$title"}}},
          "rightColumn":{"playlistVideoListRenderer":{"contents":[]}}}
    }}}}}}"""

    private val watchBody = """{"contents":{"singleColumnWatchNextResults":{
      "results":{"results":{"contents":[{"itemSectionRenderer":{"contents":[{"videoMetadataRenderer":{
        "title":{"simpleText":"A film"},"shortViewCountText":{"simpleText":"12K views"},
        "publishedTimeText":{"simpleText":"2 years ago"},
        "owner":{"videoOwnerRenderer":{"title":{"simpleText":"Ada"},
          "navigationEndpoint":{"browseEndpoint":{"browseId":"UCada"}}}}
      }}]}}]}},
      "pivot":{"sectionListRenderer":{"contents":[{"compactVideoRenderer":{"videoId":"next","title":{"simpleText":"Next"}}}],
        "continuations":[{"nextContinuationData":{"continuation":"PIVOT"}}]}}
    }}}"""
}
