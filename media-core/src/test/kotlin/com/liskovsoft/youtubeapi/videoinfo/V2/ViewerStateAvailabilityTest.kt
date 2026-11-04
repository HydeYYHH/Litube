package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerStateAvailabilityTest {
    @Test fun absentCommentViewerStateIsUnknownRatherThanNeutral() {
        assertNull(commentPage("""{"commentRenderer":{"commentId":"comment"}}""").comments.single().viewerRating)
        assertNull(commentPage("""{"commentRenderer":{"commentId":"comment","isLiked":false}}""").comments.single().viewerRating)
        assertEquals("INDIFFERENT", commentPage("""{"commentRenderer":{"commentId":"comment","isLiked":false,
            "actionButtons":{"commentActionButtonsRenderer":{"dislikeButton":{"toggleButtonRenderer":{"isToggled":false}}}}}}""")
            .comments.single().viewerRating)
    }

    @Test fun explicitCommentTogglesReportTheirStateAndContradictoryFlagsStayUnknown() {
        for ((button, expected) in listOf("likeButton" to "LIKE", "dislikeButton" to "DISLIKE")) {
            val comment = commentPage("""{"commentRenderer":{"commentId":"comment","actionButtons":{
                "commentActionButtonsRenderer":{"$button":{"toggleButtonRenderer":{"isToggled":true}}}}}}""").comments.single()
            assertEquals(expected, comment.viewerRating)
            assertEquals(expected == "LIKE", comment.liked)
            assertEquals(expected == "DISLIKE", comment.disliked)
        }
        assertNull(commentPage("""{"commentRenderer":{"commentId":"comment","isLiked":true,"isDisliked":true}}""")
            .comments.single().viewerRating)
    }

    @Test fun modernCommentWithoutToolbarStateDoesNotInventANeutralState() {
        val comment = commentPage("""{"commentEntityPayload":{"key":"comment-key","properties":{"commentId":"comment"}}}""")
            .comments.single()
        assertNull(comment.viewerRating)
        assertFalse(comment.liked)
        assertFalse(comment.disliked)
    }

    @Test fun explicitModernToolbarStateRetainsAllThreeViewerStates() {
        for (state in listOf("LIKED", "DISLIKED", "INDIFFERENT")) {
            val comment = commentPage("""{"commentViewModel":{"commentKey":"comment-key","toolbarStateKey":"toolbar-key"},
                "commentEntityPayload":{"key":"comment-key","properties":{"commentId":"comment"}},
                "commentToolbarStateEntityPayload":{"key":"toolbar-key","likeState":"TOOLBAR_LIKE_STATE_$state"}}""")
                .comments.single()
            assertEquals(when (state) { "LIKED" -> "LIKE"; "DISLIKED" -> "DISLIKE"; else -> "INDIFFERENT" }, comment.viewerRating)
        }
    }

    @Test fun aPollNeedsExplicitSelectionEvidenceToConfirmARemovedVote() {
        fun poll(choices: String) = postPoll(jsonObject("""{"choices":$choices}""")!!)
        assertFalse(poll("""[{"text":{"simpleText":"First"}}]""").selectionKnown)
        assertTrue(poll("""[{"selected":false},{"isSelected":false}]""").selectionKnown)
        assertTrue(poll("""[{"selected":true},{}]""").selectionKnown)
        assertFalse(poll("""[{"selected":false},{}]""").selectionKnown)
        assertFalse(poll("""[{"selected":false},42]""").selectionKnown)
    }
}
