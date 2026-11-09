package com.hhst.youtubelite.extractor

import com.hhst.youtubelite.core.DeviceEvidence
import com.hhst.youtubelite.diagnostics.DiagnosticContext
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestContext
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MscResultCacheAndroidTest {
    @Test fun appBridgeReplacesFreshUrlsAndSeparatesNetworkAndBrowserSessions() {
        val calls = AtomicInteger()
        val playback = MscPlayback(GlobalContext.get().get<SignatureSolver>(),
            GlobalContext.get().get<PoTokenMinter>(), identity = { RequestContext(visitor = "cache-visitor") })
        fun context(key: String = "browser1", network: Long = 0L): ExtractionContext {
            val snapshot = YoutubeWebSession(key, WebAccount.ANONYMOUS, 0, null, null, null,
                "cache-visitor", "fixture-UA", network, "https://www.youtube.com/s/player/fixture/base.js",
                "fixture", JsonObject(emptyMap())) { "" }
            return ExtractionContext(snapshot, object : ExtractionTransport {
                override fun get(context: ExtractionContext, url: String, headers: Map<String, List<String>>): PageResponse =
                    error("Unexpected fixture GET")
                override fun post(context: ExtractionContext, url: String, headers: Map<String, List<String>>, body: ByteArray): PageResponse {
                    val route = calls.incrementAndGet()
                    return PageResponse(200, emptyMap(), """{"playabilityStatus":{"status":"OK"},
                        "videoDetails":{"videoId":"cacheScope1","title":"Cache fixture","lengthSeconds":"20"},
                        "streamingData":{"formats":[{"itag":18,"height":360,"width":640,"bitrate":300000,
                        "mimeType":"video/mp4; codecs=\"avc1.42001e, mp4a.40.2\"","url":"https://media.invalid/route$route"}]}}
                    """.trimIndent(), url)
                }
            }, false, false, true, "", System.nanoTime() + TimeUnit.SECONDS.toNanos(15), { true }, ExtractionDiagnostics())
        }
        fun resolve(context: ExtractionContext, fresh: Boolean = false): String =
            playback.resolve("cacheScope1", context, null, fresh, emptySet()).stream.formats.first { it.itag == 18 }.url
        val old = resolve(context())
        assertEquals(old, resolve(context())); assertEquals(1, calls.get())
        val refreshed = resolve(context(), fresh = true)
        assertNotEquals(old, refreshed)
        assertEquals(refreshed, resolve(context())); assertEquals(2, calls.get())
        val moved = resolve(context(network = 1))
        assertNotEquals(refreshed, moved)
        assertEquals(moved, resolve(context(network = 1))); assertEquals(3, calls.get())
        val browserChanged = resolve(context(key = "browser2", network = 1))
        assertNotEquals(moved, browserChanged)
        assertEquals(browserChanged, resolve(context(key = "browser2", network = 1))); assertEquals(4, calls.get())
        DeviceEvidence.writeJson("msc-result-cache.json",
            """{"freshReplacedOldUrls":true,"networkIsolated":true,"browserIsolated":true,"requests":${calls.get()}}""")
    }
}
