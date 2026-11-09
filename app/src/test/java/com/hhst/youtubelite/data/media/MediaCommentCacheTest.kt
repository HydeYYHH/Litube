package com.hhst.youtubelite.data.media

import com.hhst.youtubelite.comments.domain.CommentNavigationRequest
import com.hhst.youtubelite.comments.domain.CommentSort
import com.hhst.youtubelite.comments.domain.CommentTarget
import com.hhst.youtubelite.watch.domain.UnconfirmedActionException
import com.hhst.youtubelite.watch.domain.VideoRating
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MediaCommentCacheTest {
    private val target = CommentTarget.Video("abcdefghijk")
    private val context = RequestContext(accessToken = "token", generation = 7)

    private fun comment(prefix: String, id: String = "parent") = """{"commentRenderer":{
        "commentId":"$id","voteCount":{"simpleText":"10"},"contentText":{"simpleText":"Comment"},
        "actionMenu":{"menuServiceItemRenderer":{"icon":{"iconType":"DELETE"},
          "serviceEndpoint":{"performCommentActionEndpoint":{"action":"${prefix}DELETE"}}}},
        "actionButtons":{"commentActionButtonsRenderer":{"likeButton":{"toggleButtonRenderer":{
          "defaultServiceEndpoint":{"performCommentActionEndpoint":{"action":"${prefix}LIKE"}},
          "toggledServiceEndpoint":{"performCommentActionEndpoint":{"action":"${prefix}UNLIKE"}}}}}}}}"""

    private fun repository(context: () -> RequestContext = { this.context }, respond: (String) -> String) =
        MediaCommentsRepository(MediaClient(OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = Buffer().also { request.body?.writeTo(it) }.readUtf8()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(respond(body).toResponseBody()).build()
        }.build(), context))

    @Test fun aLaterStaleReadCannotChangeTheDisplayedUnlikeIntentOrMixCountSnapshots() = runBlocking {
        val writes = mutableListOf<String>()
        val repository = repository { body ->
            if (body.contains("\"actions\"")) { writes += body; """{"status":"STATUS_SUCCEEDED"}""" }
            else comment(if (body.contains("stale")) "stale-" else "current-")
        }
        repository.comments(target, CommentSort.Top, "current")
        assertEquals("11", repository.perform(CommentNavigationRequest.LikeComment(target, "parent",
            VideoRating.Like, VideoRating.None)).comment!!.likes)
        repository.comments(target, CommentSort.Top, "stale")
        val unlike = repository.perform(CommentNavigationRequest.LikeComment(target, "parent",
            VideoRating.None, VideoRating.Like)).comment!!
        assertTrue(writes.last().contains("\"actions\":[\"stale-UNLIKE\"]"))
        assertEquals(VideoRating.None, unlike.rating)
        assertEquals("10", unlike.likes)
    }

    @Test fun aPartialRepeatedParentAddsRatingActionsWithoutLosingCountsReplyOrDeleteCapabilities() = runBlocking {
        val requests = mutableListOf<String>()
        val repository = repository { body ->
            requests += body
            when {
                body.contains("\"actions\"") -> """{"status":"STATUS_SUCCEEDED"}"""
                body.contains("\"commentText\"") -> """{"status":"STATUS_SUCCEEDED","commentRenderer":{"commentId":"reply"}}"""
                body.contains("partial") -> """{"commentRenderer":{"commentId":"parent",
                    "actionButtons":{"commentActionButtonsRenderer":{"likeButton":{"toggleButtonRenderer":{
                      "defaultServiceEndpoint":{"performCommentActionEndpoint":{"action":"partial-LIKE"}}
                    }}}}}}"""
                else -> comment("original-").replace("\"actionMenu\"", "\"createReplyParams\":\"ORIGINAL_REPLY\",\"actionMenu\"")
            }
        }
        val thread = repository.comments(target, CommentSort.Top, "opening").threads.single()
        assertTrue(thread.ownedByViewer)
        assertTrue(repository.repliesPage(target, thread, "partial").replies.isEmpty())
        val liked = repository.perform(CommentNavigationRequest.LikeComment(target, "parent",
            VideoRating.Like, VideoRating.None)).comment!!
        assertEquals("11", liked.likes)
        assertTrue(liked.ownedByViewer)
        repository.perform(CommentNavigationRequest.SubmitReply(target, "parent", "parent", "Reply"))
        repository.perform(CommentNavigationRequest.DeleteComment(target, "parent", "parent"))
        assertTrue(requests[2].contains("\"actions\":[\"partial-LIKE\"]"))
        assertTrue(requests[3].contains("\"createReplyParams\":\"ORIGINAL_REPLY\""))
        assertTrue(requests[4].contains("\"actions\":[\"original-DELETE\"]"))
        assertEquals(5, requests.size)
    }

    @Test fun olderPagesCannotReplaceNewerCommandTokens() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writes = mutableListOf<String>()
        val repository = repository { body ->
            when {
                body.contains("\"actions\"") -> { writes += body; """{"status":"STATUS_SUCCEEDED"}""" }
                body.contains("old-page") -> {
                    started.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    comment("old-")
                }
                else -> comment("new-")
            }
        }
        try {
            val old = async(Dispatchers.Default) { repository.comments(target, CommentSort.Top, "old-page") }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            repository.comments(target, CommentSort.Top, "new-page")
            release.countDown()
            old.await()
            repository.perform(CommentNavigationRequest.LikeComment(target, "parent", VideoRating.Like, VideoRating.None))
            assertTrue(writes.single().contains("\"actions\":[\"new-LIKE\"]"))
        } finally { release.countDown() }
    }

    @Test fun aPageAlreadyInFlightCannotRestoreDeletedCommentCapabilities() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writes = mutableListOf<String>()
        val repository = repository { body ->
            when {
                body.contains("\"actions\"") -> { writes += body; """{"status":"STATUS_SUCCEEDED"}""" }
                body.contains("old-page") -> {
                    started.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    comment("old-")
                }
                else -> comment("current-")
            }
        }
        repository.comments(target, CommentSort.Top, "current")
        try {
            val old = async(Dispatchers.Default) { repository.comments(target, CommentSort.Top, "old-page") }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            repository.perform(CommentNavigationRequest.DeleteComment(target, "parent", "parent"))
            release.countDown()
            old.await()
            assertTrue(runCatching { repository.perform(CommentNavigationRequest.LikeComment(target, "parent",
                VideoRating.Like, VideoRating.None)) }.exceptionOrNull() is UnconfirmedActionException)
            assertEquals(1, writes.size)
        } finally { release.countDown() }
    }

    @Test fun commentPagesKeepAllActionCapabilitiesBeyondThePostCacheLimit() = runBlocking {
        var reads = 0
        val writes = mutableListOf<String>()
        val repository = repository { body ->
            if (body.contains("\"actions\"")) { writes += body; """{"status":"STATUS_SUCCEEDED"}""" }
            else {
                reads++
                "{\"contents\":[" + (0..39).joinToString(",") { comment("$it-", "comment-$it") } + "]}"
            }
        }
        assertEquals(40, repository.comments(target, CommentSort.Top, "page").threads.size)
        repository.perform(CommentNavigationRequest.LikeComment(target, "comment-0", VideoRating.Like, VideoRating.None))
        assertEquals(1, reads)
        assertTrue(writes.single().contains("\"actions\":[\"0-LIKE\"]"))
    }

    @Test fun failedWritesKeepTheConfirmedCountSnapshot() = runBlocking {
        var writes = 0
        val repository = repository { body ->
            if (!body.contains("\"actions\"")) comment("current-")
            else if (++writes == 1) """{"status":"STATUS_FAILED"}"""
            else """{"status":"STATUS_SUCCEEDED"}"""
        }
        repository.comments(target, CommentSort.Top, "current")
        val like = CommentNavigationRequest.LikeComment(target, "parent", VideoRating.Like, VideoRating.None)
        assertTrue(runCatching { repository.perform(like) }.isFailure)
        assertEquals("11", repository.perform(like).comment!!.likes)
    }

    @Test fun anOldWorkerCannotReplaceANewerSessionsCommentCapabilities() = runBlocking {
        val captured = CountDownLatch(1)
        val release = CountDownLatch(1)
        val contexts = AtomicInteger()
        val reads = AtomicInteger()
        val writes = mutableListOf<String>()
        val repository = repository(context = {
            if (contexts.incrementAndGet() == 1) {
                captured.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                context
            } else context.copy(generation = 8)
        }) { body ->
            if (body.contains("\"actions\"")) { writes += body; """{"status":"STATUS_SUCCEEDED"}""" }
            else { reads.incrementAndGet(); comment("new-") }
        }
        try {
            val old = async(Dispatchers.Default) {
                runCatching { repository.comments(target, CommentSort.Top, "old") }
            }
            assertTrue(captured.await(5, TimeUnit.SECONDS))
            repository.comments(target, CommentSort.Top, "new")
            release.countDown()
            assertTrue(old.await().isFailure)
            repository.perform(CommentNavigationRequest.LikeComment(target, "parent", VideoRating.Like, VideoRating.None))
            assertEquals(1, reads.get())
            assertTrue(writes.single().contains("\"actions\":[\"new-LIKE\"]"))
        } finally { release.countDown() }
    }
}
