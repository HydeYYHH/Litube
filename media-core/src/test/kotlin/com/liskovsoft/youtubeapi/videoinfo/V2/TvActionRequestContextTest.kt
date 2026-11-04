package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class TvActionRequestContextTest {
    private class Capture : HttpClient {
        val requests = mutableListOf<HttpRequest>()
        override fun execute(request: HttpRequest): HttpReply {
            requests += request
            return HttpReply(200, """{"success":true}""")
        }
    }

    @Test fun oauthVideoAndSubscriptionWritesUseTheTvActionContext() {
        val http = Capture()
        val context = RequestContext(accountId = "account", accessToken = "fixture-token", language = "zh", region = "TW")
        assertTrue(voteVideo("abcdefghijk", VideoVote.LIKE, context, http).ok)
        assertTrue(subscribeChannel("UCabcdefghijk", true, context, http = http).ok)
        assertEquals(2, http.requests.size)
        assertTrue(http.requests[0].url.endsWith("/like/like"))
        assertTrue(http.requests[1].url.endsWith("/subscription/subscribe"))
        for (request in http.requests) {
            val root = requireNotNull(jsonObject(request.body))
            val client = requireNotNull(root.path("context", "client"))
            assertEquals("POST", request.method)
            assertEquals("TVHTML5", client.text("clientName"))
            assertEquals("7", request.headers["X-Youtube-Client-Name"])
            assertEquals(request.headers["X-Youtube-Client-Version"], client.text("clientVersion"))
            assertEquals("WATCH", client.text("clientScreen"))
            assertEquals(request.headers["User-Agent"], client.text("userAgent"))
            assertEquals("zh", client.text("acceptLanguage"))
            assertEquals("TW", client.text("acceptRegion"))
            assertEquals(false, root.path("context", "user")?.bool("enableSafetyMode"))
            assertEquals(false, root.path("context", "user")?.bool("lockedSafetyMode"))
            assertEquals(true, root.bool("racyCheckOk"))
            assertEquals(true, root.bool("contentCheckOk"))
            assertEquals("Bearer fixture-token", request.headers["Authorization"])
            assertNull(request.headers["Cookie"])
            assertNull(request.headers["X-Goog-PageId"])
        }
        assertEquals("abcdefghijk", jsonObject(http.requests[0].body)?.obj("target")?.text("videoId"))
        assertEquals("UCabcdefghijk", (jsonObject(http.requests[1].body)?.arr("channelIds")?.single() as? Json.Str)?.value)
    }

    @Test fun legacyTvActionsKeepTheSelectedAccountAndDeriveTheBodyAgentFromTheHeader() {
        val http = Capture()
        val query = PlayerQuery(YOUTUBE_API + "history/pause_watch_history", mapOf("user-agent" to "fixture-tv-agent"),
            """{"context":{"client":{"clientName":"TVHTML5","clientVersion":"fixture","hl":"fr","gl":"CA"},"user":{"onBehalfOfUser":"selected-page"},"request":{"opaque":"keep"}},"nop":"false"}""")
        assertTrue(requestAction(query, http).ok)
        val sent = http.requests.single()
        val root = requireNotNull(jsonObject(sent.body))
        val client = requireNotNull(root.path("context", "client"))
        assertEquals("WATCH", client.text("clientScreen"))
        assertEquals("fixture-tv-agent", client.text("userAgent"))
        assertEquals("fr", client.text("acceptLanguage"))
        assertEquals("CA", client.text("acceptRegion"))
        assertEquals("selected-page", root.path("context", "user")?.text("onBehalfOfUser"))
        assertEquals("keep", root.path("context", "request")?.text("opaque"))
        assertEquals("false", root.text("nop"))
        assertEquals(query.headers, sent.headers)
    }

    @Test fun explicitTvContextAndContentDecisionsArePreserved() {
        val http = Capture()
        val body = """{"context":{"client":{"clientName":"TVHTML5","clientScreen":"EMBED","userAgent":"body-agent","hl":"en","gl":"US","acceptLanguage":"fr-CA","acceptRegion":"CA"},"user":{"enableSafetyMode":true,"lockedSafetyMode":true,"onBehalfOfUser":"page"}},"racyCheckOk":false,"contentCheckOk":false,"target":{"videoId":"abcdefghijk"}}"""
        val query = PlayerQuery(YOUTUBE_API + "like/like", mapOf("User-Agent" to "header-agent"), body)
        assertTrue(requestAction(query, http).ok)
        assertEquals(jsonObject(body)?.encode(), jsonObject(http.requests.single().body)?.encode())
        assertEquals(query.headers, http.requests.single().headers)
    }

    @Test fun webActionsKeepTheirOriginalPayloadAndHeaders() {
        val http = Capture()
        val query = PlayerQuery(YOUTUBE_API + "like/like", mapOf("User-Agent" to "web-agent", "Authorization" to "fixture-cookie-auth"),
            """{ "context": { "client": { "clientName": "WEB", "hl": "zh", "gl": "TW" } }, "target": { "videoId": "abcdefghijk" } }""")
        assertTrue(requestAction(query, http).ok)
        assertEquals(query.body, http.requests.single().body)
        assertEquals(query.headers, http.requests.single().headers)
    }

    @Test fun readAndPlayerQueriesDoNotGainActionOnlyContext() {
        val http = Capture()
        val context = RequestContext(accountId = "account", accessToken = "fixture-token")
        val read = youtubeQuery(YOUTUBE_API + "browse", "\"browseId\":\"FEsubscriptions\"", context)
        val player = contextualQuery(playerQuery(PlayerClient.TV, "abcdefghijk", null), context)
        assertTrue(requestObject(read, http).ok)
        assertTrue(requestObject(player, http).ok)
        for (request in http.requests) {
            val root = requireNotNull(jsonObject(request.body))
            assertNull(root.path("context", "client")?.text("clientScreen"))
            assertNull(root.path("context", "client")?.text("acceptLanguage"))
            assertNull(root.path("context", "user"))
        }
    }
}
