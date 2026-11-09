package com.hhst.youtubelite.data.media

import com.hhst.youtubelite.comments.domain.*
import com.hhst.youtubelite.post.domain.*
import com.hhst.youtubelite.watch.domain.VideoRating
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestContext
import com.liskovsoft.youtubeapi.videoinfo.V2.WebSession
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class MediaPostActionsTest {
    private val identity = PostIdentity("UCauthor", "Ugkx_post")
    private val native = RequestContext(accountId = "account", accessToken = "oauth", generation = 7)
    private val web = native.copy(accessToken = null, webSession = WebSession("SAPISID=fixture; SID=fixture"))
    private fun html(status: String = "INDIFFERENT", id: String = identity.postId, prefix: String = "") = """<script>var ytInitialData = {
      "backstagePostRenderer":{"postId":"$id","authorEndpoint":{"browseEndpoint":{"browseId":"UCauthor"}},
        "contentText":{"simpleText":"Post"},"voteCount":{"simpleText":"10"},"voteStatus":"$status",
        "actionButtons":{"commentActionButtonsRenderer":{
          "likeButton":{"toggleButtonRenderer":{"defaultServiceEndpoint":{"performCommentActionEndpoint":{"action":"${prefix}LIKE"}},
            "toggledServiceEndpoint":{"performCommentActionEndpoint":{"action":"${prefix}UNLIKE"}}}},
          "dislikeButton":{"toggleButtonRenderer":{"defaultServiceEndpoint":{"performCommentActionEndpoint":{"action":"${prefix}DISLIKE"}},
            "toggledServiceEndpoint":{"performCommentActionEndpoint":{"action":"${prefix}UNDISLIKE"}}}}}},
        "backstageAttachment":{"pollRenderer":{"totalVotes":{"simpleText":"100 votes"},"choices":[
          {"text":{"simpleText":"First"},"votePercentageIfSelected":{"simpleText":"51%"},
           "selectServiceEndpoint":{"commandMetadata":{"webCommandMetadata":{"apiUrl":"/youtubei/v1/feedback"}},"feedbackEndpoint":{"feedbackToken":"FIRST"}},
           "deselectServiceEndpoint":{"commandMetadata":{"webCommandMetadata":{"apiUrl":"/youtubei/v1/feedback"}},"feedbackEndpoint":{"feedbackToken":"REMOVE_FIRST"}}},
          {"text":{"simpleText":"Second"}}]}}},
      "itemSectionRenderer":{"sectionIdentifier":"comment-item-section","contents":[
        {"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"comments"}}}}]}};</script>"""

    private fun client(context: () -> RequestContext = { native }, respond: (String, String) -> String) = MediaClient(OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        assertEquals("SAPISID=fixture; SID=fixture", request.header("Cookie"))
        assertTrue(request.header("Authorization")!!.startsWith("SAPISIDHASH "))
        val body = Buffer().also { request.body?.writeTo(it) }.readUtf8()
        if (request.method == "POST") assertTrue(body.contains("\"clientName\":\"WEB\""))
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
            .body(respond(request.url.encodedPath, body).toResponseBody()).build()
    }.build(), context)

    @Test fun likesTogglesAndPollVotesUseAuthorizedServerCommands() = runBlocking {
        val writes = mutableListOf<String>()
        val repository = MediaPostRepository(client { path, body ->
            // Reads can lag behind a confirmed write; the second tap must still remove the like.
            if (path.startsWith("/post/")) html() else {
                writes += body
                """{"status":"STATUS_SUCCEEDED"}"""
            }
        }) { web }
        assertEquals(VideoRating.Like, repository.rate(identity, VideoRating.Like, VideoRating.None).rating)
        assertEquals(VideoRating.None, repository.rate(identity, VideoRating.None, VideoRating.Like).rating)
        val voted = repository.vote(identity, 0, true).poll!!
        assertTrue(voted.voted)
        assertTrue(voted.options.first().selected)
        assertEquals("51%", voted.options.first().percentage)
        assertEquals(3, writes.size)
        assertTrue(writes[1].contains("\"actions\":[\"UNLIKE\"]"))
        assertTrue(writes.last().contains("\"feedbackTokens\":[\"FIRST\"]"))
    }

    @Test fun aStalePostReadCannotTurnAnUnlikeIntoAnotherLike() = runBlocking {
        val writes = mutableListOf<String>()
        val repository = MediaPostRepository(client { path, body ->
            if (path.startsWith("/post/")) html() else {
                writes += body
                """{"status":"STATUS_SUCCEEDED"}"""
            }
        }) { web }
        assertEquals(VideoRating.Like, repository.rate(identity, VideoRating.Like, VideoRating.None).rating)
        assertEquals(VideoRating.None, repository.post(identity)!!.rating)
        assertEquals(VideoRating.None, repository.rate(identity, VideoRating.None, VideoRating.Like).rating)
        assertTrue(writes.last().contains("\"actions\":[\"UNLIKE\"]"))
    }

    @Test fun stalePollReadsCannotTurnAnExplicitDeselectIntoAnotherSelect() = runBlocking {
        val writes = mutableListOf<String>()
        val repository = MediaPostRepository(client { path, body ->
            if (path.startsWith("/post/")) html() else { writes += body; """{"status":"STATUS_SUCCEEDED"}""" }
        }) { web }
        assertTrue(repository.vote(identity, 0, true).poll!!.options.first().selected)
        repository.post(identity)
        assertFalse(repository.vote(identity, 0, false).poll!!.options.first().selected)
        assertTrue(writes.last().contains("\"feedbackTokens\":[\"REMOVE_FIRST\"]"))
    }

    @Test fun aReadStartedBeforeConfirmationCannotReplaceTheConfirmedCommandTokens() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val reads = AtomicInteger()
        val writes = mutableListOf<String>()
        val repository = MediaPostRepository(client { path, body ->
            if (!path.startsWith("/post/")) { writes += body; """{"status":"STATUS_SUCCEEDED"}""" }
            else if (reads.incrementAndGet() == 2) {
                started.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                html(prefix = "stale-")
            } else html(prefix = "current-")
        }) { web }
        repository.post(identity)
        try {
            val old = async(Dispatchers.Default) { repository.post(identity) }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            repository.rate(identity, VideoRating.Like, VideoRating.None)
            release.countDown()
            old.await()
            repository.rate(identity, VideoRating.None, VideoRating.Like)
            assertTrue(writes.last().contains("\"actions\":[\"current-UNLIKE\"]"))
        } finally { release.countDown() }
    }

    @Test fun olderReadsCannotReplaceNewerCapabilitiesOrRecreateAnEvictedEntry() = runBlocking {
        for (evict in listOf(false, true)) {
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val reads = AtomicInteger()
            val writes = mutableListOf<String>()
            val repository = MediaPostRepository(client { path, body ->
                if (!path.startsWith("/post/")) { writes += body; """{"status":"STATUS_SUCCEEDED"}""" }
                else {
                    val id = path.substringAfterLast('/')
                    if (id == identity.postId && reads.incrementAndGet() == 1) {
                        started.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                        html(id = id, prefix = "old-")
                    } else html(id = id, prefix = "new-")
                }
            }) { web }
            try {
                val old = async(Dispatchers.Default) { repository.post(identity) }
                assertTrue(started.await(5, TimeUnit.SECONDS))
                if (evict) repeat(32) { repository.post(identity.copy(postId = "Ugkx_other_$it")) }
                repository.post(identity)
                release.countDown()
                old.await()
                repository.rate(identity, VideoRating.Like, VideoRating.None)
                assertTrue(writes.single().contains("\"actions\":[\"new-LIKE\"]"))
            } finally { release.countDown() }
        }
    }

    @Test fun commandCacheRetainsTheMostRecentlyUsed32Posts() = runBlocking {
        val reads = mutableListOf<String>()
        val repository = MediaPostRepository(client { path, _ ->
            if (path.startsWith("/post/")) {
                val id = path.substringAfterLast('/')
                reads += id
                html(id = id)
            } else """{"status":"STATUS_SUCCEEDED"}"""
        }) { web }
        repository.post(identity)
        repeat(31) { repository.post(identity.copy(postId = "Ugkx_other_$it")) }
        repository.post(identity) // Touch the oldest entry before adding the 33rd.
        repository.post(identity.copy(postId = "Ugkx_other_31"))
        val before = reads.size
        repository.rate(identity, VideoRating.Like, VideoRating.None)
        assertEquals(before, reads.size)
        repository.rate(identity.copy(postId = "Ugkx_other_0"), VideoRating.Like, VideoRating.None)
        assertEquals(before + 1, reads.size)
        assertEquals("Ugkx_other_0", reads.last())
    }

    @Test fun aDifferentSessionMustLoadItsOwnCommandTokens() = runBlocking {
        val generation = AtomicLong(native.generation)
        val writes = mutableListOf<String>()
        val repository = MediaPostRepository(client(context = { native.copy(generation = generation.get()) }) { path, body ->
            if (path.startsWith("/post/")) html(prefix = "${generation.get()}-")
            else { writes += body; """{"status":"STATUS_SUCCEEDED"}""" }
        }) { it.copy(accessToken = null, webSession = web.webSession) }
        repository.post(identity)
        generation.incrementAndGet()
        repository.rate(identity, VideoRating.Like, VideoRating.None)
        assertTrue(writes.single().contains("\"actions\":[\"8-LIKE\"]"))
    }

    @Test fun anOldWorkerEnteringAfterANewSessionCannotResetTheNewCache() = runBlocking {
        val captured = CountDownLatch(1)
        val release = CountDownLatch(1)
        val contexts = AtomicInteger()
        val reads = AtomicInteger()
        val next = native.copy(generation = 8)
        val repository = MediaPostRepository(client(context = {
            if (contexts.incrementAndGet() == 1) {
                captured.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                native
            } else next
        }) { path, _ ->
            if (path.startsWith("/post/")) { reads.incrementAndGet(); html() }
            else """{"status":"STATUS_SUCCEEDED"}"""
        }) { it.copy(accessToken = null, webSession = web.webSession) }
        try {
            val old = async(Dispatchers.Default) { runCatching { repository.post(identity) } }
            assertTrue(captured.await(5, TimeUnit.SECONDS))
            repository.post(identity)
            release.countDown()
            assertTrue(old.await().isFailure)
            repository.rate(identity, VideoRating.Like, VideoRating.None)
            assertEquals(1, reads.get())
        } finally { release.countDown() }
    }

    @Test fun failedWritesDoNotAdvanceTheCountSnapshot() = runBlocking {
        var writes = 0
        val repository = MediaPostRepository(client { path, _ ->
            if (path.startsWith("/post/")) html()
            else if (++writes == 1) """{"status":"STATUS_FAILED"}"""
            else """{"status":"STATUS_SUCCEEDED"}"""
        }) { web }
        repository.post(identity)
        assertTrue(runCatching { repository.rate(identity, VideoRating.Like, VideoRating.None) }.isFailure)
        assertEquals("11", repository.rate(identity, VideoRating.Like, VideoRating.None).likes)
    }

    @Test fun postCommentsCanBeCreatedRepliedToRatedAndDeletedWithTheSameWebIdentity() = runBlocking {
        val writes = mutableListOf<String>()
        val repository = MediaCommentsRepository(client { path, body -> when {
            path.startsWith("/post/") -> html()
            path.endsWith("browse") -> """{"commentSimpleboxRenderer":{"submitButton":{"buttonRenderer":{"serviceEndpoint":{
              "createCommentEndpoint":{"createCommentParams":"CREATE"}}}}},
              "commentThreadRenderer":{"comment":{"commentRenderer":{"commentId":"thread","contentText":{"simpleText":"Original"},
                "createReplyParams":"REPLY","actionMenu":{"menuServiceItemRenderer":{"icon":{"iconType":"DELETE"},"serviceEndpoint":{"performCommentActionEndpoint":{"action":"DELETE"}}}},
                "actionButtons":{"commentActionButtonsRenderer":{"likeButton":{"toggleButtonRenderer":{
                  "defaultServiceEndpoint":{"performCommentActionEndpoint":{"action":"LIKE"}}}}}}}}}}"""
            path.endsWith("create_comment") || path.endsWith("create_comment_reply") -> {
                writes += body
                """{"status":"STATUS_SUCCEEDED","commentRenderer":{"commentId":"created","contentText":{"simpleText":"Posted"}}}"""
            }
            else -> { writes += body; """{"status":"STATUS_SUCCEEDED"}""" }
        } }, webContext = { web })
        val target = CommentTarget.Post(identity)
        repository.comments(target, CommentSort.Top, null)
        assertEquals("created", repository.perform(CommentNavigationRequest.SubmitComment(target, "Hello")).comment?.id)
        assertEquals("created", repository.perform(CommentNavigationRequest.SubmitReply(target, "thread", "thread", "Reply")).comment?.id)
        repository.perform(CommentNavigationRequest.LikeComment(target, "thread", VideoRating.Like, VideoRating.None))
        repository.perform(CommentNavigationRequest.DeleteComment(target, "thread", "thread"))
        assertTrue(writes[0].contains("\"createCommentParams\":\"CREATE\""))
        assertTrue(writes[1].contains("\"createReplyParams\":\"REPLY\""))
        assertTrue(writes[2].contains("\"actions\":[\"LIKE\"]"))
        assertTrue(writes[3].contains("\"actions\":[\"DELETE\"]"))
    }

    @Test fun missingWebAuthorizationDoesNotSendAMutation() = runBlocking {
        var calls = 0
        val client = MediaClient(OkHttpClient.Builder().addInterceptor { calls++; error("No request expected") }.build()) { native }
        assertTrue(runCatching { MediaPostRepository(client).rate(identity, VideoRating.Like, VideoRating.None) }.exceptionOrNull() is PostAuthorizationRequiredException)
        val comments = MediaCommentsRepository(client)
        assertTrue(runCatching { comments.perform(CommentNavigationRequest.SubmitComment(CommentTarget.Post(identity), "Kept draft")) }
            .exceptionOrNull() is PostAuthorizationRequiredException)
        assertEquals(0, calls)
    }

    @Test fun anUnconfirmedPostMutationRemainsAFailureAfterOneBoundedRecovery() = runBlocking {
        var writes = 0
        val repository = MediaPostRepository(client { path, _ ->
            if (path.startsWith("/post/")) html() else { writes++; """{"responseContext":{}}""" }
        }) { web }
        assertTrue(runCatching { repository.rate(identity, VideoRating.Like, VideoRating.None) }.isFailure)
        assertEquals(2, writes)
    }

    @Test fun expiredWebCookiesOfferAuthorizationInsteadOfSendingAnEmptyCommand() = runBlocking {
        var writes = 0
        val client = client { path, _ ->
            if (path.startsWith("/post/")) html().replace("\"performCommentActionEndpoint\":{\"action\":\"LIKE\"}", "\"signInEndpoint\":{}")
            else { writes++; "{}" }
        }
        assertTrue(runCatching { MediaPostRepository(client) { web }.rate(identity, VideoRating.Like, VideoRating.None) }.exceptionOrNull() is PostAuthorizationRequiredException)
        val comments = MediaCommentsRepository(client, webContext = { web })
        val target = CommentTarget.Post(identity)
        comments.comments(target, CommentSort.Top, null)
        assertTrue(runCatching { comments.perform(CommentNavigationRequest.SubmitComment(target, "Kept draft")) }
            .exceptionOrNull() is PostAuthorizationRequiredException)
        // The browse request reads comments; no comment or feedback mutation is submitted.
        assertEquals(1, writes)
    }
}
