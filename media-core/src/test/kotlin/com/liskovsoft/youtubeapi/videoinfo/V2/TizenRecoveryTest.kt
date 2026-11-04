package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class TizenRecoveryTest {
    @Test fun compatibilityRequestKeepsTheRawTimestampAndPlaybackCapabilities() {
        val query = playerQuery(PlayerClient.TV_TIZEN, "abcdefghijk", "visitor", 20760)
        val body = requireNotNull(jsonObject(query.body))
        assertEquals("UKS9800", body.path("context", "client")!!.text("deviceModel"))
        assertEquals("Tizen", body.path("context", "client")!!.text("osName"))
        assertEquals("5.20260707", body.path("context", "client")!!.text("clientVersion"))
        assertEquals(playerUserAgent(PlayerClient.TV_TIZEN), query.headers["User-Agent"])
        assertTrue(query.body.contains("\"signatureTimestamp\":20760,"))
        assertTrue(query.body.contains("\"supportXhr\":false"))
        assertTrue(query.body.contains("\"isInlinePlaybackNoAd\":true"))
        assertFalse(playerQuery(PlayerClient.TV, "abcdefghijk", null, 20760).body.contains("Tizen"))
    }

    @Test fun challengedGuestsFallBackWithTheAccountAndRememberTheSuccessfulClient() {
        val queues = PlayerClientQueues(PlayerClient.VISIONOS)
        val attempts = mutableListOf<PlayerLoadAttempt>()
        val auth = mutableListOf<Pair<String, String?>>()
        val http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                val client = jsonObject(request.body)!!.path("context", "client")!!.text("clientName")!!
                auth += client to request.headers["Authorization"]
                return HttpReply(200, if (request.headers["User-Agent"] == playerUserAgent(PlayerClient.TV_TIZEN))
                    """{"playabilityStatus":{"status":"OK"},"streamingData":{"formats":[{"url":"https://media.test/video","itag":18}]}}"""
                else """{"playabilityStatus":{"status":"LOGIN_REQUIRED","reason":"Sign in to confirm you're not a bot"}}""")
            }
        }
        fun load(id: String) = loadPlayer(id, RequestContext(accessToken = "fixture-token"), http,
            script = PlayerScript("https://www.youtube.com/s/player/test/base.js", "signatureTimestamp:20760"),
            queues = queues, onAttempt = attempts::add)
        assertTrue(load("abcdefghijk").ok)
        assertEquals(listOf(PlayerClient.VISIONOS, PlayerClient.TV_TIZEN), attempts.map { it.client })
        assertEquals(PlayerClient.TV_TIZEN, attempts.last().client)
        assertTrue(attempts.last().authenticated)
        assertTrue(auth.dropLast(1).all { it.second == null })
        assertEquals("Bearer fixture-token", auth.last().second)
        attempts.clear(); auth.clear()
        assertTrue(load("bcdefghijkl").ok)
        assertEquals(listOf(PlayerClient.TV_TIZEN), attempts.map { it.client })
    }

    @Test fun mediaRejectionDeferralAndExclusionStillControlTheAccountRoute() {
        val context = RequestContext(accessToken = "fixture-token")
        val seen = mutableListOf<PlayerClient>()
        val http = object : HttpClient { override fun execute(request: HttpRequest) = HttpReply(200, "{}") }
        loadPlayer("abcdefghijk", context, http, deferred = setOf(PlayerClient.TV_TIZEN), onAttempt = { seen += it.client })
        assertEquals(PlayerClient.TV_TIZEN, seen.last())
        assertEquals(seen.toSet().size, seen.size)
        seen.clear()
        loadPlayer("abcdefghijk", context, http, excluded = setOf(PlayerClient.TV_TIZEN), onAttempt = { seen += it.client })
        assertFalse(PlayerClient.TV_TIZEN in seen)
    }

    @Test fun anonymousAndLiveQueuesDoNotPayForTheUnverifiedAccountRoute() {
        for (signIn in SignIn.values()) {
            assertFalse(PlayerClient.TV_TIZEN in PlayerClientQueues.defaultOrder(ClientQueueKey(signIn, PlaybackForm.LIVE)))
        }
        assertFalse(PlayerClient.TV_TIZEN in PlayerClientQueues.defaultOrder(ClientQueueKey(SignIn.SIGNED_OUT, PlaybackForm.VOD)))
    }

    @Test fun transportFailuresDoNotPromoteTheAccountRouteAndBotChecksRespectDeferral() {
        val key = ClientQueueKey(SignIn.SIGNED_IN, PlaybackForm.VOD)
        for (bot in listOf(false, true)) {
            val seen = mutableListOf<PlayerClient>()
            val walker = PlayerAttempt(PlayerClientQueues())
            walker.run(SignIn.SIGNED_IN, deferred = if (bot) setOf(PlayerClient.TV_TIZEN) else emptySet()) {
                seen += it
                PlayerAnswer(true, if (bot) "LOGIN_REQUIRED" else null, if (bot) "not a bot" else null,
                    false, false, false, false)
            }
            val expected = PlayerClientQueues.defaultOrder(key)
            assertEquals(if (bot) expected.filter { it != PlayerClient.TV_TIZEN } + PlayerClient.TV_TIZEN else expected, seen)
        }
    }

    @Test fun brandAccountSelectionPreservesPlaybackSafetyFields() {
        val query = contextualQuery(playerQuery(PlayerClient.TV_TIZEN, "abcdefghijk", "visitor", 20760),
            RequestContext(accessToken = "fixture-token", onBehalfOfUser = "channel"))
        val user = jsonObject(query.body)!!.path("context", "user")!!
        assertEquals("channel", user.text("onBehalfOfUser"))
        assertTrue(query.body.contains("\"enableSafetyMode\":false"))
        assertTrue(query.body.contains("\"lockedSafetyMode\":false"))
    }
}
