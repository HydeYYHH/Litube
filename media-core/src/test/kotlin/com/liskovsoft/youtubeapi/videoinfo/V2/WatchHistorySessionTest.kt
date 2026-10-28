package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class WatchHistorySessionTest {
    private val tracking = PlayerTracking("https://www.youtube.com/api/stats/playback?ei=event", "https://www.youtube.com/api/stats/watchtime?of=keep")

    private fun captureHttp(requests: MutableList<HttpRequest>) = object : HttpClient {
        override fun execute(request: HttpRequest): HttpReply {
            requests += request
            return HttpReply(204, "")
        }
    }

    private fun param(url: String, name: String): String? =
        Regex("[?&]$name=([^&]*)").find(url)?.groupValues?.get(1)

    @Test fun cookieSessionsSignBothTrackingRequests() {
        val requests = mutableListOf<HttpRequest>()
        val http = captureHttp(requests)
        val history = WatchHistory(http)
        val context = RequestContext(webSession = WebSession("SAPISID=secret", 2))
        // The first update creates the playback record only: a watch-time report must
        // span a real interval, so it goes out with the next update.
        assertTrue(history.update("aqz-KE-bpKQ", 1000, 100_000, tracking, context).ok)
        assertEquals(1, requests.size)
        assertTrue(history.update("aqz-KE-bpKQ", 11_000, 100_000, tracking, context).ok)
        assertEquals(2, requests.size)
        requests.forEach {
            assertEquals("SAPISID=secret", it.headers["Cookie"])
            assertEquals("2", it.headers["X-Goog-AuthUser"])
            assertTrue(it.headers["Authorization"]!!.startsWith("SAPISIDHASH "))
        }
        assertTrue(requests[0].url.contains("/api/stats/playback"))
        assertTrue(requests[1].url.contains("/api/stats/watchtime"))
        // The interval chains from the session start to the current position.
        assertEquals("1.0", param(requests[1].url, "st"))
        assertEquals("11.0", param(requests[1].url, "et"))
    }

    @Test fun bearerWinsAndRejectedOriginsNeverReceiveCredentials() {
        val requests = mutableListOf<HttpRequest>()
        val http = captureHttp(requests)
        val context = RequestContext(accessToken = "token", webSession = WebSession("SAPISID=secret"))
        assertTrue(WatchHistory(http).update("aqz-KE-bpKQ", 1000, 100_000, tracking, context).ok)
        assertTrue(requests.all { it.headers == mapOf("Authorization" to "Bearer token") })
        requests.clear()
        for (base in listOf("https://www.youtube.com.evil.test/api/stats/playback", "https://user@www.youtube.com/api/stats/playback",
            "https://www.youtube.com:444/api/stats/playback", "http://www.youtube.com/api/stats/playback")) {
            // A fresh history consults the playback url for session creation.
            assertEquals(RequestFailure.INVALID, WatchHistory(http).update("aqz-KE-bpKQ", 1000, 100_000,
                tracking.copy(playbackUrl = base), context).error!!.kind)
        }
        assertTrue(requests.isEmpty())
    }

    @Test fun tokenRefreshKeepsTheSessionAndTheLedger() {
        val requests = mutableListOf<HttpRequest>()
        val http = captureHttp(requests)
        val history = WatchHistory(http)
        val first = RequestContext(accessToken = "token-a", accountId = "id1")
        assertTrue(history.update("aqz-KE-bpKQ", 1000, 100_000, tracking, first).ok)
        assertEquals(1, requests.size)
        // The token rotated underneath the same account: the session continues.
        val refreshed = RequestContext(accessToken = "token-b", accountId = "id1")
        assertTrue(history.update("aqz-KE-bpKQ", 11_000, 100_000, tracking, refreshed).ok)
        assertEquals(2, requests.size)
        assertTrue(requests[1].url.contains("/api/stats/watchtime"))
        assertEquals("1.0", param(requests[1].url, "st"))
        // Same cpn across the refresh: one playback session.
        assertEquals(param(requests[0].url, "cpn"), param(requests[1].url, "cpn"))
    }

    @Test fun eachVideoSessionGetsItsOwnCpn() {
        val requests = mutableListOf<HttpRequest>()
        val http = captureHttp(requests)
        val history = WatchHistory(http)
        val context = RequestContext(accessToken = "token")
        assertTrue(history.update("aqz-KE-bpKQ", 1000, 100_000, tracking, context).ok)
        assertTrue(history.update("dQw4w9WgXcQ", 2000, 20000, tracking, context).ok)
        assertEquals(2, requests.size)
        val cpnA = param(requests[0].url, "cpn")
        val cpnB = param(requests[1].url, "cpn")
        assertNotNull(cpnA)
        assertNotNull(cpnB)
        assertNotEquals(cpnA, cpnB)
    }

    @Test fun pausedTicksAndBackwardSeeksSendNoWatchTime() {
        val requests = mutableListOf<HttpRequest>()
        val http = captureHttp(requests)
        val history = WatchHistory(http)
        val context = RequestContext(accessToken = "token")
        assertTrue(history.update("aqz-KE-bpKQ", 1000, 100_000, tracking, context).ok)
        assertEquals(1, requests.size)
        // Paused: the same position twice carries no interval.
        assertTrue(history.update("aqz-KE-bpKQ", 1000, 100_000, tracking, context).ok)
        assertEquals(1, requests.size)
        assertTrue(history.update("aqz-KE-bpKQ", 11_000, 100_000, tracking, context).ok)
        assertEquals(2, requests.size)
        // Backward seek: the range resets silently; the next forward report chains
        // from the seek target.
        assertTrue(history.update("aqz-KE-bpKQ", 5_000, 100_000, tracking, context).ok)
        assertEquals(2, requests.size)
        assertTrue(history.update("aqz-KE-bpKQ", 8_000, 100_000, tracking, context).ok)
        assertEquals(3, requests.size)
        assertEquals("5.0", param(requests[2].url, "st"))
        assertEquals("8.0", param(requests[2].url, "et"))
    }

    @Test fun watchtimeUrlIsValidatedBeforeSending() {
        val requests = mutableListOf<HttpRequest>()
        val http = captureHttp(requests)
        val history = WatchHistory(http)
        val context = RequestContext(accessToken = "token")
        val broken = tracking.copy(watchTimeUrl = "https://www.youtube.com.evil.test/api/stats/watchtime")
        assertTrue(history.update("aqz-KE-bpKQ", 1000, 100_000, broken, context).ok)
        assertEquals(1, requests.size)
        assertEquals(RequestFailure.INVALID, history.update("aqz-KE-bpKQ", 11_000, 100_000, broken, context).error!!.kind)
        assertEquals(1, requests.size)
    }
}
