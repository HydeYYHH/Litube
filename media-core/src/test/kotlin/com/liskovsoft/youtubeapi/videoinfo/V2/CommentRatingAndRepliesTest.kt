package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class CommentRatingAndRepliesTest {
    private val signedIn = RequestContext(accessToken = "access", generation = 7)

    private fun tvComment(count: String = "2") = commentPage("""{
      "commentThreadRenderer":{"comment":{"commentRenderer":{
        "commentId":"parent","contentText":{"simpleText":"Parent"},"repliesCount":{"simpleText":"$count"},
        "detailViewEndpoint":{"showEngagementPanelEndpoint":{"engagementPanel":{"engagementPanelSectionListRenderer":{
          "content":{"sectionListRenderer":{"contents":[{"itemSectionRenderer":{
            "continuations":[{"reloadContinuationData":{"continuation":"detail"}}]
          }}]}}
        }}}}
      }}}
    }""").comments.single()

    private fun detail(liked: Boolean = false, disliked: Boolean = false, id: String = "parent") = """{
      "continuationContents":{"itemSectionContinuation":{"contents":[
        {"commentRenderer":{"commentId":"other","actionButtons":{"commentActionButtonsRenderer":{
          "likeButton":{"toggleButtonRenderer":{"defaultServiceEndpoint":{"performCommentActionEndpoint":{"action":"WRONG"}}}}
        }}}},
        {"commentRenderer":{"commentId":"$id","isLiked":$liked,"actionButtons":{"commentActionButtonsRenderer":{
          "likeButton":{"toggleButtonRenderer":{
            "defaultServiceEndpoint":{"performCommentActionEndpoint":{"action":"LIKE"}},
            "toggledServiceEndpoint":{"performCommentActionEndpoint":{"action":"UNLIKE"}}
          }},
          "dislikeButton":{"toggleButtonRenderer":{"isToggled":$disliked,
            "defaultServiceEndpoint":{"performCommentActionEndpoint":{"action":"DISLIKE"}},
            "toggledServiceEndpoint":{"performCommentActionEndpoint":{"action":"UNDISLIKE"}}
          }}
        }}}}
      ]}}
    }"""

    @Test fun tvDetailContinuationAndPluralReplyCountArePreserved() {
        val comment = tvComment()
        assertEquals("detail", comment.detailKey)
        assertEquals("detail", comment.replyKey)
        assertEquals("2", comment.replyCount)
        assertNull(comment.likeKey)
        assertEquals("detail", tvComment("0").detailKey)
        assertNull(tvComment("0").replyKey)
    }

    @Test fun legacyReplyContinuationsAndLabelsAreReadWithoutBecomingListPagination() {
        for (kind in listOf("nextContinuationData", "reloadContinuationData")) {
            val page = commentPage("""{"contents":[{"commentThreadRenderer":{
              "comment":{"commentRenderer":{"commentId":"parent"}},
              "replies":{"commentRepliesRenderer":{
                "viewReplies":{"buttonRenderer":{"text":{"runs":[{"text":"2 replies"}]}}},
                "continuations":[{"$kind":{"continuation":"replies"}}]
              }}
            }}]}""")
            assertEquals("replies", page.comments.single().replyKey)
            assertEquals("2 replies", page.comments.single().replyCount)
            assertNull(page.nextKey)
        }
    }

    @Test fun modernEngagementCommandsProvideAllFourRatingActions() {
        for (state in listOf("LIKED", "DISLIKED", "INDIFFERENT")) {
            val comment = commentPage("""{
              "contents":[{"commentViewModel":{"commentKey":"comment","toolbarSurfaceKey":"surface","toolbarStateKey":"state"}}],
              "frameworkUpdates":{"entityBatchUpdate":{"mutations":[
                {"entityKey":"comment","payload":{"commentEntityPayload":{"properties":{"commentId":"parent","content":{"content":"Hello"}}}}},
                {"entityKey":"state","payload":{"engagementToolbarStateEntityPayload":{"likeState":"TOOLBAR_LIKE_STATE_$state"}}},
                {"entityKey":"surface","payload":{"engagementToolbarSurfaceEntityPayload":{
                  "likeCommand":{"innertubeCommand":{"performCommentActionEndpoint":{"action":"LIKE"}}},
                  "unlikeCommand":{"innertubeCommand":{"performCommentActionEndpoint":{"action":"UNLIKE"}}},
                  "dislikeCommand":{"innertubeCommand":{"performCommentActionEndpoint":{"action":"DISLIKE"}}},
                  "undislikeCommand":{"innertubeCommand":{"performCommentActionEndpoint":{"action":"UNDISLIKE"}}}
                }}}
              ]}}
            }""").comments.single()
            assertEquals("LIKE", comment.likeKey)
            assertEquals("UNLIKE", comment.unlikeKey)
            assertEquals("DISLIKE", comment.dislikeKey)
            assertEquals("UNDISLIKE", comment.undislikeKey)
            assertEquals(state == "LIKED", comment.liked)
            assertEquals(state == "DISLIKED", comment.disliked)
        }
    }

    @Test fun tvRatingsReadDetailBeforeWritingAndUseTheCurrentServerState() {
        for ((like, selected) in listOf(true to false, true to true, false to false, false to true)) {
            val requests = mutableListOf<HttpRequest>()
            val http = object : HttpClient {
                override fun execute(request: HttpRequest): HttpReply {
                    requests += request
                    assertEquals("Bearer access", request.headers["Authorization"])
                    assertTrue(request.body.contains("\"clientName\":\"TVHTML5\""))
                    return if (requests.size == 1) {
                        assertTrue(request.body.contains("\"continuation\":\"detail\""))
                        HttpReply(200, detail(liked = like && selected, disliked = !like && selected))
                    } else HttpReply(200, """{"actionResults":[{"status":"STATUS_SUCCEEDED"}]}""")
                }
            }
            val result = if (like) likeComment(tvComment(), signedIn, http) else dislikeComment(tvComment(), signedIn, http)
            assertTrue(result.ok)
            assertTrue(result.value!!.action.confirmed)
            assertEquals(2, requests.size)
            assertTrue(requests.last().url.contains("/comment/perform_comment_action"))
            val expected = if (like) { if (selected) "UNLIKE" else "LIKE" } else { if (selected) "UNDISLIKE" else "DISLIKE" }
            assertTrue(requests.last().body.contains("\"actions\":[\"$expected\"]"))
        }
    }

    @Test fun existingActionsWriteOnceWithoutReadingDetail() {
        var calls = 0
        val comment = commentPage(detail()).comments.last().copy(detailKey = "detail")
        val result = likeComment(comment, signedIn, object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                calls++
                assertTrue(request.url.contains("/comment/perform_comment_action"))
                return HttpReply(200, """{"status":"STATUS_SUCCEEDED"}""")
            }
        })
        assertTrue(result.ok)
        assertEquals(1, calls)
    }

    @Test fun anonymousRatingNeverReadsOrWrites() {
        val result = likeComment(tvComment(), RequestContext(), object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply = error("Unexpected HTTP call")
        })
        assertEquals(RequestFailure.AUTH, result.error?.kind)
    }

    @Test fun missingTargetOrFailedDetailNeverWritesAnotherCommentsAction() {
        for (reply in listOf(HttpReply(200, detail(id = "different")), HttpReply(503, "{}"), HttpReply(200, "{}"))) {
            var calls = 0
            val result = likeComment(tvComment(), signedIn, object : HttpClient {
                override fun execute(request: HttpRequest): HttpReply {
                    calls++
                    assertFalse(request.url.contains("/comment/perform_comment_action"))
                    return reply
                }
            })
            assertFalse(result.ok)
            assertEquals(1, calls)
        }
    }

    @Test fun missingRatingCapabilityIsReportedWithoutAnInvalidRequest() {
        val result = likeComment(tvComment().copy(detailKey = null), signedIn, object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply = error("Unexpected HTTP call")
        })
        assertEquals(RequestFailure.UNSUPPORTED, result.error?.kind)
        assertEquals("missing_comment_rating_action", result.error?.code)
    }
}
