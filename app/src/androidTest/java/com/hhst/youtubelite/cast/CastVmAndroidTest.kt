@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.hhst.youtubelite.cast

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.Player
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.cast.protocol.CastDeviceAuth
import com.hhst.youtubelite.cast.protocol.CastV2Session
import com.hhst.youtubelite.extractor.Format
import com.hhst.youtubelite.player.engine.CastSource
import fi.iki.elonen.NanoHTTPD
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.util.Date

class CastVmAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    /** End-to-end against [CastReceiverVmAndroidTest] on a second AVD; run both with `-e castVm 1`. */
    @Test fun castSessionPlaysPausesSeeksAndDisconnects() {
        assumeTrue("Run explicitly with -e castVm 1",
            InstrumentationRegistry.getArguments().getString("castVm") == "1")
        val video = CastVmFixture.bytes("cast-video.mp4")
        val audio = CastVmFixture.bytes("cast-audio.m4a")
        val upstream = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                val bytes = if (session.uri == "/v") video else audio
                val mime = if (session.uri == "/v") "video/mp4" else "audio/mp4"
                val range = session.headers["range"]?.removePrefix("bytes=")?.split('-')
                val start = range?.firstOrNull()?.toIntOrNull() ?: 0
                val end = (range?.getOrNull(1)?.toIntOrNull() ?: bytes.lastIndex).coerceAtMost(bytes.lastIndex)
                return newFixedLengthResponse(if (range == null) Response.Status.OK else Response.Status.PARTIAL_CONTENT,
                    mime, bytes.copyOfRange(start, end + 1).inputStream(), (end - start + 1).toLong()).apply {
                    addHeader("Accept-Ranges", "bytes")
                    if (range != null) addHeader("Content-Range", "bytes $start-$end/${bytes.size}")
                }
            }
        }
        upstream.start()
        val info = CastVmFixture.media()
        fun format(token: String, videoTrack: Boolean): Format {
            val track = info.getJSONObject(if (videoTrack) "v" else "a")
            return Format(url = "http://127.0.0.1:${upstream.listeningPort}/$token",
                width = if (videoTrack) 320 else 0, height = if (videoTrack) 180 else 0,
                bitrate = if (videoTrack) 600000 else 70000, fps = if (videoTrack) 24 else 0,
                codec = if (videoTrack) "avc1.42c00c" else "mp4a.40.2",
                mimeType = if (videoTrack) "video/mp4" else "audio/mp4",
                videoOnly = videoTrack, audioOnly = !videoTrack, initStart = 0,
                initEnd = track.getInt("initEnd"), indexStart = track.getInt("indexStart"),
                indexEnd = track.getInt("indexEnd"), sampleRate = if (videoTrack) -1 else 44100,
                audioChannels = if (videoTrack) -1 else 1)
        }
        val source = CastSource("vm-fixture", "VM H.264 + AAC", 30, format("v", true), format("a", false), "VM fixture")
        lateinit var controller: CastController
        var failed = false
        compose.runOnUiThread {
            controller = CastController(compose.activity.applicationContext, OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).build(), sessionFactory = { host, port, listener ->
                CastV2Session(host, port, CastV2Session.DEFAULT_MEDIA_RECEIVER_APP_ID, listener, CastVmFixture.authenticator())
            })
            controller.onSessionFailed = { failed = true }
            controller.onSessionStarted = { assertTrue(controller.startCasting(source, 0)) }
            controller.initialize()
            controller.setDiscoveryEnabled(true)
        }
        fun await(timeout: Long, condition: () -> Boolean) {
            compose.waitUntil(timeout) {
                var ready = false
                compose.runOnUiThread { ready = condition() }
                ready
            }
        }
        try {
            await(30000) { controller.state.value.devices.any { it.name == "Cast VM" } }
            compose.runOnUiThread { controller.selectDevice(controller.state.value.devices.first { it.name == "Cast VM" }.id) }
            await(45000) { failed || controller.remotePlayer()?.playbackState == Player.STATE_READY }
            assertFalse("Authentication/launch/load failed", failed)
            await(15000) { (controller.remotePlayer()?.currentPosition ?: 0) > 1500 }
            compose.runOnUiThread { controller.remotePlayer()!!.pause() }
            await(10000) { controller.remotePlayer()?.playWhenReady == false }
            compose.runOnUiThread { controller.remotePlayer()!!.seekTo(10000) }
            await(10000) { (controller.remotePlayer()?.currentPosition ?: 0) >= 9800 }
            compose.runOnUiThread { controller.remotePlayer()!!.play() }
            await(10000) { controller.remotePlayer()?.isPlaying == true }
            Thread.sleep(1500) // Allow the independent receiver to sample actual decoded frames/audio.
            compose.runOnUiThread { controller.endSession() }
            assertFalse(controller.state.value.chromecastSession)
        } finally {
            compose.runOnUiThread { controller.endSession(); controller.setDiscoveryEnabled(false) }
            upstream.stop()
        }
    }

    @Test fun deviceAuthRejectsInvalidResponses() {
        val nonce = ByteArray(16) { it.toByte() }
        val response = CastVmFixture.response(nonce)
        CastDeviceAuth.verify(response, CastVmFixture.tls, nonce, listOf(CastVmFixture.root), CastVmFixture.root, CastVmFixture.fixedTime)
        fun rejected(action: () -> Unit) { assertTrue("Invalid authentication accepted", runCatching(action).isFailure) }
        rejected { CastDeviceAuth.verify(response, CastVmFixture.tls, nonce, now = CastVmFixture.fixedTime) }
        rejected { CastDeviceAuth.verify(response, CastVmFixture.tls, ByteArray(16), listOf(CastVmFixture.root), CastVmFixture.root, CastVmFixture.fixedTime) }
        rejected { CastDeviceAuth.verify(CastVmFixture.response(nonce, wrongSignature = true), CastVmFixture.tls, nonce, listOf(CastVmFixture.root), CastVmFixture.root, CastVmFixture.fixedTime) }
        rejected { CastDeviceAuth.verify(response, CastVmFixture.tls, nonce, listOf(CastVmFixture.root), CastVmFixture.root, Date(0)) }
        rejected { CastDeviceAuth.verify(CastVmFixture.response(nonce, revoked = true), CastVmFixture.tls, nonce, listOf(CastVmFixture.root), CastVmFixture.root, CastVmFixture.fixedTime) }
    }
}
