package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionConfirmationScopeTest {
    private val context = RequestContext(accessToken = "fixture-token")
    private val comment = commentPage("""{"commentRenderer":{"commentId":"target","isLiked":false}}""")
        .comments.single().copy(likeKey = "LIKE")
    private fun http(body: String) = object : HttpClient {
        override fun execute(request: HttpRequest) = HttpReply(200, body)
    }

    @Test fun ratingACommentCannotBeConfirmedByAnUnrelatedEntity() {
        for (body in listOf("""{"commentRenderer":{"commentId":"other","voteCount":{"simpleText":"923"}}}""",
            """{"playlistId":"unrelated-playlist"}""")) {
            val reply = rateComment(comment, CommentVote.LIKE, context, http(body)).value!!
            assertFalse(reply.action.confirmed)
        }
    }

    @Test fun aMatchingCommentEntityStillConfirmsAndPreservesItsServerCount() {
        val reply = rateComment(comment, CommentVote.LIKE, context,
            http("""{"commentRenderer":{"commentId":"target","voteCount":{"simpleText":"923"}}}""")).value!!
        assertTrue(reply.action.confirmed)
        assertEquals("923", reply.comment!!.likeCount)
    }

    @Test fun viewerStateWritesCannotBorrowConfirmationFromCommentOrPlaylistEntities() {
        for (body in listOf("""{"commentRenderer":{"commentId":"unrelated-comment"}}""",
            """{"playlistId":"unrelated-playlist"}""")) {
            assertFalse(voteVideo("abcdefghijk", VideoVote.LIKE, context, http(body)).value!!.confirmed)
            assertFalse(subscribeChannel("UCabcdefghijklmnopqrstuv", true, context, http = http(body)).value!!.confirmed)
        }
    }

    @Test fun anExplicitPendingStatusCannotBeOverriddenBySuccessFlagsOrEntities() {
        for (extra in listOf("\"success\":true", "\"actionResult\":{\"status\":\"STATUS_SUCCEEDED\"}")) {
            val body = """{"status":"PENDING",$extra,"commentRenderer":{"commentId":"target"}}"""
            assertFalse(rateComment(comment, CommentVote.LIKE, context, http(body)).value!!.action.confirmed)
            assertFalse(voteVideo("abcdefghijk", VideoVote.LIKE, context, http(body)).value!!.confirmed)
        }
        val nested = """{"actionResult":{"status":"PENDING","success":true},"commentRenderer":{"commentId":"target"}}"""
        assertFalse(rateComment(comment, CommentVote.LIKE, context, http(nested)).value!!.action.confirmed)
        assertFalse(voteVideo("abcdefghijk", VideoVote.LIKE, context, http(nested)).value!!.confirmed)
    }

    @Test fun createAndReplyKeepTheirGenericReturnedEntityConfirmation() {
        val transport = http("""{"commentRenderer":{"commentId":"new-comment","contentText":{"simpleText":"Posted"}}}""")
        assertTrue(createComment("Posted", "create", context, transport).value!!.action.confirmed)
        assertTrue(replyComment("Posted", "reply", context, transport).value!!.action.confirmed)
    }

    @Test fun postFeedbackCannotBorrowConfirmationFromUnrelatedEntities() {
        val web = context.copy(accessToken = null, webSession = WebSession("SAPISID=fixture; SID=fixture"))
        val command = PostCommand("feedback", "\"feedbackTokens\":[\"fixture-feedback\"]")
        for (body in listOf("""{"commentRenderer":{"commentId":"unrelated-comment"}}""",
            """{"playlistId":"unrelated-playlist"}""")) {
            assertFalse(performPostCommand(command, web, http(body)).value!!.confirmed)
        }
    }
}
