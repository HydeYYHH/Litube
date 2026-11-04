package com.hhst.youtubelite.data.media

import com.hhst.youtubelite.comments.domain.*
import com.hhst.youtubelite.watch.domain.VideoRating
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestContext
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class MediaCommentWriteContinuityTest {
    @Test fun repeatedRatingsUseConfirmedViewerStateAndActionTokensWithoutReloadingTheCommentList() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"engagementPanelSectionListRenderer":{"panelIdentifier":"engagement-panel-comments-section",
                "content":{"continuationEndpoint":{"continuationCommand":{"token":"comments"}}}}}"""))
            server.enqueue(MockResponse().setBody("""{"commentRenderer":{"commentId":"c","contentText":{"simpleText":"Comment"},
                "voteCount":{"simpleText":"10"},"actionButtons":{"commentActionButtonsRenderer":{
                  "likeButton":{"toggleButtonRenderer":{"isToggled":false,
                    "defaultServiceEndpoint":{"performCommentActionEndpoint":{"action":"like"}},
                    "toggledServiceEndpoint":{"performCommentActionEndpoint":{"action":"unlike"}}}},
                  "dislikeButton":{"toggleButtonRenderer":{"isToggled":false,
                    "defaultServiceEndpoint":{"performCommentActionEndpoint":{"action":"dislike"}},
                    "toggledServiceEndpoint":{"performCommentActionEndpoint":{"action":"undislike"}}}}
                }}}}"""))
            repeat(4) { server.enqueue(MockResponse().setBody("""{"actionResults":[{"status":"STATUS_SUCCEEDED"}]}""")) }
            val client = MediaClient(OkHttpClient.Builder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
            }.build()) { RequestContext(accessToken = "access", generation = 7) }
            val repository = MediaCommentsRepository(client)
            val target = CommentTarget.Video("abcdefghijk")
            val original = repository.comments(target, CommentSort.Top, null).threads.single()
            assertEquals("10", original.likes)
            val like = repository.perform(CommentNavigationRequest.LikeComment(target, "c", VideoRating.Like, VideoRating.None)).comment!!
            assertEquals(VideoRating.Like, like.rating)
            assertEquals("11", like.likes)
            val unlike = repository.perform(CommentNavigationRequest.LikeComment(target, "c", VideoRating.None, VideoRating.Like)).comment!!
            assertEquals(VideoRating.None, unlike.rating)
            assertEquals("10", unlike.likes)
            assertEquals(VideoRating.Dislike, repository.perform(CommentNavigationRequest.DislikeComment(target, "c", VideoRating.Dislike, VideoRating.None)).comment!!.rating)
            assertEquals(VideoRating.None, repository.perform(CommentNavigationRequest.DislikeComment(target, "c", VideoRating.None, VideoRating.Dislike)).comment!!.rating)
            repeat(2) { server.takeRequest() }
            for (action in listOf("like", "unlike", "dislike", "undislike")) {
                assertTrue(server.takeRequest().body.readUtf8().contains("\"actions\":[\"$action\"]"))
            }
            assertEquals(6, server.requestCount)
        }
    }
}
