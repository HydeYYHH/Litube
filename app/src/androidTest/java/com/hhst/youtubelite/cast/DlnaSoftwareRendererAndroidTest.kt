package com.hhst.youtubelite.cast

import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.util.UnstableApi
import androidx.test.platform.app.InstrumentationRegistry
import com.yinnho.upnpcast.DLNACast
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in interoperability test: the renderer must decode video, not merely acknowledge SOAP. */
@UnstableApi
class DlnaSoftwareRendererAndroidTest {
    @Test fun playsPausesSeeksAndResumesOnAnIndependentRenderer() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val description = arguments.getString("dlnaDescriptionUrl")
        val media = arguments.getString("dlnaMediaUrl")
        assumeTrue(arguments.getString("network") == "1" && description != null && media != null)
        val location = requireNotNull(DlnaDescription.location(requireNotNull(description)))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = RealDlnaEngine(context)
        val holdMs = arguments.getString("dlnaHoldMs")?.toLongOrNull()?.coerceIn(0, 15_000) ?: 0
        val device = DLNACast.Device(location.toString(), "Independent DLNA renderer", location.host, true)
        // An emulator cannot receive LAN connections directly. A desktop adb forward may expose
        // the real Android media proxy to Kodi without changing production networking behavior.
        val proxyEndpoint = arguments.getString("dlnaProxyEndpoint")?.toHttpUrl()
        val proxy = proxyEndpoint?.let {
            val length = requireNotNull(arguments.getString("dlnaMediaBytes")?.toLongOrNull())
            require(length > 0)
            LocalStreamProxy(context, OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).build(),
                port = arguments.getString("dlnaProxyListenPort")?.toIntOrNull() ?: 18080,
                advertisedHost = "127.0.0.1").apply {
                configure(null, "Litube DLNA Kodi test", null, requireNotNull(media), "video/mp4",
                    null, null, LocalStreamProxy.StreamRanges(-1, -1, length), null)
                start()
            }
        }
        val playbackUrl = if (proxy != null) proxy.proxyUrl("/stream/v?g=${proxy.currentConfigId()}").toHttpUrl().newBuilder()
            .host(requireNotNull(proxyEndpoint).host).port(proxyEndpoint.port).build().toString()
        else requireNotNull(media)

        suspend fun awaitStage(label: String, ready: suspend () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 20_000
            while (!ready()) {
                check(SystemClock.elapsedRealtime() < deadline) { "Timed out: $label" }
                delay(250)
            }
        }
        fun report(label: String, position: Pair<Long, Long>?) {
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("stream", "\nDLNA $label: position=${position?.first} duration=${position?.second} ms\n")
            })
        }
        try {
            assertTrue(engine.cast(device, playbackUrl, "Litube DLNA Kodi test", "video/mp4"))
            awaitStage("playing with advancing position") {
                engine.transportState() == DLNACast.PlaybackState.PLAYING &&
                    (engine.progress()?.first ?: 0) >= 2_000
            }
            val playing = requireNotNull(engine.progress())
            assertTrue("Use a video at least 25 seconds long", playing.second >= 25_000)
            report("PLAYING", playing)

            assertTrue(engine.pause())
            awaitStage("paused") { engine.transportState() == DLNACast.PlaybackState.PAUSED }
            val paused = requireNotNull(engine.progress())
            delay(2_000)
            val stillPaused = requireNotNull(engine.progress())
            assertTrue("Paused position advanced: $paused -> $stillPaused", abs(stillPaused.first - paused.first) <= 1_000)
            assertEquals(DLNACast.PlaybackState.PAUSED, engine.transportState())
            report("PAUSED (position stable for 2 seconds)", stillPaused)
            delay(holdMs)

            assertTrue(engine.seek(20_000))
            awaitStage("forward seek to 20 seconds") {
                abs((engine.progress()?.first ?: Long.MIN_VALUE) - 20_000) <= 1_000
            }
            assertEquals(DLNACast.PlaybackState.PAUSED, engine.transportState())
            report("SEEK forward to 20 seconds (paused)", engine.progress())
            delay(holdMs)

            assertTrue(engine.seek(4_000))
            awaitStage("backward seek to 4 seconds") {
                abs((engine.progress()?.first ?: Long.MIN_VALUE) - 4_000) <= 1_000
            }
            report("SEEK backward to 4 seconds", engine.progress())
            assertTrue(engine.play())
            awaitStage("resumed with advancing position") {
                engine.transportState() == DLNACast.PlaybackState.PLAYING &&
                    (engine.progress()?.first ?: 0) >= 6_000
            }
            report("RESUMED", engine.progress())
            assertTrue(engine.seek(12_000))
            awaitStage("seek while playing to 12 seconds") {
                val position = engine.progress()?.first ?: 0
                engine.transportState() == DLNACast.PlaybackState.PLAYING && position in 11_000..14_000
            }
            report("SEEK while playing to 12 seconds", engine.progress())
            delay(holdMs)
            assertTrue(engine.stop())
            awaitStage("stopped") { engine.transportState() == DLNACast.PlaybackState.STOPPED }
            report("STOPPED", engine.progress())
        } finally {
            runCatching { engine.stop() }
            proxy?.stop()
        }
    }
}
