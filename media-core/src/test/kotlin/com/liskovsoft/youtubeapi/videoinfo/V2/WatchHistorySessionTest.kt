package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class WatchHistorySessionTest {
    private val tracking = PlayerTracking("https://www.youtube.com/api/stats/playback?ei=event", "https://www.youtube.com/api/stats/watchtime?of=keep")

    @Test fun cookieSessionsSignBothTrackingRequests() {
        val requests = mutableListOf<HttpRequest>()
        val http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                requests += request
                return HttpReply(204, "")
            }
        }
        assertTrue(WatchHistory(http).update("aqz-KE-bpKQ", 1000, 10000, tracking,
            RequestContext(webSession = WebSession("SAPISID=secret", 2))).ok)
        assertEquals(2, requests.size)
        requests.forEach {
            assertEquals("SAPISID=secret", it.headers["Cookie"])
            assertEquals("2", it.headers["X-Goog-AuthUser"])
            assertTrue(it.headers["Authorization"]!!.startsWith("SAPISIDHASH "))
        }
    }

    @Test fun bearerWinsAndRejectedOriginsNeverReceiveCredentials() {
        val requests = mutableListOf<HttpRequest>()
        val http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                requests += request
                return HttpReply(204, "")
            }
        }
        val context = RequestContext(accessToken = "token", webSession = WebSession("SAPISID=secret"))
        assertTrue(WatchHistory(http).update("aqz-KE-bpKQ", 1000, 10000, tracking, context).ok)
        assertTrue(requests.all { it.headers == mapOf("Authorization" to "Bearer token") })
        requests.clear()
        for (base in listOf("https://www.youtube.com.evil.test/api/stats/playback", "https://user@www.youtube.com/api/stats/playback",
            "https://www.youtube.com:444/api/stats/playback", "http://www.youtube.com/api/stats/playback")) {
            assertEquals(RequestFailure.INVALID, WatchHistory(http).update("aqz-KE-bpKQ", 1000, 10000,
                tracking.copy(playbackUrl = base), context).error!!.kind)
        }
        assertTrue(requests.isEmpty())
    }
}
