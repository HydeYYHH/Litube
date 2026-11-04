package com.hhst.youtubelite.data.media

import com.hhst.youtubelite.watch.domain.VideoRating
import com.hhst.youtubelite.comments.domain.*
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MediaCommentRepliesTest {
    @Test fun delayedRepliesCannotReplaceConfirmedParentCommands() = runBlocking {
        val reading = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writes = mutableListOf<String>()
        fun comment(prefix: String) = """{"commentRenderer":{"commentId":"parent","voteCount":{"simpleText":"10"},
            "contentText":{"simpleText":"Parent"},"actionButtons":{"commentActionButtonsRenderer":{
            "likeButton":{"toggleButtonRenderer":{"defaultServiceEndpoint":{"performCommentActionEndpoint":{"action":"$prefix-like"}},
            "toggledServiceEndpoint":{"performCommentActionEndpoint":{"action":"$prefix-unlike"}}}}}}}}"""
        val client = MediaClient(OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = Buffer().also { request.body?.writeTo(it) }.readUtf8()
            val response = when {
                body.contains("\"videoId\"") -> """{"engagementPanelSectionListRenderer":{"panelIdentifier":"engagement-panel-comments-section",
                    "content":{"continuationEndpoint":{"continuationCommand":{"token":"comments"}}}}}"""
                body.contains("replies-old") -> {
                    reading.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    comment("stale")
                }
                body.contains("\"actions\"") -> { writes += body; """{"status":"STATUS_SUCCEEDED"}""" }
                else -> comment("current")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(response.toResponseBody()).build()
        }.build()) { RequestContext(accessToken = "access", generation = 7) }
        val repository = MediaCommentsRepository(client)
        val target = CommentTarget.Video("abcdefghijk")
        val thread = repository.comments(target, CommentSort.Top, null).threads.single()
        try {
            val replies = async(Dispatchers.Default) { repository.repliesPage(target, thread, "replies-old") }
            assertTrue(reading.await(5, TimeUnit.SECONDS))
            repository.perform(CommentNavigationRequest.LikeComment(target, thread.id, VideoRating.Like, VideoRating.None))
            release.countDown()
            assertTrue(replies.await().replies.isEmpty())
            repository.perform(CommentNavigationRequest.LikeComment(target, thread.id, VideoRating.None, VideoRating.Like))
            assertTrue(writes.last().contains("\"actions\":[\"current-unlike\"]"))
        } finally { release.countDown() }
    }

    @Test fun tvRepliesExcludeTheRepeatedParentAndKeepBothPaginationAndRatingActions() = runBlocking {
        MockWebServer().use { server ->
            val responses = listOf(
                """{"engagementPanelSectionListRenderer":{"panelIdentifier":"engagement-panel-comments-section","content":{"continuationEndpoint":{"continuationCommand":{"token":"comments"}}}}}""",
                """{"continuationContents":{"itemSectionContinuation":{"contents":[{"commentThreadRenderer":{"comment":{"commentRenderer":{
                  "commentId":"parent","contentText":{"simpleText":"Parent"},"repliesCount":{"simpleText":"2"},
                  "detailViewEndpoint":{"showEngagementPanelEndpoint":{"engagementPanel":{"engagementPanelSectionListRenderer":{
                    "content":{"sectionListRenderer":{"contents":[{"itemSectionRenderer":{"continuations":[{"reloadContinuationData":{"continuation":"detail"}}]}}]}}
                  }}}}
                }}}}]}}}""",
                """{"continuationContents":{"itemSectionContinuation":{"contents":[
                  {"commentRenderer":{"commentId":"parent","actionButtons":{"commentActionButtonsRenderer":{"likeButton":{"toggleButtonRenderer":{
                    "defaultServiceEndpoint":{"performCommentActionEndpoint":{"action":"parent-like"}}
                  }}}}}},
                  {"commentThreadRenderer":{"comment":{"commentRenderer":{"commentId":"child-1","contentText":{"simpleText":"First reply"},
                    "actionButtons":{"commentActionButtonsRenderer":{"likeButton":{"toggleButtonRenderer":{
                      "defaultServiceEndpoint":{"performCommentActionEndpoint":{"action":"child-like"}}
                    }}}}}
                  }}}
                ],"continuations":[{"nextContinuationData":{"continuation":"replies-next"}}]}}}""",
                """{"continuationContents":{"itemSectionContinuation":{"contents":[{"commentThreadRenderer":{"comment":{"commentRenderer":{"commentId":"child-2","contentText":{"simpleText":"Second reply"}}}}}]}}}""",
                """{"actionResults":[{"status":"STATUS_SUCCEEDED"}]}""",
                """{"actionResults":[{"status":"STATUS_SUCCEEDED"}]}""",
            )
            responses.forEach { server.enqueue(MockResponse().setBody(it)) }
            val transport = OkHttpClient.Builder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
            }.build()
            val repository = MediaCommentsRepository(MediaClient(transport) { RequestContext(accessToken = "access", generation = 7) })
            val target = CommentTarget.Video("abcdefghijk")
            val thread = repository.comments(target, CommentSort.Top, null).threads.single()
            assertEquals("detail", thread.replyKey)
            assertEquals(2, thread.replyCount)
            val first = repository.repliesPage(target, thread, null)
            assertEquals(listOf("child-1"), first.replies.map { it.id })
            assertEquals("replies-next", first.nextCursor)
            val second = repository.repliesPage(target, thread, first.nextCursor)
            assertEquals(listOf("child-2"), second.replies.map { it.id })
            assertNull(second.nextCursor)
            repository.perform(CommentNavigationRequest.LikeComment(target, thread.id, VideoRating.Like, VideoRating.None))
            repository.perform(CommentNavigationRequest.LikeComment(target, first.replies.single().id, VideoRating.Like, VideoRating.None))
            val requests = List(6) { server.takeRequest() }
            assertTrue(requests[2].body.readUtf8().contains("\"continuation\":\"detail\""))
            assertTrue(requests[3].body.readUtf8().contains("\"continuation\":\"replies-next\""))
            assertTrue(requests[4].body.readUtf8().contains("\"actions\":[\"parent-like\"]"))
            assertTrue(requests[5].body.readUtf8().contains("\"actions\":[\"child-like\"]"))
            assertEquals(6, server.requestCount)
        }
    }
}
