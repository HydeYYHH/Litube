package com.hhst.youtubelite.data.media

import androidx.lifecycle.SavedStateHandle
import com.hhst.youtubelite.comments.domain.CommentAction
import com.hhst.youtubelite.comments.domain.CommentsContent
import com.hhst.youtubelite.comments.presentation.CommentsViewModel
import com.hhst.youtubelite.core.DeviceEvidence
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestContext
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test

/** Real repository, ViewModel and loopback HTTP; no public account or timing benchmark. */
class MediaCommentsPagingAndroidTest {
    @Test fun newestSkipsTopAndAnOpeningReplyCursorIsNeverRequestedTwice() = runBlocking<Unit> {
        val requestCounts = mutableListOf<Int>()
        repeat(5) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody(opening))
                server.enqueue(MockResponse().setBody(comments))
                server.enqueue(MockResponse().setBody(replies(next = "rk")))
                val vm = withContext(Dispatchers.Main.immediate) {
                    CommentsViewModel(repository(server), SavedStateHandle(mapOf("commentsSort" to "Newest")), "abcdefghijk")
                }
                try {
                    awaitThread(vm) { true }
                    withContext(Dispatchers.Main.immediate) { vm.onAction(CommentAction.ToggleReplies("parent")) }
                    val thread = awaitThread(vm) { it.replies.size == 1 && !it.repliesLoading }
                    assertNull(thread.repliesNextCursor)
                    withContext(Dispatchers.Main.immediate) {
                        repeat(4) {
                            vm.onAction(CommentAction.LoadMoreReplies("parent"))
                            vm.onAction(CommentAction.ToggleReplies("parent"))
                            vm.onAction(CommentAction.ToggleReplies("parent"))
                        }
                    }
                    assertEquals(3, server.requestCount)
                    val bodies = (0 until 3).map { server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8() }
                    assertTrue(bodies[0].contains("\"videoId\":\"abcdefghijk\""))
                    assertTrue(bodies[1].contains("\"continuation\":\"newest\""))
                    assertTrue(bodies[2].contains("\"continuation\":\"rk\""))
                    assertTrue(bodies.none { it.contains("top-comments") })
                    requestCounts += server.requestCount
                } finally {
                    withContext(Dispatchers.Main.immediate) { vm.dispose() }
                }
            }
        }
        DeviceEvidence.writeJson("msc-comments-newest-and-reply-loop.json",
            """{"rounds":5,"requestsPerRound":$requestCounts,"openingRequestsPerRound":1,"newestRequestsPerRound":1,"replyRequestsPerRound":1,"replyLoopStopped":true}""")
    }

    @Test fun reopeningCancelsTheOldSocketAndResumesOnlyTheUnansweredReplyRead() = runBlocking<Unit> {
        val requestCounts = mutableListOf<Int>()
        repeat(5) {
            MockWebServer().use { server ->
                val replyReceived = CountDownLatch(1)
                val cancelledCall = CountDownLatch(1)
                val replyRequests = AtomicInteger()
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        val body = request.body.readUtf8()
                        return when {
                            body.contains("\"videoId\"") -> MockResponse().setBody(opening)
                            body.contains("\"continuation\":\"newest\"") -> MockResponse().setBody(comments)
                            body.contains("\"continuation\":\"rk\"") -> {
                                if (replyRequests.incrementAndGet() == 1) {
                                    replyReceived.countDown()
                                    MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                                } else MockResponse().setBody(replies(next = null))
                            }
                            else -> MockResponse().setResponseCode(500)
                        }
                    }
                }
                val listener = object : EventListener() {
                    override fun callFailed(call: Call, ioe: IOException) { cancelledCall.countDown() }
                }
                val vm = withContext(Dispatchers.Main.immediate) {
                    CommentsViewModel(repository(server, listener), SavedStateHandle(mapOf("commentsSort" to "Newest")), "abcdefghijk")
                }
                try {
                    awaitThread(vm) { true }
                    withContext(Dispatchers.Main.immediate) { vm.onAction(CommentAction.ToggleReplies("parent")) }
                    assertTrue(replyReceived.await(5, TimeUnit.SECONDS))
                    withContext(Dispatchers.Main.immediate) { vm.cancelTransient(); vm.ensureLoaded() }
                    assertTrue(cancelledCall.await(5, TimeUnit.SECONDS))
                    val thread = awaitThread(vm) { it.replies.size == 1 && !it.repliesLoading }
                    assertEquals("reply", thread.replies.single().id)
                    withContext(Dispatchers.Main.immediate) { vm.cancelTransient(); vm.ensureLoaded() }
                    assertEquals(2, replyRequests.get())
                    assertEquals(4, server.requestCount)
                    requestCounts += server.requestCount
                } finally {
                    withContext(Dispatchers.Main.immediate) { vm.dispose() }
                }
            }
        }
        DeviceEvidence.writeJson("msc-comments-cancel-resume.json",
            """{"rounds":5,"requestsPerRound":$requestCounts,"initialCommentRequestsPerRound":2,"replyRequestsPerRound":2,"cancelledSocket":true,"settledReopenRequests":0}""")
    }

    private fun repository(server: MockWebServer, listener: EventListener = EventListener.NONE): MediaCommentsRepository {
        val transport = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).eventListener(listener).addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
        }.build()
        return MediaCommentsRepository(MediaClient(transport) { RequestContext(accessToken = "fixture-access", generation = 7) })
    }

    private suspend fun awaitThread(vm: CommentsViewModel, predicate: (com.hhst.youtubelite.comments.domain.CommentThread) -> Boolean) =
        withTimeout(5_000) {
            vm.uiState.first { state -> (state.content as? CommentsContent.Ready)?.threads?.singleOrNull()?.let(predicate) == true }
                .let { (it.content as CommentsContent.Ready).threads.single() }
        }

    private val opening = """{"engagementPanelSectionListRenderer":{"panelIdentifier":"engagement-panel-comments-section","content":{"continuationEndpoint":{"continuationCommand":{"token":"top-comments"}}}},"sortFilterSubMenuRenderer":{"subMenuItems":[{"title":"Newest first","selected":false,"serviceEndpoint":{"continuationCommand":{"token":"newest"}}}]}}"""
    private val comments = """{"continuationContents":{"itemSectionContinuation":{"contents":[{"commentThreadRenderer":{"comment":{"commentRenderer":{"commentId":"parent","contentText":{"simpleText":"Parent"},"replyCount":1}},"replies":{"commentRepliesRenderer":{"continuationCommand":{"token":"rk"}}}}}]}}}"""
    private fun replies(next: String?): String {
        val continuation = next?.let {
            """,{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"$it"}}}}"""
        }.orEmpty()
        return """{"onResponseReceivedEndpoints":[{"appendContinuationItemsAction":{"continuationItems":[{"commentRenderer":{"commentId":"parent"}},{"commentRenderer":{"commentId":"reply","contentText":{"simpleText":"Reply"}}}$continuation]}}]}"""
    }
}
