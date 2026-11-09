package com.hhst.youtubelite.data.media

import com.hhst.youtubelite.post.domain.PostIdentity
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestContext
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class MediaPostRepositoryTest {
    private val landing = """{"contents":{"tabRenderer":{"title":"Posts","endpoint":{"browseEndpoint":{"browseId":"UCauthor","params":"posts-tab"}}}}}"""
    private fun row(id: String, next: String? = null) = """{"backstagePostRenderer":{"postId":"$id","contentText":{"simpleText":"Post $id"}},
        "continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"${next.orEmpty()}"}}}}"""

    private class Harness(val reply: (Request, String) -> String) {
        val calls = mutableListOf<Pair<Request, String>>()
        val client = MediaClient(OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = Buffer().also { request.body?.writeTo(it) }.readUtf8()
            calls += request to body
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(reply(request, body).toResponseBody()).build()
        }.build()) { RequestContext(accessToken = "oauth", generation = 7) }
    }

    @Test fun signedInChannelPostsResolveHandlesAndAppendThroughTheProductionChannelRepository() = runBlocking {
        val h = Harness { request, body -> when {
            request.url.encodedPath.endsWith("resolve_url") -> """{"browseEndpoint":{"browseId":"UCauthor"}}"""
            body.contains("\"continuation\":\"page-2\"") -> row("Ugkx_second")
            body.contains("posts-tab") -> row("Ugkx_first", "page-2")
            else -> landing
        } }
        val repository = MediaChannelRepository(h.client, MediaPostRepository(h.client))
        val first = repository.posts("@author")
        assertEquals(PostIdentity("UCauthor", "Ugkx_first"), first.posts.single().identity)
        assertEquals("page-2", first.nextKey)
        val next = repository.postsNext("UCauthor", first.nextKey!!)
        assertEquals(PostIdentity("UCauthor", "Ugkx_second"), next.posts.single().identity)
        assertNull(next.nextKey)
        assertEquals(4, h.calls.size)
        h.calls.forEach { (request, body) ->
            assertTrue(body.contains("\"clientName\":\"WEB\""))
            assertNull(request.header("Authorization"))
        }
    }

    @Test fun aPostLinkLoadsItsActualAuthorWithoutRequiringTheChannelInTheLink() = runBlocking {
        val h = Harness { _, _ -> """<script>var ytInitialData = {"backstagePostRenderer":{"postId":"Ugkx_post",
            "authorEndpoint":{"browseEndpoint":{"browseId":"UCauthor"}},"contentText":{"simpleText":"Hello"}}};</script>""" }
        val post = MediaPostRepository(h.client).post(PostIdentity("", "Ugkx_post"))!!
        assertEquals(PostIdentity("UCauthor", "Ugkx_post"), post.identity)
        assertEquals("Hello", post.text)
        assertEquals("GET", h.calls.single().first.method)
    }
}
