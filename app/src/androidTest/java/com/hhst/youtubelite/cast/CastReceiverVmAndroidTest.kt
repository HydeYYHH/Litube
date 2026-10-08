package com.hhst.youtubelite.cast.protocol

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.cast.CastVmFixture
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/** Simulated receiver: run on a SECOND AVD with `-e castVm 1`, paired with CastVmAndroidTest. */
class CastReceiverVmAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun serveReceiver() {
        assumeTrue("Run explicitly with -e castVm 1",
            InstrumentationRegistry.getArguments().getString("castVm") == "1")
        lateinit var web: WebView
        compose.runOnUiThread { web = WebView(compose.activity).apply {
            settings.javaScriptEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            webViewClient = WebViewClient()
        } }
        compose.setContent { AndroidView({ web }, modifier = Modifier.fillMaxSize()) }
        fun js(script: String): String {
            val done = CountDownLatch(1)
            var result = "null"
            compose.runOnUiThread { web.evaluateJavascript(script) { result = it; done.countDown() } }
            check(done.await(10, TimeUnit.SECONDS))
            return result
        }
        val nsd = compose.activity.getSystemService(Context.NSD_SERVICE) as NsdManager
        val registered = CountDownLatch(1)
        val registration = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) { error("NSD registration $code") }
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) = Unit
            override fun onServiceRegistered(info: NsdServiceInfo) { registered.countDown() }
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
        }
        val server = CastVmFixture.context().serverSocketFactory.createServerSocket(8009) as SSLServerSocket
        server.soTimeout = 120_000
        nsd.registerService(NsdServiceInfo().apply {
            serviceName = "Cast-VM"; serviceType = "_googlecast._tcp."; port = 8009
            setAttribute("fn", "Cast VM"); setAttribute("ca", "1"); setAttribute("id", "cast-vm")
        }, NsdManager.PROTOCOL_DNS_SD, registration)
        assertTrue(registered.await(20, TimeUnit.SECONDS))
        var frames = 0L
        var audioBytes = 0L
        var sawLoad = false
        var sawPause = false
        var sawSeek = false
        try {
            (server.accept() as SSLSocket).use { socket ->
                socket.soTimeout = 45_000
                val input = socket.inputStream
                val output = socket.outputStream
                var mediaUrl = ""
                var mediaSession = 1
                var paused = false
                fun send(message: CastMessage, json: JSONObject) {
                    CastMessageCodec.writeFramed(output, CastMessage.utf8(message.destinationId,
                        message.sourceId, message.namespace, json.toString())); output.flush()
                }
                fun mediaStatus(message: CastMessage, requestId: Int) {
                    val raw = js("(function(){var v=document.querySelector('video');return v?JSON.stringify({position:v.currentTime,duration:v.duration||30,frames:v.getVideoPlaybackQuality?v.getVideoPlaybackQuality().totalVideoFrames:0,audio:v.webkitAudioDecodedByteCount||0,ready:v.readyState,paused:v.paused}):null})()")
                    val status = if (raw == "null") JSONObject() else JSONObject(JSONArray("[$raw]").getString(0))
                    frames = maxOf(frames, status.optLong("frames"))
                    audioBytes = maxOf(audioBytes, status.optLong("audio"))
                    val state = if (status.optInt("ready") < 2) "BUFFERING" else if (paused) "PAUSED" else "PLAYING"
                    send(message, JSONObject().put("type", "MEDIA_STATUS").put("requestId", requestId).put("status",
                        JSONArray().put(JSONObject().put("mediaSessionId", mediaSession).put("playerState", state)
                            .put("currentTime", status.optDouble("position", 0.0)).put("media",
                                JSONObject().put("contentId", mediaUrl).put("duration", 30)))))
                }
                while (true) {
                    val message = CastMessageCodec.readFramed(input) ?: break
                    if (message.namespace == CastDeviceAuth.NAMESPACE) {
                        val challenge = Proto.parse(Proto.bytes(Proto.parse(message.payloadBinary!!), 1))
                        val nonce = Proto.bytes(challenge, 2)
                        CastMessageCodec.writeFramed(output, CastMessage(0, "receiver-0", "sender-0", CastDeviceAuth.NAMESPACE,
                            1, null, Proto.encode(2 to CastVmFixture.response(nonce))))
                        output.flush(); continue
                    }
                    val payload = JSONObject(message.payloadUtf8 ?: "{}")
                    val type = payload.optString("type")
                    val requestId = payload.optInt("requestId")
                    if (message.namespace == CastV2Channel.NS_HEARTBEAT && type == "PING") {
                        send(message, JSONObject().put("type", "PONG")); continue
                    }
                    if (message.namespace.endsWith("cast.receiver")) {
                        if (type == "STOP") break
                        if (type == "LAUNCH" || type == "GET_STATUS") send(message, JSONObject().put("type", "RECEIVER_STATUS")
                            .put("requestId", requestId).put("status", JSONObject().put("applications", JSONArray().put(JSONObject()
                                .put("appId", "CC1AD845").put("sessionId", "vm-session").put("transportId", "vm-transport")))))
                    } else if (message.namespace.endsWith("cast.media")) {
                        when (type) {
                            "LOAD" -> {
                                mediaUrl = payload.getJSONObject("media").getString("contentId")
                                sawLoad = true; mediaSession++; paused = false
                                compose.runOnUiThread { web.loadUrl(mediaUrl.substringBeforeLast('/') + "/player") }
                                Thread.sleep(1000)
                                mediaStatus(message, requestId)
                            }
                            "GET_STATUS" -> mediaStatus(message, requestId)
                            "PAUSE" -> { sawPause = true; paused = true; js("document.querySelector('video').pause()"); mediaStatus(message, requestId) }
                            "PLAY" -> { paused = false; js("document.querySelector('video').play()"); mediaStatus(message, requestId) }
                            "SEEK" -> { sawSeek = true; js("document.querySelector('video').currentTime=${payload.optDouble("currentTime")}"); mediaStatus(message, requestId) }
                            "SET_PLAYBACK_RATE" -> { js("document.querySelector('video').playbackRate=${payload.optDouble("playbackRate")}"); mediaStatus(message, requestId) }
                            "STOP" -> { js("document.querySelector('video').pause()"); mediaStatus(message, requestId) }
                        }
                    }
                }
            }
            assertTrue("No Cast LOAD", sawLoad)
            assertTrue("Video never decoded: $frames", frames > 10)
            assertTrue("Audio never decoded: $audioBytes", audioBytes > 0)
            assertTrue("No pause", sawPause)
            assertTrue("No seek", sawSeek)
        } finally {
            server.close(); nsd.unregisterService(registration)
            compose.runOnUiThread { web.destroy() }
        }
    }
}
