package com.hhst.youtubelite.data.media

import com.hhst.youtubelite.post.domain.PostIdentity
import com.hhst.youtubelite.watch.domain.VideoRating
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestContext
import com.liskovsoft.youtubeapi.videoinfo.V2.WebSession
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaDesiredStateTimeoutOwnershipTest {
    @Test(timeout = 15_000)
    fun aTimedOutReadCannotReplaceTheNextReadsFreshWriteCapability() = runBlocking {
        val identity = PostIdentity("UCauthor", "Ugkx_post")
        val context = RequestContext("account", "oauth", generation = 7)
        val web = context.copy(accessToken = null, webSession = WebSession("SAPISID=fixture; SID=fixture"))
        val reads = AtomicInteger()
        val webLookups = AtomicInteger()
        val writes = CopyOnWriteArrayList<String>()
        val firstReadThread = AtomicReference<Thread?>()
        val releaseFirst = CountDownLatch(1)
        val firstWorkerFinished = CountDownLatch(1)
        val client = MediaClient(OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = Buffer().also { request.body?.writeTo(it) }.readUtf8()
            val reply: ResponseBody = if (request.url.encodedPath.startsWith("/post/")) {
                when (reads.incrementAndGet()) {
                    1 -> post(identity.postId, "initial").toResponseBody()
                    2 -> {
                        // Return headers first; blocking inside the interceptor would
                        // let OkHttp reject the cancelled call before MediaClient sees it.
                        delayedBody(post(identity.postId, "timed-out"), firstReadThread, releaseFirst)
                    }
                    else -> post(identity.postId, "current").toResponseBody()
                }
            } else {
                writes += body
                (if (writes.size == 1) "{}" else """{"status":"STATUS_SUCCEEDED"}""").toResponseBody()
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(reply).build()
        }.build()) {
            // Before the fix this generation check followed the late observer's assignment;
            // cancellation now prevents reaching it, so the wait is bounded.
            if (Thread.currentThread() === firstReadThread.get() && releaseFirst.count == 0L)
                firstWorkerFinished.countDown()
            context
        }
        val repository = MediaPostRepository(client) {
            // Prime, first write, timed-out read, valid read, then retry preparation.
            if (webLookups.incrementAndGet() == 5) {
                releaseFirst.countDown()
                firstWorkerFinished.await(500, TimeUnit.MILLISECONDS)
            }
            web
        }
        try {
            repository.post(identity)
            assertEquals(VideoRating.Like, repository.rate(identity, VideoRating.Like, VideoRating.None).rating)
            assertEquals(3, reads.get())
            assertEquals(2, writes.size)
            assertTrue(writes.first().contains("initial-like"))
            assertTrue(writes.last().contains("current-like"))
            assertFalse(writes.last().contains("timed-out-like"))
        } finally {
            releaseFirst.countDown()
        }
    }

    private fun delayedBody(body: String, readingThread: AtomicReference<Thread?>, release: CountDownLatch): ResponseBody {
        val payload = Buffer().writeUtf8(body)
        val source = object : Source {
            private var started = false
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (!started) {
                    started = true
                    readingThread.set(Thread.currentThread())
                    // Ignore interruption/close to model a body source returning after timeout.
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                    while (release.count != 0L && System.nanoTime() < deadline) {
                        try { release.await(100, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { }
                    }
                }
                return payload.read(sink, byteCount)
            }
            override fun timeout() = Timeout.NONE
            override fun close() = Unit
        }.buffer()
        return object : ResponseBody() {
            override fun contentType() = null
            override fun contentLength() = -1L
            override fun source(): BufferedSource = source
        }
    }

    private fun post(id: String, token: String) =
        """<script>var ytInitialData = {"backstagePostRenderer":{"postId":"$id",
          "voteStatus":"INDIFFERENT","voteCount":{"simpleText":"10"},"actionButtons":{"commentActionButtonsRenderer":{
            "likeButton":{"toggleButtonRenderer":{"defaultServiceEndpoint":{"performCommentActionEndpoint":{"action":"$token-like"}},
              "toggledServiceEndpoint":{"performCommentActionEndpoint":{"action":"$token-unlike"}}}},
            "dislikeButton":{"toggleButtonRenderer":{"defaultServiceEndpoint":{"performCommentActionEndpoint":{"action":"$token-dislike"}},
              "toggledServiceEndpoint":{"performCommentActionEndpoint":{"action":"$token-undislike"}}}}}}}};</script>"""
}
