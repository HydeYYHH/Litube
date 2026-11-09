package com.hhst.youtubelite.data.media

import com.liskovsoft.youtubeapi.videoinfo.V2.RequestContext
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Test

class ChannelShortsRepositoryTest {
    @Test fun authenticatedChannelFollowsTheReturnedShortsCommandAndContinuation() = runBlocking {
        val requests = mutableListOf<String>()
        val client = MediaClient(OkHttpClient.Builder().addInterceptor { chain ->
            val body = Buffer().also { chain.request().body?.writeTo(it) }.readUtf8()
            requests += Regex("\"(?:continuation|params)\"\\s*:\\s*\"([^\"]+)\"")
                .find(body)?.groupValues?.get(1) ?: "root"
            val answer = if (requests.size == 1) navigation else shortPage
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                .message("OK").body(answer.toResponseBody()).build()
        }.build(), { RequestContext(accessToken = "test-token") })
        val repository = MediaChannelRepository(client, MediaPostRepository(client))
        val page = repository.shorts("UCowner")
        assertEquals(listOf("video123456"), page.shorts.map { it.id })
        assertEquals("Short title", page.shorts.single().title)
        assertEquals("next-shorts", page.nextKey)
        repository.shortsNext("UCowner", requireNotNull(page.nextKey))
        assertEquals(listOf("root", "shorts-command", "next-shorts"), requests)
    }

    private val navigation = """{"contents":{"tileRenderer":{"style":"TILE_STYLE_YTLR_WORMHOLE_RECTANGULAR","header":{"tileHeaderRenderer":{"thumbnailOverlays":[{"tileMetadataRenderer":{"title":{"simpleText":"Shorts"}}}]}},"onSelectCommand":{"browseEndpoint":{"browseId":"UCowner","params":"shorts-command"}}}}}"""
    private val shortPage = """{"contents":{"items":[{"tileRenderer":{"header":{"tileHeaderRenderer":{"thumbnail":{"thumbnails":[{"url":"https://i.ytimg.com/vi/video123456/frame0.jpg"}]},"thumbnailOverlays":[{"tileMetadataRenderer":{"title":{"simpleText":"Short title"}}}]}},"onSelectCommand":{"reelWatchEndpoint":{"videoId":"video123456"}}}}],"continuations":[{"nextContinuationData":{"continuation":"next-shorts"}}]}}"""
}
