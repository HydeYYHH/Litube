package com.hhst.youtubelite.extractor

import com.google.gson.JsonObject
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestContext
import java.io.IOException
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackIdentityTest {
    private val id = "abcdefghijk"
    private val solver = object : SignatureSolver {
        override fun signatureTimestamp(playerUrl: String, context: ExtractionContext) = 1
        override fun playerScript(playerUrl: String, context: ExtractionContext) = "var config = {signatureTimestamp:1};"
        override fun solve(playerUrl: String, signatures: Set<String>, throttles: Set<String>, context: ExtractionContext) =
            SignatureSolver.Solutions(emptyMap(), emptyMap())
    }
    private val minted = mutableListOf<PoTokenMinter.TokenRequest>()
    private val tokens = object : PoTokenMinter {
        override fun mint(request: PoTokenMinter.TokenRequest, context: ExtractionContext): PoTokenMinter.Token {
            minted += request
            return PoTokenMinter.Token("token-${request.purpose}", Long.MAX_VALUE)
        }
        override fun invalidate(scope: String) = Unit
    }

    @Test fun playbackWithoutAHomeVisitAdoptsTheCapturedAnonymousIdentity() {
        val adopted = mutableListOf<Pair<String?, Long>>()
        val playback = MscPlayback(solver, tokens, { RequestContext(generation = 7) },
            { visitor, generation -> adopted += visitor to generation })
        val request = playback.requestContext(context("page-visitor"))
        assertEquals("page-visitor", request.visitor)
        assertEquals(listOf("page-visitor" to 7L), adopted)
    }

    @Test fun anExistingIdentityBindsBothThePlayerRequestAndMediaToken() {
        val adopted = mutableListOf<String?>()
        val playback = MscPlayback(solver, tokens, { RequestContext(visitor = "persistent-visitor") },
            { visitor, _ -> adopted += visitor })
        val resolved = playback.resolve(id, context("page-visitor"), null, false,
            StreamClient.entries.filter { it != StreamClient.WEB }.toSet())
        assertTrue(adopted.isEmpty())
        assertEquals(listOf("persistent-visitor"), minted.filter { it.binding == PoTokenMinter.Binding.VISITOR }.map { it.identifier }.distinct())
        assertTrue(resolved.stream.formats.any { it.url.contains("pot=token-GVS") })
        assertTrue(resolved.stream.formats.all { it.requestPlan?.client == StreamClient.WEB })
    }

    @Test fun anAccountSessionCannotBecomeTheDeviceVisitor() {
        val adopted = mutableListOf<String?>()
        val playback = MscPlayback(solver, tokens, { RequestContext(accessToken = "test-token", generation = 9) },
            { visitor, _ -> adopted += visitor })
        playback.requestContext(context("account-visitor"))
        assertTrue(adopted.isEmpty())
    }

    @Test fun aBrowserAccountCannotInitializeTheAnonymousProfile() {
        val adopted = mutableListOf<String?>()
        val playback = MscPlayback(solver, tokens, { RequestContext() }, { visitor, _ -> adopted += visitor })
        playback.requestContext(context("account-visitor", WebAccount.AUTHENTICATED))
        assertTrue(adopted.isEmpty())
    }

    private fun context(visitor: String, account: WebAccount = WebAccount.ANONYMOUS): ExtractionContext {
        val session = YoutubeWebSession("session", account, 0, null, null, null, visitor,
            "test-agent", 0, "https://www.youtube.com/s/player/test/base.js", "test", JsonObject()) { "" }
        val transport = object : ExtractionTransport {
            override fun get(context: ExtractionContext, url: String, headers: Map<String, List<String>>) =
                throw IOException("Unexpected GET")
            override fun post(context: ExtractionContext, url: String, headers: Map<String, List<String>>, body: ByteArray): PageResponse {
                assertEquals(listOf("persistent-visitor"), headers["X-Goog-Visitor-Id"])
                return PageResponse(200, emptyMap(), """{
                  "playabilityStatus":{"status":"OK"},"videoDetails":{"videoId":"$id","title":"Film","lengthSeconds":"10"},
                  "streamingData":{"formats":[{"itag":18,"mimeType":"video/mp4; codecs=\"avc1.42001E, mp4a.40.2\"",
                    "width":640,"height":360,"bitrate":1000000,"url":"https://media.example.test/movie.mp4"}]}
                }""", url)
            }
        }
        return ExtractionContext(session, transport, false, false, true, "",
            System.nanoTime() + TimeUnit.SECONDS.toNanos(30), { true }, object : EngineDiagnostics {
                override fun event(stage: String, profile: String, detail: String, elapsedMillis: Long, httpStatus: Int) = Unit
            })
    }
}
