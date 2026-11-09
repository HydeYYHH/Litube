package com.hhst.youtubelite.data.media

import com.liskovsoft.youtubeapi.videoinfo.V2.HttpClient
import com.liskovsoft.youtubeapi.videoinfo.V2.ReadResult
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestContext
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestError
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestFailure
import com.liskovsoft.youtubeapi.videoinfo.V2.WatchPage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedWatchReadsTest {
    private fun page(id: String) = WatchPage(id, "t", "d", "a", "c",
        emptyList(), emptyList(), null, null, null, null)

    private fun ok(id: String) = ReadResult(page(id))
    private fun failed() = ReadResult<WatchPage>(error = RequestError(RequestFailure.TRANSPORT, "offline"))

    @Test fun identicalConcurrentReadsShareOneNetworkResponse() {
        val reads = SharedWatchReads()
        var calls = 0
        val started = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val owner = pool.submit<String> {
                reads.read("video", RequestContext(visitor = "visitor-1"), FakeHttp, fresh = false) { _, _, _ ->
                    calls++
                    started.countDown()
                    Thread.sleep(150)
                    ok("result-1")
                }.value!!.videoId!!
            }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            val joiner = pool.submit<String> {
                reads.read("video", RequestContext(visitor = "visitor-1"), FakeHttp, fresh = true) { _, _, _ ->
                    calls++; ok("second-call")
                }.value!!.videoId!!
            }
            assertEquals("result-1", owner.get(3, TimeUnit.SECONDS))
            assertEquals("result-1", joiner.get(3, TimeUnit.SECONDS))
            assertEquals(1, calls)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test fun differentIdentitiesNeverShare() {
        val reads = SharedWatchReads()
        var calls = 0
        fun read(visitor: String): String = reads.read("video", RequestContext(visitor = visitor), FakeHttp, fresh = false) { _, _, _ ->
            calls++
            ok("r-$visitor")
        }.value!!.videoId!!
        assertEquals("r-a", read("a"))
        assertEquals("r-b", read("b"))
        assertEquals(2, calls)
    }

    @Test fun freshCallersNeverReuseACompletedResponse() {
        val reads = SharedWatchReads()
        var calls = 0
        fun read(fresh: Boolean): String = reads.read("video", RequestContext(visitor = "v"), FakeHttp, fresh) { _, _, _ ->
            calls++
            ok("r")
        }.value!!.videoId!!
        assertEquals("r", read(fresh = false))
        assertEquals(1, calls)
        assertEquals("r", read(fresh = true))
        assertEquals(2, calls)
        assertEquals("r", read(fresh = true))
        assertEquals(3, calls)
        assertEquals("r", read(fresh = false))
        assertEquals(3, calls)
    }

    @Test fun equivalentOauthRefreshesShareButProfilesVisitorsCookiesAndLocalesDoNot() {
        val reads = SharedWatchReads()
        var calls = 0
        val base = RequestContext(accountId = "account", accessToken = "first", visitor = "visitor", generation = 1)
        fun read(context: RequestContext) = reads.read("video", context, FakeHttp, false) { _, _, _ ->
            calls++
            ok("r-$calls")
        }.value!!.videoId
        assertEquals("r-1", read(base))
        assertEquals("r-1", read(base.copy(accessToken = "refreshed")))
        assertEquals("r-2", read(base.anonymous()))
        assertEquals("r-3", read(base.copy(visitor = "other")))
        assertEquals("r-4", read(base.copy(language = "zh")))
        assertEquals("r-5", read(base.copy(region = "CN")))
        assertEquals("r-6", read(base.copy(generation = 2)))
        val web = base.copy(accessToken = null,
            webSession = com.liskovsoft.youtubeapi.videoinfo.V2.WebSession("SAPISID=one", 0))
        assertEquals("r-7", read(web))
        assertEquals("r-7", read(web.copy(webSession = com.liskovsoft.youtubeapi.videoinfo.V2.WebSession("SAPISID=one", 0))))
        assertEquals("r-8", read(web.copy(webSession = com.liskovsoft.youtubeapi.videoinfo.V2.WebSession("SAPISID=two", 0))))
        assertEquals("r-9", read(web.copy(webSession = com.liskovsoft.youtubeapi.videoinfo.V2.WebSession("SAPISID=one", 1))))
        assertEquals("r-10", read(base.anonymous().copy(visitorCookie = "VISITOR_INFO1_LIVE=one")))
        assertEquals("r-11", read(base.anonymous().copy(visitorCookie = "VISITOR_INFO1_LIVE=two")))
        assertEquals(11, calls)
    }

    @Test fun aFreshResponseReplacesThePreviouslyCachedPage() {
        val reads = SharedWatchReads()
        var calls = 0
        fun read(fresh: Boolean) = reads.read("video", RequestContext(visitor = "v"), FakeHttp, fresh) { _, _, _ ->
            ok("result-${++calls}")
        }.value!!.videoId
        assertEquals("result-1", read(false))
        assertEquals("result-2", read(true))
        assertEquals("result-2", read(false))
        assertEquals(2, calls)
    }

    @Test fun aFailedResponseIsNeverSharedAndTheNextCallerReadsAgain() {
        val reads = SharedWatchReads()
        var calls = 0
        try {
            reads.read("video", RequestContext(visitor = "v"), FakeHttp, fresh = false) { _, _, _ ->
                calls++
                failed()
            }
            throw AssertionError("expected MediaException")
        } catch (expected: MediaException) {
        }
        assertEquals("r", reads.read("video", RequestContext(visitor = "v"), FakeHttp, fresh = false) { _, _, _ ->
            calls++
            ok("r")
        }.value!!.videoId!!)
        assertEquals(2, calls)
    }

    @Test fun expiredResponsesAreReRead() {
        var now = 0L
        val reads = SharedWatchReads(clock = { now })
        var calls = 0
        fun read(): String = reads.read("video", RequestContext(visitor = "v"), FakeHttp, fresh = false) { _, _, _ ->
            calls++
            ok("r-$calls")
        }.value!!.videoId!!
        assertEquals("r-1", read())
        now += 60_000
        assertEquals("r-2", read())
        assertEquals(2, calls)
    }

    private object FakeHttp : HttpClient {
        override fun execute(request: com.liskovsoft.youtubeapi.videoinfo.V2.HttpRequest) =
            error("network must run through the injected load block")
    }
}
