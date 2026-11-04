package com.hhst.youtubelite.data.media

import com.hhst.youtubelite.comments.domain.CommentNavigationRequest
import com.hhst.youtubelite.comments.domain.CommentSort
import com.hhst.youtubelite.comments.domain.CommentTarget
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestContext
import com.liskovsoft.youtubeapi.videoinfo.V2.loadWatch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class MediaCommentWatchReuseTest {
    private val videoId = "abcdefghijk"
    private val target = CommentTarget.Video(videoId)
    private val context = RequestContext(accountId = "account", accessToken = "access", visitor = "visitor",
        language = "zh", region = "CN", generation = 7)

    private fun client(server: MockWebServer) = MediaClient(OkHttpClient.Builder().addInterceptor { chain ->
        chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
    }.build()) { context }

    private fun opening(createKey: String = "create-key") = """{
        "engagementPanelSectionListRenderer":{"panelIdentifier":"engagement-panel-comments-section",
            "content":{"continuationEndpoint":{"continuationCommand":{"token":"comments"}}}},
        "createCommentEndpoint":{"createCommentParams":"$createKey"},
        "commentsEntryPointRenderer":{"commentCount":{"simpleText":"42"}}
    }"""

    private val emptyComments = """{"continuationContents":{"itemSectionContinuation":{"contents":[]}}}"""

    @Test fun anExistingWatchReadSkipsTheDuplicateNextAndPreservesNewestAndCreation() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(opening()))
            server.enqueue(MockResponse().setBody("""{"sortFilterSubMenuRenderer":{"subMenuItems":[
                {"title":"Newest first","serviceEndpoint":{"continuationCommand":{"token":"newest"}}}
            ]}}"""))
            server.enqueue(MockResponse().setBody(emptyComments))
            server.enqueue(MockResponse().setBody("""{"actionResult":{"status":"STATUS_SUCCEEDED"}}"""))
            val client = client(server)
            val shared = SharedWatchReads()
            client.read { ctx, http -> shared.read(videoId, ctx, http, false) { id, identity, transport ->
                loadWatch(id, identity, http = transport)
            }.requireValue() }
            val repository = MediaCommentsRepository(client, shared)
            val page = repository.comments(target, CommentSort.Newest, null)
            assertEquals("42", page.total)
            assertFalse(page.sortFallback)
            repository.perform(CommentNavigationRequest.SubmitComment(target, "Hello"))

            val requests = List(4) { server.takeRequest() }
            val bodies = requests.map { it.body.readUtf8() }
            assertTrue(bodies[0].contains("\"videoId\":\"$videoId\""))
            assertTrue(bodies[1].contains("\"continuation\":\"comments\""))
            assertTrue(bodies[2].contains("\"continuation\":\"newest\""))
            assertTrue(bodies[3].contains("\"createCommentParams\":\"create-key\""))
            assertTrue(bodies.all { it.contains("\"clientName\":\"TVHTML5\"") && it.contains("\"hl\":\"zh\"") && it.contains("\"gl\":\"CN\"") })
            assertTrue(requests.all { it.getHeader("Authorization") == "Bearer access" })
            assertEquals(4, server.requestCount)
        }
    }

    @Test fun disabledAndEmptyWatchResponsesNeedNoCommentRequest() = runBlocking {
        for (disabled in listOf(false, true)) MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(if (disabled)
                """{"messageRenderer":{"text":{"simpleText":"Comments are turned off"}},"commentsEntryPointRenderer":{"commentCount":{"simpleText":"0"}}}"""
                else "{}"))
            val client = client(server)
            val shared = SharedWatchReads()
            client.read { ctx, http -> shared.read(videoId, ctx, http, false) { id, identity, transport ->
                loadWatch(id, identity, http = transport)
            }.requireValue() }
            val page = MediaCommentsRepository(client, shared).comments(target, CommentSort.Top, null)
            assertTrue(page.threads.isEmpty())
            assertNull(page.nextCursor)
            assertEquals(disabled, page.turnedOff)
            assertEquals(if (disabled) "0" else "", page.total)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun aConfirmedCommentWriteInvalidatesTheOpeningCapabilities() = runBlocking {
        MockWebServer().use { server ->
            listOf(opening("old-create"), emptyComments,
                """{"actionResult":{"status":"STATUS_SUCCEEDED"}}""", opening("new-create"), emptyComments)
                .forEach { server.enqueue(MockResponse().setBody(it)) }
            val client = client(server)
            val repository = MediaCommentsRepository(client, SharedWatchReads())
            repository.comments(target, CommentSort.Top, null)
            repository.perform(CommentNavigationRequest.SubmitComment(target, "Hello"))
            repository.comments(target, CommentSort.Top, null)
            val bodies = List(5) { server.takeRequest().body.readUtf8() }
            assertTrue(bodies[2].contains("\"createCommentParams\":\"old-create\""))
            assertTrue(bodies[3].contains("\"videoId\":\"$videoId\""))
            assertEquals(5, server.requestCount)
        }
    }

    @Test fun paginationUsesOnlyItsOwnContinuationEvenWithSharedReads() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(emptyComments))
            val repository = MediaCommentsRepository(client(server), SharedWatchReads())
            repository.comments(target, CommentSort.Top, "page-two")
            assertTrue(server.takeRequest().body.readUtf8().contains("\"continuation\":\"page-two\""))
            assertEquals(1, server.requestCount)
        }
    }
}
