package com.hhst.youtubelite.extractor

import kotlinx.serialization.json.JsonObject
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestContext
import java.io.IOException
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Test

class PlaybackIdentityTest {
    private val id = "abcdefghijk"

    @Test fun anUnsupportedTransportContinuesTheQueueBeforeReturningAStream() {
        val clients = mutableListOf<String>()
        val playback = MscPlayback(solver, tokens, { RequestContext(visitor = "persistent-visitor") })
        val task = context("persistent-visitor", respond = { _, body ->
            clients += Regex("\"clientName\"\\s*:\\s*\"([^\"]+)\"").find(body.toString(Charsets.UTF_8))!!.groupValues[1]
            if (clients.size == 1) """{"streamingData":{"serverAbrStreamingUrl":"https://media.example.test/sabr"},
                "playerConfig":{"mediaCommonConfig":{"mediaUstreamerRequestConfig":{"videoPlaybackUstreamerConfig":"config"}}}}"""
            else """{"streamingData":{"formats":[{"itag":18,"mimeType":"video/mp4","url":"https://media.example.test/muxed"}]}}"""
        })
        val resolved = playback.resolve(id, task, null, false, emptySet())
        assertEquals(listOf("VISIONOS", "WEB"), clients)
        assertTrue(resolved.stream.formats.any { it.url.contains("/muxed") })
    }

    @Test fun playbackDoesNotWaitForACatalogSupplement() {
        val urls = mutableListOf<String>()
        val playback = MscPlayback(solver, tokens, { RequestContext(visitor = "persistent-visitor") })
        val adaptive = """{"videoDetails":{"videoId":"$id"},"streamingData":{"adaptiveFormats":[
            {"itag":137,"mimeType":"video/mp4","url":"https://media.example.test/video","height":1080},
            {"itag":140,"mimeType":"audio/mp4","url":"https://media.example.test/audio"}]}}"""
        fun task(catalog: Boolean) = context("persistent-visitor", catalog = catalog, respond = { url, _ ->
            urls += url
            if (url.contains("reel_item_watch")) """{"playerResponse":{"videoDetails":{"videoId":"$id"},
                "streamingData":{"formats":[{"itag":18,"mimeType":"video/mp4","url":"https://media.example.test/muxed"}]}}}"""
            else adaptive
        })
        val fast = playback.resolve(id, task(false), null, false, emptySet())
        assertTrue(fast.stream.formats.isNotEmpty())
        assertEquals(1, urls.size)
        val catalog = playback.resolve(id, task(true), null, false, emptySet())
        assertEquals(1, urls.count { it.contains("reel_item_watch") })
        assertTrue(catalog.stream.formats.any { it.url.endsWith("/muxed") })
    }

    @Test fun deferredFallbackDoesNotReplayClientsThatAlreadyRefusedTheVideo() {
        val clients = mutableListOf<String>()
        val playback = MscPlayback(solver, tokens, { RequestContext(visitor = "persistent-visitor") })
        val task = context("persistent-visitor", respond = { _, body ->
            val client = Regex("\"clientName\"\\s*:\\s*\"([^\"]+)\"").find(body.toString(Charsets.UTF_8))!!.groupValues[1]
            clients += client
            if (client == "TVHTML5") """{"streamingData":{"formats":[{"itag":18,"mimeType":"video/mp4","url":"https://media.example.test/muxed"}]}}"""
            else """{"playabilityStatus":{"status":"UNPLAYABLE"}}"""
        })
        playback.resolve(id, task, null, false, setOf(StreamClient.TV))
        assertEquals("TVHTML5", clients.last())
        assertEquals(StreamClient.entries.size, clients.size)
        assertEquals(2, clients.count { it == "WEB" })
        assertEquals(1, clients.count { it == "VISIONOS" })
    }
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

    @Test fun resolutionFailureRetainsIdentityAndEveryClientOutcome() {
        val playback = MscPlayback(solver, tokens, { RequestContext(visitor = "persistent-visitor") })
        val context = context("page-visitor", response = """{"playabilityStatus":{"status":"LOGIN_REQUIRED","reason":"Sign in to confirm you're not a bot"}}""")
        val failure = runCatching { playback.resolve(id, context, null, false, emptySet()) }
            .exceptionOrNull() as? PlaybackResolutionException
        assertNotNull(failure)
        assertEquals("playability_bot_check", failure?.error?.code)
        assertEquals("ANONYMOUS", failure?.diagnostics?.accountState)
        assertEquals("test", failure?.diagnostics?.configurationSource)
        assertEquals(0L, failure?.diagnostics?.networkGeneration)
        val attempts = requireNotNull(failure?.diagnostics).attempts
        assertEquals(StreamClient.entries.size, attempts.size)
        assertTrue(attempts.all { it.status == "LOGIN_REQUIRED" && it.error?.code == "playability_bot_check" })
    }

    private fun context(visitor: String, account: WebAccount = WebAccount.ANONYMOUS, response: String? = null,
        catalog: Boolean = false, respond: ((String, ByteArray) -> String)? = null): ExtractionContext {
        val session = YoutubeWebSession("session", account, 0, null, null, null, visitor,
            "test-agent", 0, "https://www.youtube.com/s/player/test/base.js", "test", JsonObject(emptyMap())) { "" }
        val transport = object : ExtractionTransport {
            override fun get(context: ExtractionContext, url: String, headers: Map<String, List<String>>) =
                throw IOException("Unexpected GET")
            override fun post(context: ExtractionContext, url: String, headers: Map<String, List<String>>, body: ByteArray): PageResponse {
                assertEquals(listOf("persistent-visitor"), headers["X-Goog-Visitor-Id"])
                return PageResponse(200, emptyMap(), respond?.invoke(url, body) ?: response ?: """{
                  "playabilityStatus":{"status":"OK"},"videoDetails":{"videoId":"$id","title":"Film","lengthSeconds":"10"},
                  "streamingData":{"formats":[{"itag":18,"mimeType":"video/mp4; codecs=\"avc1.42001E, mp4a.40.2\"",
                    "width":640,"height":360,"bitrate":1000000,"url":"https://media.example.test/movie.mp4"}]}
                }""", url)
            }
        }
        return ExtractionContext(session, transport, false, catalog, true, "",
            System.nanoTime() + TimeUnit.SECONDS.toNanos(30), { true }, object : EngineDiagnostics {
                override fun event(stage: String, profile: String, detail: String, elapsedMillis: Long, httpStatus: Int) = Unit
            })
    }
}
