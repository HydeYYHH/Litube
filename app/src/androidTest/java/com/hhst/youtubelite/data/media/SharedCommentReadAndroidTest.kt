package com.hhst.youtubelite.data.media

import android.os.SystemClock
import com.hhst.youtubelite.comments.domain.CommentSort
import com.hhst.youtubelite.comments.domain.CommentTarget
import com.hhst.youtubelite.core.DeviceEvidence
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestContext
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Full app repositories and real local HTTP; response delay makes the removed RTT visible. */
class SharedCommentReadAndroidTest {
    @Test fun commentsReuseTheWatchOpeningAcrossFiveNetworkTrials() = runBlocking {
        val openings = AtomicInteger()
        val continuations = AtomicInteger()
        val players = AtomicInteger()
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path.orEmpty().endsWith("/player")) {
                        players.incrementAndGet()
                        return MockResponse().setBody(details)
                    }
                    if (request.body.clone().readUtf8().contains("\"continuation\"")) {
                        continuations.incrementAndGet()
                        return MockResponse().setBody(comments).setHeadersDelay(150, TimeUnit.MILLISECONDS)
                    }
                    openings.incrementAndGet()
                    return MockResponse().setBody(watch).setHeadersDelay(150, TimeUnit.MILLISECONDS)
                }
            }
            server.start()
            val http = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
            }.build()
            val client = MediaClient(http) { RequestContext(visitor = "comment-fixture") }
            val sharedTimes = mutableListOf<Long>()
            val separateTimes = mutableListOf<Long>()
            suspend fun trial(shared: Boolean): Long {
                val reads = if (shared) SharedWatchReads() else null
                val beforeOpening = openings.get()
                val beforeComments = continuations.get()
                val beforePlayer = players.get()
                assertEquals("Fixture title", MediaWatchRepository(client, reads).watch(videoId).video.title)
                val started = SystemClock.elapsedRealtime()
                val page = MediaCommentsRepository(client, reads).comments(CommentTarget.Video(videoId), CommentSort.Top, null)
                val elapsed = SystemClock.elapsedRealtime() - started
                assertEquals("Hello", page.threads.single().body)
                assertEquals("42", page.total)
                assertEquals(if (shared) 1 else 2, openings.get() - beforeOpening)
                assertEquals(1, continuations.get() - beforeComments)
                assertEquals(1, players.get() - beforePlayer)
                return elapsed
            }
            repeat(5) { iteration ->
                if (iteration % 2 == 0) {
                    sharedTimes += trial(true)
                    separateTimes += trial(false)
                } else {
                    separateTimes += trial(false)
                    sharedTimes += trial(true)
                }
            }
            assertEquals(15, openings.get())
            assertEquals(10, continuations.get())
            assertEquals(10, players.get())
            assertEquals(35, server.requestCount)
            DeviceEvidence.writeJson("msc-shared-comment-read.json", """{
                "trials":5,"perRequestDelayMs":150,"sharedCommentMs":$sharedTimes,"separateCommentMs":$separateTimes,
                "sharedOpeningsPerTrial":1,"separateOpeningsPerTrial":2,"commentContinuations":${continuations.get()},
                "watchOpenings":${openings.get()},"playerMetadataReads":${players.get()},"totalRequests":${server.requestCount}
            }""".trimIndent())
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdown()
        }
    }

    private companion object {
        const val videoId = "abcdefghijk"
        val details = """{"videoDetails":{"videoId":"$videoId","title":"Fixture title","author":"Author","channelId":"UCfixture","lengthSeconds":"30"}}"""
        val watch = """{
            "videoDetails":{"videoId":"$videoId","title":"Fixture title","author":"Author","channelId":"UCfixture"},
            "engagementPanelSectionListRenderer":{"panelIdentifier":"engagement-panel-comments-section",
                "content":{"continuationEndpoint":{"continuationCommand":{"token":"comments"}}}},
            "commentsEntryPointRenderer":{"commentCount":{"simpleText":"42"}}
        }"""
        val comments = """{"continuationContents":{"itemSectionContinuation":{"contents":[
            {"commentRenderer":{"commentId":"comment-one","contentText":{"simpleText":"Hello"}}}
        ]}}}"""
    }
}
