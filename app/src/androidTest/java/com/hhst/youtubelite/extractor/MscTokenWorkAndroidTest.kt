package com.hhst.youtubelite.extractor

import android.os.SystemClock
import com.hhst.youtubelite.core.DeviceEvidence
import com.liskovsoft.youtubeapi.videoinfo.V2.HttpClient
import com.liskovsoft.youtubeapi.videoinfo.V2.HttpReply
import com.liskovsoft.youtubeapi.videoinfo.V2.HttpRequest
import com.liskovsoft.youtubeapi.videoinfo.V2.PlaybackSession
import com.liskovsoft.youtubeapi.videoinfo.V2.PlayerClient
import com.liskovsoft.youtubeapi.videoinfo.V2.PlayerToken
import com.liskovsoft.youtubeapi.videoinfo.V2.PlayerTokenProvider
import com.liskovsoft.youtubeapi.videoinfo.V2.PlayerTokenRequest
import com.liskovsoft.youtubeapi.videoinfo.V2.TokenPurpose
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real MSC execution with synthetic HTTP and controlled mint work, not a public-network benchmark. */
class MscTokenWorkAndroidTest {
    @Test fun tokenWorkOnlyRunsForAcceptedMediaAndUsableCaptions() {
        val rows = JSONArray()
        val media = """"streamingData":{"formats":[{"url":"https://media.invalid/video"}]}"""
        val captions = """"captions":{"playerCaptionsTracklistRenderer":{"captionTracks":[{"baseUrl":"https://captions.invalid/track","languageCode":"en"}]}}"""
        val scenarios = listOf(
            Scenario("no_captions", "{$media}", true, listOf(TokenPurpose.PLAYER, TokenPurpose.MEDIA)),
            Scenario("unplayable", """{"playabilityStatus":{"status":"UNPLAYABLE","reason":"Fixture unavailable"}}""",
                false, listOf(TokenPurpose.PLAYER)),
            Scenario("usable_captions", "{$media,$captions}", true, TokenPurpose.values().toList())
        )
        for (scenario in scenarios) {
            val elapsed = mutableListOf<Long>()
            repeat(5) { sample ->
                val purposes = mutableListOf<TokenPurpose>()
                var requests = 0
                val tokens = object : PlayerTokenProvider {
                    override fun token(request: PlayerTokenRequest): PlayerToken {
                        assertEquals(PlayerClient.WEB_SAFARI, request.client)
                        assertEquals("tokenWork01", request.videoId)
                        purposes += request.purpose
                        SystemClock.sleep(TOKEN_DELAY_MS)
                        return PlayerToken(request.purpose.name, SystemClock.elapsedRealtime() + 60_000)
                    }
                }
                val http = object : HttpClient {
                    override fun execute(request: HttpRequest): HttpReply {
                        requests++
                        assertEquals("POST", request.method)
                        assertTrue(request.url.contains("/youtubei/v1/player"))
                        return HttpReply(200, scenario.body)
                    }
                }
                val session = PlaybackSession(http, tokens = tokens, now = SystemClock::elapsedRealtime)
                val started = SystemClock.elapsedRealtime()
                val result = session.load("tokenWork01", includeMuxedFloor = false,
                    excluded = PlayerClient.values().toSet() - PlayerClient.WEB_SAFARI)
                val elapsedMs = SystemClock.elapsedRealtime() - started
                elapsed += elapsedMs
                assertEquals(scenario.accepted, result.ok)
                assertEquals(scenario.purposes, purposes)
                assertEquals(1, requests)
                if (scenario.accepted) {
                    val answer = result.value!!.answer!!
                    assertTrue(answer.muxedFormats.single().url!!.contains("pot=MEDIA"))
                    assertNotNull(answer.expiresAtMs)
                    if (scenario.name == "usable_captions")
                        assertTrue(answer.captions.single().url!!.contains("pot=SUBTITLE"))
                } else assertEquals("playability_unplayable", result.error?.code)
                rows.put(JSONObject().put("scenario", scenario.name).put("sample", sample)
                    .put("accepted", result.ok).put("requests", requests)
                    .put("purposes", JSONArray(purposes.map { it.name }))
                    .put("injectedMintMs", purposes.size * TOKEN_DELAY_MS).put("elapsedMs", elapsedMs))
            }
            rows.put(JSONObject().put("scenario", scenario.name).put("samples", elapsed.size)
                .put("medianElapsedMs", elapsed.sorted()[elapsed.size / 2])
                .put("stagesPerLoad", scenario.purposes.size))
        }
        DeviceEvidence.writeJson("msc-token-work.json", JSONObject()
            .put("scope", "Android MSC with synthetic HTTP and fixed-delay tokens; not public-network timing")
            .put("delayPerMintMs", TOKEN_DELAY_MS).put("samplesPerScenario", 5).put("results", rows).toString())
    }

    private data class Scenario(val name: String, val body: String, val accepted: Boolean, val purposes: List<TokenPurpose>)

    private companion object { const val TOKEN_DELAY_MS = 80L }
}
