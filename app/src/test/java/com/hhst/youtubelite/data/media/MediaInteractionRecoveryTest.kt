package com.hhst.youtubelite.data.media

import com.hhst.youtubelite.comments.domain.CommentNavigationRequest
import com.hhst.youtubelite.comments.domain.CommentSort
import com.hhst.youtubelite.comments.domain.CommentTarget
import com.hhst.youtubelite.post.domain.PostIdentity
import com.hhst.youtubelite.watch.domain.UnconfirmedActionException
import com.hhst.youtubelite.watch.domain.VideoRating
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestContext
import com.liskovsoft.youtubeapi.videoinfo.V2.WebSession
import com.liskovsoft.youtubeapi.videoinfo.V2.commentPage
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaInteractionRecoveryTest {
    private val identity = PostIdentity("UCauthor", "Ugkx_post")
    private val context = RequestContext(accountId = "account", accessToken = "oauth", generation = 7)
    private val web = context.copy(accessToken = null, webSession = WebSession("SAPISID=fixture; SID=fixture"))
    private val target = CommentTarget.Video("abcdefghijk")

    private fun client(respond: (String, String) -> String) = MediaClient(OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        val body = Buffer().also { request.body?.writeTo(it) }.readUtf8()
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
            .body(respond(request.url.encodedPath, body).toResponseBody()).build()
    }.build()) { context }

    private fun post(status: String = "INDIFFERENT", token: String = "old", selected: Boolean = false) =
        """<script>var ytInitialData = {"backstagePostRenderer":{"postId":"${identity.postId}",
          "voteStatus":"$status","voteCount":{"simpleText":"10"},"actionButtons":{"commentActionButtonsRenderer":{
            "likeButton":{"toggleButtonRenderer":{"defaultServiceEndpoint":{"performCommentActionEndpoint":{"action":"$token-like"}},
              "toggledServiceEndpoint":{"performCommentActionEndpoint":{"action":"$token-unlike"}}}},
            "dislikeButton":{"toggleButtonRenderer":{"defaultServiceEndpoint":{"performCommentActionEndpoint":{"action":"$token-dislike"}},
              "toggledServiceEndpoint":{"performCommentActionEndpoint":{"action":"$token-undislike"}}}}}},
          "backstageAttachment":{"pollRenderer":{"choices":[{"text":{"simpleText":"First"},"selected":$selected,
            "selectServiceEndpoint":{"feedbackEndpoint":{"feedbackToken":"$token-select"}},
            "deselectServiceEndpoint":{"feedbackEndpoint":{"feedbackToken":"$token-deselect"}}}]}}}};</script>"""

    @Test fun anUnacknowledgedPostRatingReadsNewCapabilitiesBeforeOneRetry() = runBlocking {
        val writes = mutableListOf<String>()
        val repository = MediaPostRepository(client { path, body ->
            if (path.startsWith("/post/")) post(token = if (writes.isEmpty()) "old" else "fresh")
            else { writes += body; if (writes.size == 1) "{}" else """{"status":"STATUS_SUCCEEDED"}""" }
        }) { web }
        repository.post(identity)
        assertEquals(VideoRating.Like, repository.rate(identity, VideoRating.Like, VideoRating.None).rating)
        assertEquals(2, writes.size)
        assertTrue(writes.first().contains("old-like"))
        assertTrue(writes.last().contains("fresh-like"))
    }

    @Test fun anAlreadyCommittedPostRatingIsNotWrittenTwice() = runBlocking {
        var writes = 0
        val repository = MediaPostRepository(client { path, _ ->
            if (path.startsWith("/post/")) post(status = if (writes == 0) "INDIFFERENT" else "LIKE")
            else { writes++; "{}" }
        }) { web }
        assertEquals(VideoRating.Like, repository.rate(identity, VideoRating.Like, VideoRating.None).rating)
        assertEquals(1, writes)
    }

    @Test fun anUnacknowledgedPollVoteReacquiresTheCurrentSelectedCommand() = runBlocking {
        val writes = mutableListOf<String>()
        val repository = MediaPostRepository(client { path, body ->
            if (path.startsWith("/post/")) post(token = if (writes.isEmpty()) "old" else "fresh")
            else { writes += body; if (writes.size == 1) "{}" else """{"status":"STATUS_SUCCEEDED"}""" }
        }) { web }
        repository.post(identity)
        assertTrue(repository.vote(identity, 0, true).poll!!.options.single().selected)
        assertEquals(2, writes.size)
        assertTrue(writes.first().contains("old-select"))
        assertTrue(writes.last().contains("fresh-select"))
        assertFalse(writes.last().contains("old-select"))
    }

    private val opening = """{"engagementPanelSectionListRenderer":{"panelIdentifier":"engagement-panel-comments-section",
        "content":{"continuationEndpoint":{"continuationCommand":{"token":"comments"}}}}}"""

    private fun comment(liked: Boolean = false, token: String = "old", includeTarget: Boolean = true, disliked: Boolean = false) =
        if (!includeTarget) "{}" else """{"commentRenderer":{"commentId":"comment","isLiked":$liked,
          "voteCount":{"simpleText":"${if (liked) "11" else "10"}"},"contentText":{"simpleText":"Comment"},
          "detailViewEndpoint":{"showEngagementPanelEndpoint":{"engagementPanel":{"engagementPanelSectionListRenderer":{
            "content":{"sectionListRenderer":{"contents":[{"itemSectionRenderer":{
              "continuations":[{"reloadContinuationData":{"continuation":"comment-detail"}}]}}]}}
          }}}},
          "actionButtons":{"commentActionButtonsRenderer":{"likeButton":{"toggleButtonRenderer":{
            "defaultServiceEndpoint":{"performCommentActionEndpoint":{"action":"$token-like"}},
            "toggledServiceEndpoint":{"performCommentActionEndpoint":{"action":"$token-unlike"}}}},
            "dislikeButton":{"toggleButtonRenderer":{"isToggled":$disliked,
              "defaultServiceEndpoint":{"performCommentActionEndpoint":{"action":"$token-dislike"}},
              "toggledServiceEndpoint":{"performCommentActionEndpoint":{"action":"$token-undislike"}}}}}}}}"""

    @Test fun theCommentFixtureExposesTheRealDetailContinuation() {
        assertEquals("comment-detail", commentPage(comment()).comments.single().detailKey)
    }

    @Test fun anUnacknowledgedCommentRatingReadsDetailAndUsesItsNewAction() = runBlocking {
        val writes = mutableListOf<String>()
        val repository = MediaCommentsRepository(client { _, body ->
            when {
                body.contains("\"actions\"") -> { writes += body; if (writes.size == 1) "{}" else """{"status":"STATUS_SUCCEEDED"}""" }
                body.contains("\"videoId\"") -> opening
                body.contains("comment-detail") -> comment(token = "fresh")
                else -> comment()
            }
        })
        repository.comments(target, CommentSort.Top, null)
        val updated = repository.perform(CommentNavigationRequest.LikeComment(target, "comment", VideoRating.Like, VideoRating.None)).comment!!
        assertEquals(VideoRating.Like, updated.rating)
        assertEquals("11", updated.likes)
        assertEquals(2, writes.size)
        assertTrue(writes.first().contains("old-like"))
        assertTrue(writes.last().contains("fresh-like"))
    }

    @Test fun anAlreadyCommittedCommentRatingIsConfirmedByDetailWithoutASecondWrite() = runBlocking {
        var writes = 0
        val repository = MediaCommentsRepository(client { _, body ->
            when {
                body.contains("\"actions\"") -> { writes++; "{}" }
                body.contains("\"videoId\"") -> opening
                body.contains("comment-detail") -> comment(liked = true, token = "fresh")
                else -> comment()
            }
        })
        repository.comments(target, CommentSort.Top, null)
        val updated = repository.perform(CommentNavigationRequest.LikeComment(target, "comment", VideoRating.Like, VideoRating.None)).comment!!
        assertEquals(VideoRating.Like, updated.rating)
        assertEquals("11", updated.likes)
        assertEquals(1, writes)
    }

    @Test fun missingCommentReadbackNeverReplaysAnOldOpaqueAction() = runBlocking {
        var writes = 0
        val repository = MediaCommentsRepository(client { _, body ->
            when {
                body.contains("\"actions\"") -> { writes++; "{}" }
                body.contains("\"videoId\"") -> opening
                body.contains("comment-detail") -> comment(includeTarget = false)
                else -> comment()
            }
        })
        repository.comments(target, CommentSort.Top, null)
        val failure = runCatching {
            repository.perform(CommentNavigationRequest.LikeComment(target, "comment", VideoRating.Like, VideoRating.None))
        }.exceptionOrNull()
        assertTrue(failure is UnconfirmedActionException)
        assertEquals(1, writes)
    }

    @Test fun removingACommentRatingUsesTheFreshDislikeRemovalCapability() = runBlocking {
        val writes = mutableListOf<String>()
        val repository = MediaCommentsRepository(client { _, body ->
            when {
                body.contains("\"actions\"") -> { writes += body; if (writes.size == 1) "{}" else """{"status":"STATUS_SUCCEEDED"}""" }
                body.contains("\"videoId\"") -> opening
                body.contains("comment-detail") -> comment(token = "fresh", disliked = true)
                else -> comment(liked = true)
            }
        })
        repository.comments(target, CommentSort.Top, null)
        assertEquals(VideoRating.None, repository.perform(
            CommentNavigationRequest.LikeComment(target, "comment", VideoRating.None, VideoRating.Like)).comment!!.rating)
        assertEquals(2, writes.size)
        assertTrue(writes.first().contains("old-unlike"))
        assertTrue(writes.last().contains("fresh-undislike"))
    }

    @Test fun removingAPostRatingUsesTheFreshDislikeRemovalCapability() = runBlocking {
        val writes = mutableListOf<String>()
        val repository = MediaPostRepository(client { path, body ->
            if (path.startsWith("/post/")) post(status = if (writes.isEmpty()) "LIKE" else "DISLIKE", token = if (writes.isEmpty()) "old" else "fresh")
            else { writes += body; if (writes.size == 1) "{}" else """{"status":"STATUS_SUCCEEDED"}""" }
        }) { web }
        repository.post(identity)
        assertEquals(VideoRating.None, repository.rate(identity, VideoRating.None, VideoRating.Like).rating)
        assertEquals(2, writes.size)
        assertTrue(writes.first().contains("old-unlike"))
        assertTrue(writes.last().contains("fresh-undislike"))
    }

    @Test fun absentCommentViewerStateCannotConfirmARemovalOrReplayOldCapabilities() = runBlocking {
        var writes = 0
        val unknown = comment().replace("\"isLiked\":false,", "").replace("\"isToggled\":false,", "")
        val repository = MediaCommentsRepository(client { _, body ->
            when {
                body.contains("\"actions\"") -> { writes++; "{}" }
                body.contains("\"videoId\"") -> opening
                body.contains("comment-detail") -> unknown
                else -> comment(liked = true)
            }
        })
        repository.comments(target, CommentSort.Top, null)
        assertTrue(runCatching { repository.perform(CommentNavigationRequest.LikeComment(
            target, "comment", VideoRating.None, VideoRating.Like)) }.exceptionOrNull() is UnconfirmedActionException)
        assertEquals(1, writes)
    }

    @Test fun absentPollSelectionFlagsCannotConfirmARemovalOrReplayFeedback() = runBlocking {
        var writes = 0
        val repository = MediaPostRepository(client { path, _ ->
            if (path.startsWith("/post/")) {
                if (writes == 0) post(selected = true) else post().replace("\"selected\":false,", "")
            } else { writes++; "{}" }
        }) { web }
        repository.post(identity)
        assertTrue(runCatching { repository.vote(identity, 0, false) }.exceptionOrNull() is UnconfirmedActionException)
        assertEquals(1, writes)
    }
}
