package com.hhst.youtubelite.data.media

import com.hhst.youtubelite.comments.domain.CommentNavigationRequest
import com.hhst.youtubelite.comments.domain.CommentSort
import com.hhst.youtubelite.comments.domain.CommentTarget
import com.hhst.youtubelite.watch.domain.UnconfirmedActionException
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestContext
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class MediaCommentsRepositoryTest {
    @Test fun newestUsesTheOpeningSortKeyWithoutFetchingTopAndKeepsTheCreationToken() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"engagementPanelSectionListRenderer":{"panelIdentifier":"engagement-panel-comments-section","content":{"continuationEndpoint":{"continuationCommand":{"token":"top-comments"}}}},"sortFilterSubMenuRenderer":{"subMenuItems":[{"title":"Newest first","selected":false,"serviceEndpoint":{"continuationCommand":{"token":"newest"}}}]},"createCommentEndpoint":{"createCommentParams":"opening-create-key"}}"""))
            server.enqueue(MockResponse().setBody("""{"continuationContents":{"itemSectionContinuation":{"contents":[{"commentRenderer":{"commentId":"newest-comment","contentText":{"simpleText":"Newest"}}}]}}}"""))
            server.enqueue(MockResponse().setBody("""{"commentRenderer":{"commentId":"created","contentText":{"simpleText":"Hello"}}}"""))
            val transport = OkHttpClient.Builder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
            }.build()
            val repository = MediaCommentsRepository(MediaClient(transport) { RequestContext(accessToken = "access", generation = 7) })
            val target = CommentTarget.Video("abcdefghijk")
            val page = repository.comments(target, CommentSort.Newest, null)
            assertEquals(listOf("newest-comment"), page.threads.map { it.id })
            assertFalse(page.sortFallback)
            assertEquals(2, server.requestCount)
            assertTrue(server.takeRequest().body.readUtf8().contains("\"videoId\":\"abcdefghijk\""))
            val selected = server.takeRequest().body.readUtf8()
            assertTrue(selected.contains("\"continuation\":\"newest\""))
            assertFalse(selected.contains("top-comments"))
            repository.perform(CommentNavigationRequest.SubmitComment(target, "Hello"))
            assertTrue(server.takeRequest().body.readUtf8().contains("\"createCommentParams\":\"opening-create-key\""))
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun aVideoReadWithoutCreationTokensCanSubmitExactlyOnce() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"engagementPanelSectionListRenderer":{"panelIdentifier":"engagement-panel-comments-section","content":{"continuationEndpoint":{"continuationCommand":{"token":"comments"}}}}}"""))
            server.enqueue(MockResponse().setBody("""{"continuationContents":{"itemSectionContinuation":{"contents":[]}}}"""))
            server.enqueue(MockResponse().setBody("""{"actionResult":{"status":"STATUS_SUCCEEDED"}}"""))
            val transport = OkHttpClient.Builder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
            }.build()
            val repository = MediaCommentsRepository(MediaClient(transport) { RequestContext(accessToken = "access", generation = 7) })
            val target = CommentTarget.Video("abcdefghijk")
            repository.comments(target, CommentSort.Top, null)
            repository.perform(CommentNavigationRequest.SubmitComment(target, "Hello"))
            repeat(2) { server.takeRequest() }
            val posted = server.takeRequest()
            assertTrue(posted.path.orEmpty().startsWith("/youtubei/v1/comment/create_comment"))
            val body = posted.body.readUtf8()
            assertTrue(body.contains("\"createCommentParams\":"))
            assertTrue(body.contains("\"clientName\":\"TVHTML5\""))
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun postingAfterChangingSortUsesTheInitialCreationTokenAndRequiresConfirmation() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"engagementPanelSectionListRenderer":{"panelIdentifier":"engagement-panel-comments-section","content":{"continuationEndpoint":{"continuationCommand":{"token":"comments"}}}},"createCommentEndpoint":{"createCommentParams":"create-key"}}"""))
            server.enqueue(MockResponse().setBody("""{"sortFilterSubMenuRenderer":{"subMenuItems":[{"title":"Newest first","serviceEndpoint":{"continuationCommand":{"token":"newest"}}}]}}"""))
            server.enqueue(MockResponse().setBody("""{"continuationContents":{"itemSectionContinuation":{"contents":[]}}}"""))
            server.enqueue(MockResponse().setBody("""{"commentRenderer":{"commentId":"created","contentText":{"simpleText":"Hello"}}}"""))
            server.enqueue(MockResponse().setBody("{}"))
            val transport = OkHttpClient.Builder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
            }.build()
            val repository = MediaCommentsRepository(MediaClient(transport) { RequestContext(accessToken = "access", generation = 7) })
            val target = CommentTarget.Video("abcdefghijk")
            repository.comments(target, CommentSort.Newest, null)
            repository.perform(CommentNavigationRequest.SubmitComment(target, "Hello"))
            repeat(3) { server.takeRequest() }
            val posted = server.takeRequest()
            assertEquals("Bearer access", posted.getHeader("Authorization"))
            assertTrue(posted.path.orEmpty().startsWith("/youtubei/v1/comment/create_comment"))
            assertTrue(posted.body.readUtf8().contains("\"createCommentParams\":\"create-key\""))
            val failure = runCatching { repository.perform(CommentNavigationRequest.SubmitComment(target, "Hello")) }.exceptionOrNull()
            assertTrue(failure is UnconfirmedActionException)
            assertEquals(5, server.requestCount)
        }
    }
}
