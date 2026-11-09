package com.hhst.youtubelite.cast

import android.net.ConnectivityManager
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.cast.protocol.CastV2Session
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in interoperability test against an independent desktop Cast receiver. No test trust roots. */
class CastSoftwareReceiverAndroidTest {
    @Test fun desktopReceiverPlaysPausesAndSeeksWithProductionAuthentication() {
        val args = InstrumentationRegistry.getArguments()
        val host = args.getString("castReceiverHost")
        val media = args.getString("castMediaUrl")
        assumeTrue("Supply -e network 1 -e castReceiverHost IP -e castMediaUrl URL",
            args.getString("network") == "1" && host != null && media != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val connected = AtomicBoolean()
        val closing = AtomicBoolean()
        val failure = AtomicReference<String>()
        val state = AtomicReference<String>()
        val position = AtomicLong()
        val session = CastV2Session.forDefaultMediaReceiver(requireNotNull(host),
            args.getString("castReceiverPort")?.toInt() ?: 8009,
            object : CastV2Session.Listener {
                override fun onConnected() { connected.set(true) }
                override fun onMediaStatus(playerState: String?, positionMs: Long, durationMs: Long, idleReason: String?) {
                    state.set(playerState)
                    position.set(positionMs)
                    if (idleReason == "ERROR") failure.set("Receiver returned IDLE/ERROR")
                }
                override fun onLaunchError(reason: String) { failure.set(reason) }
                override fun onChannelError(reason: String) { if (!closing.get()) failure.set(reason) }
                override fun onClosed() { if (!closing.get()) failure.set("Receiver closed the session") }
                override fun onLoadFailed(type: String) { failure.set(type) }
            }, LocalCastNetwork.select(context.getSystemService(ConnectivityManager::class.java), host))
        fun await(label: String, condition: () -> Boolean) {
            val deadline = System.nanoTime() + 25_000_000_000L
            while (!condition() && failure.get() == null && System.nanoTime() < deadline) Thread.sleep(50)
            assertNull("$label: ${failure.get()}", failure.get())
            assertTrue("$label: state=${state.get()}, position=${position.get()}", condition())
        }
        try {
            session.start()
            await("production DeviceAuth and receiver launch") { connected.get() }
            session.load(requireNotNull(media), args.getString("castMediaType") ?: "video/mp4",
                "Litube casting regression", null, 0, "Litube test")
            await("video playing") { state.get() == "PLAYING" && position.get() > 1_000 }
            session.pause()
            await("pause") { state.get() == "PAUSED" }
            session.seekTo(10_000)
            await("seek") { position.get() >= 9_800 }
            session.play()
            await("resume") { state.get() == "PLAYING" }
            Thread.sleep(args.getString("castHoldMs")?.toLong()?.coerceIn(0, 30_000) ?: 0)
            assertNull(failure.get(), failure.get())
        } finally {
            closing.set(true)
            session.close()
        }
    }
}
