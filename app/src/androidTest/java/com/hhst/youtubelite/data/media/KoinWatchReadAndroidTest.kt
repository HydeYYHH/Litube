package com.hhst.youtubelite.data.media

import com.hhst.youtubelite.comments.domain.CommentSort
import com.hhst.youtubelite.comments.domain.CommentTarget
import com.hhst.youtubelite.core.DeviceEvidence
import com.hhst.youtubelite.di.appModule
import com.hhst.youtubelite.watch.domain.WatchRelated
import com.hhst.youtubelite.watch.domain.WatchVideoMenuAction
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.koin.core.context.GlobalContext
import org.koin.dsl.module
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Own instrumentation run, without launching an Activity. */
class KoinWatchReadAndroidTest {
    @Test fun productionFactorySharesWatchAndCommentsAcrossFiveTrials() = runBlocking<Unit> {
        Loopback().use { fixture ->
            ProductionContentGraph(fixture.client).use { graph ->
                val sources = graph.sources
                assertSame(fixture.client, sources.client)
                val trials = JSONArray()
                repeat(5) { round ->
                    val id = "abcdefghij$round"
                    val before = fixture.openings.get()
                    val comments = sources.comments()
                    if (round % 2 == 0) {
                        assertEquals("Fixture title", sources.watch.watchFresh(id).video.title)
                        assertEquals("Hello", comments.comments(CommentTarget.Video(id), CommentSort.Top, null).threads.single().body)
                    } else coroutineScope {
                        val watch = async { sources.watch.watchFresh(id) }
                        val page = async { comments.comments(CommentTarget.Video(id), CommentSort.Top, null) }
                        assertEquals("Fixture title", watch.await().video.title)
                        assertEquals("Hello", page.await().threads.single().body)
                    }
                    assertEquals("The production factory must share the opening", 1, fixture.openings.get() - before)
                    trials.put(JSONObject().put("round", round + 1).put("concurrent", round % 2 != 0)
                        .put("openingRequests", fixture.openings.get() - before))
                }
                assertEquals(5, fixture.players.get())
                assertEquals(5, fixture.continuations.get())
                assertEquals(15, fixture.server.requestCount)
                DeviceEvidence.writeJson("msc-koin-watch-read.json", JSONObject().put("trials", trials)
                    .put("openingRequests", fixture.openings.get()).put("continuationRequests", fixture.continuations.get())
                    .put("playerRequests", fixture.players.get()).put("totalRequests", fixture.server.requestCount).toString(2))
            }
        }
    }

    @Test fun productionFactoryDoesNotShareAcrossAccountGenerationOrVisitorChanges() = runBlocking<Unit> {
        Loopback().use { fixture ->
            fixture.context.set(RequestContext(accountId = "loopback-account-a", accessToken = "loopback-token-a",
                visitor = "loopback-visitor", generation = -91))
            ProductionContentGraph(fixture.client).use { graph ->
                graph.sources.watch.watchFresh(VIDEO)
                val comments = graph.sources.comments()
                comments.comments(CommentTarget.Video(VIDEO), CommentSort.Top, null)
                assertEquals(1, fixture.openings.get())
                fixture.context.updateAndGet { it.copy(accountId = "loopback-account-b", accessToken = "loopback-token-b") }
                comments.comments(CommentTarget.Video(VIDEO), CommentSort.Top, null)
                assertEquals("Another account cannot reuse the previous opening", 2, fixture.openings.get())
                fixture.context.updateAndGet { it.copy(generation = -90) }
                comments.comments(CommentTarget.Video(VIDEO), CommentSort.Top, null)
                assertEquals("A new generation cannot reuse the previous opening", 3, fixture.openings.get())
                fixture.context.updateAndGet { it.copy(visitor = "another-loopback-visitor") }
                comments.comments(CommentTarget.Video(VIDEO), CommentSort.Top, null)
                assertEquals("Visitor changes also separate openings", 4, fixture.openings.get())
            }
        }
    }

    @Test fun confirmedFeedbackInvalidatesBothWatchCachesButFailureKeepsTheOpening() = runBlocking<Unit> {
        Loopback().use { fixture ->
            fixture.context.set(RequestContext(accountId = "loopback-account", accessToken = "loopback-token", generation = -92))
            ProductionContentGraph(fixture.client).use { graph ->
                val sources = graph.sources
                sources.watch.watchFresh(VIDEO)
                val comments = sources.comments()
                comments.comments(CommentTarget.Video(VIDEO), CommentSort.Top, null)
                assertEquals(1, fixture.openings.get())
                val recommendation = WatchRelated("related0000", "Related", "Author", "UCfixture", "", "", "", "",
                    notInterestedToken = "loopback-feedback", feedbackGeneration = -92)
                fixture.rejectFeedback.set(true)
                assertTrue(runCatching { sources.watch.feedback(recommendation, WatchVideoMenuAction.NotInterested) }
                    .exceptionOrNull() is MediaException)
                comments.comments(CommentTarget.Video(VIDEO), CommentSort.Top, null)
                assertEquals("Failed feedback must leave the opening available", 1, fixture.openings.get())
                fixture.rejectFeedback.set(false)
                sources.watch.feedback(recommendation, WatchVideoMenuAction.NotInterested)
                sources.watch.watch(VIDEO)
                comments.comments(CommentTarget.Video(VIDEO), CommentSort.Top, null)
                assertEquals("Confirmed feedback invalidates both cache layers", 2, fixture.openings.get())
                assertEquals(2, fixture.feedbacks.get())
            }
        }
    }

    private class Loopback : AutoCloseable {
        val context = AtomicReference(RequestContext(visitor = "loopback-visitor", generation = -93))
        val openings = AtomicInteger()
        val continuations = AtomicInteger()
        val players = AtomicInteger()
        val feedbacks = AtomicInteger()
        val rejectFeedback = AtomicBoolean()
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val body = JSONObject(request.body.clone().readUtf8())
                    val videoId = body.optString("videoId", VIDEO)
                    return when {
                    request.requestUrl?.encodedPath?.endsWith("/feedback") == true -> {
                        feedbacks.incrementAndGet()
                        if (rejectFeedback.get()) MockResponse().setResponseCode(400).setBody("{}")
                        else MockResponse().setBody("""{"feedbackResponses":[{"isProcessed":true}]}""")
                    }
                    request.requestUrl?.encodedPath?.endsWith("/player") == true -> {
                        players.incrementAndGet()
                        MockResponse().setBody(DETAILS.replace(VIDEO, videoId))
                    }
                    body.has("continuation") -> {
                        continuations.incrementAndGet()
                        MockResponse().setBody(COMMENTS)
                    }
                    else -> {
                        openings.incrementAndGet()
                        MockResponse().setBody(WATCH.replace(VIDEO, videoId)).setHeadersDelay(100, TimeUnit.MILLISECONDS)
                    }
                    }
                }
            }
            start()
        }
        private val http = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
        }.build()
        val client = MediaClient(http) { context.get() }
        override fun close() {
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdown()
            server.close()
        }
    }

    private companion object {
        const val VIDEO = "abcdefghijk"
        val DETAILS = """{"videoDetails":{"videoId":"$VIDEO","title":"Fixture title","author":"Author","channelId":"UCfixture","lengthSeconds":"30"},"microformat":{"playerMicroformatRenderer":{"publishDate":"2020-01-01"}}}"""
        val WATCH = """{"videoDetails":{"videoId":"$VIDEO","title":"Fixture title","author":"Author","channelId":"UCfixture"},"engagementPanelSectionListRenderer":{"panelIdentifier":"engagement-panel-comments-section","content":{"continuationEndpoint":{"continuationCommand":{"token":"comments"}}}},"commentsEntryPointRenderer":{"commentCount":{"simpleText":"42"}}}"""
        val COMMENTS = """{"continuationContents":{"itemSectionContinuation":{"contents":[{"commentRenderer":{"commentId":"comment-one","contentText":{"simpleText":"Hello"}}}]}}}"""
    }
}

/** Uses the target APK's factories; only the transport/context dependency is replaced. */
internal class ProductionContentGraph(client: MediaClient) : AutoCloseable {
    private val koin = GlobalContext.get()
    private val overrides = module { single { client } }
    val sources: ContentRepositories
    init {
        // Loading the same Module alone retains cached SingleInstanceFactory values.
        koin.unloadModules(listOf(appModule))
        try {
            koin.loadModules(listOf(appModule, overrides))
            sources = koin.get()
        } catch (failure: Throwable) {
            close()
            throw failure
        }
    }
    override fun close() {
        koin.unloadModules(listOf(appModule, overrides))
        koin.loadModules(listOf(appModule))
    }
}
