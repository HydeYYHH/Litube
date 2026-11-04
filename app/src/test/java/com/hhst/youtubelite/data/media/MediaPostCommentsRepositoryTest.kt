package com.hhst.youtubelite.data.media

import com.hhst.youtubelite.comments.domain.CommentSort
import com.hhst.youtubelite.comments.domain.CommentTarget
import com.hhst.youtubelite.post.domain.PostIdentity
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestContext
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class MediaPostCommentsRepositoryTest {
    @Test fun openingSortingPagingAndRepliesAllFollowThePostBrowseLane() = runBlocking {
        val calls = mutableListOf<Pair<String, String>>()
        val client = MediaClient(OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = Buffer().also { request.body?.writeTo(it) }.readUtf8()
            calls += request.url.encodedPath to body
            val text = when {
                request.method == "GET" -> """<script>var ytInitialData = {"itemSectionRenderer":{"sectionIdentifier":"comment-item-section",
                    "contents":[{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"top"}}}}]}};</script>"""
                body.contains("\"continuation\":\"top\"") -> """{"commentsHeaderRenderer":{"countText":{"simpleText":"42 Comments"}},
                    "sortFilterSubMenuRenderer":{"subMenuItems":[{"title":"Newest","serviceEndpoint":{"continuationCommand":{"token":"newest"}}}]}}"""
                body.contains("\"continuation\":\"newest\"") -> """{"onResponseReceivedEndpoints":[{"reloadContinuationItemsCommand":{"continuationItems":[
                    {"commentThreadRenderer":{"comment":{"commentRenderer":{"commentId":"thread","contentText":{"simpleText":"Newest"}}},
                     "replies":{"commentRepliesRenderer":{"contents":[{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"replies"}}}}],
                     "viewReplies":{"buttonRenderer":{"text":{"simpleText":"1 reply"}}}}}}},
                    {"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"more"}}}}
                    ]}}]}"""
                body.contains("\"continuation\":\"replies\"") -> """{"onResponseReceivedEndpoints":[{"appendContinuationItemsAction":{"continuationItems":[
                    {"commentRenderer":{"commentId":"reply","contentText":{"simpleText":"Reply"}}}]}}]}"""
                body.contains("\"continuation\":\"more\"") -> """{"onResponseReceivedEndpoints":[{"appendContinuationItemsAction":{"continuationItems":[
                    {"commentRenderer":{"commentId":"second","contentText":{"simpleText":"More"}}}]}}]}"""
                else -> error("Unexpected post read: ${request.url}")
            }
            if (request.method == "POST") {
                assertEquals("/youtubei/v1/browse", request.url.encodedPath)
                assertTrue(body.contains("\"clientName\":\"WEB\""))
                assertNull(request.header("Authorization"))
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(text.toResponseBody()).build()
        }.build()) { RequestContext(accessToken = "oauth", generation = 7) }
        val repository = MediaCommentsRepository(client)
        val target = CommentTarget.Post(PostIdentity("", "Ugkx_post"))
        val page = repository.comments(target, CommentSort.Newest, null)
        assertEquals("42 Comments", page.total)
        assertEquals("thread", page.threads.single().id)
        assertEquals("more", page.nextCursor)
        assertFalse(page.sortFallback)
        val replies = repository.repliesPage(target, page.threads.single(), null)
        assertEquals("reply", replies.replies.single().id)
        val next = repository.comments(target, CommentSort.Newest, page.nextCursor)
        assertEquals("second", next.threads.single().id)
        assertEquals(5, calls.size)
    }
}
