@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hhst.youtubelite.player.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ActivityScenario
import com.google.common.util.concurrent.ListenableFuture
import com.hhst.youtubelite.cast.CastController
import com.hhst.youtubelite.cast.protocol.CastV2Session
import com.hhst.youtubelite.extension.PreferenceKeys
import com.hhst.youtubelite.extractor.ExtractionTestActivity
import com.hhst.youtubelite.player.engine.OfflineWatchFixture
import java.io.Closeable
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the production MediaSession and engine with local media, without a remote service. */
class PlaybackSessionStopAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun controllerStopEndsLocalAndAudioOnlyPlaybackEvenWithBackgroundPlayEnabled() {
        OfflineWatchFixture("stopLocal01").use { fixture ->
            instrumentation.runOnMainSync {
                fixture.prefs.setEnabled(PreferenceKeys.ENABLE_BACKGROUND_PLAY, true)
            }
            fixture.launch()
            BoundService().use { binding ->
                val service = binding.service
                for (audioOnly in listOf(false, true)) {
                    if (audioOnly) {
                        instrumentation.runOnMainSync { fixture.model.playAudioOnlyInBackground() }
                        assertTrue(fixture.model.audioOnlyBackground)
                    }
                    withController(service) { controller ->
                        instrumentation.runOnMainSync {
                            assertTrue("A current session must accept a real STOP", controller.isCommandAvailable(Player.COMMAND_STOP))
                            controller.stop()
                        }
                        fixture.await("STOP must clear engine truth and the visible session") {
                            val snapshot = fixture.engine.snapshot.value
                            snapshot.videoId == null && !snapshot.prepared && !snapshot.isPlaying &&
                                !snapshot.userWantsPlay && !fixture.model.uiState.value.visible
                        }
                        instrumentation.runOnMainSync {
                            assertFalse(fixture.model.audioOnlyBackground)
                            assertEquals(Player.STATE_IDLE, rawPlayer(service).playbackState)
                            assertFalse(rawPlayer(service).playWhenReady)
                        }
                    }
                    val prepared = fixture.engine.mediaPrepareCount
                    instrumentation.runOnMainSync { fixture.model.openVideo(fixture.id) }
                    fixture.await("Reopening stopped media must prepare a fresh source") {
                        fixture.engine.mediaPrepareCount > prepared && fixture.engine.snapshot.value.isPlaying
                    }
                }
            }
        }
    }

    @Test fun aQueuedStopCannotCrossHideAndShowWithTheSameSessionWrapper() {
        OfflineWatchFixture("stopEpoch01").use { fixture ->
            fixture.launch()
            BoundService().use { binding ->
                val service = binding.service
                val prepared = fixture.engine.mediaPrepareCount
                instrumentation.runOnMainSync {
                    val wrapper = routingPlayer(service)
                    wrapper.stop() // The router posts; hide and show run before that post.
                    service.hide()
                    service.show("Current playback", null, null)
                    assertSame("Showing the bound session must keep its wrapper", wrapper, routingPlayer(service))
                }
                awaitMainCommands()
                instrumentation.runOnMainSync {
                    assertEquals(fixture.id, fixture.engine.snapshot.value.videoId)
                    assertTrue(fixture.engine.snapshot.value.prepared)
                    assertTrue(fixture.engine.snapshot.value.userWantsPlay)
                    assertTrue(fixture.model.uiState.value.visible)
                    assertEquals(prepared, fixture.engine.mediaPrepareCount)
                }
                // Same long-lived session still accepts a newly received valid STOP.
                withController(service) { controller ->
                    instrumentation.runOnMainSync { controller.stop() }
                    fixture.await { fixture.engine.snapshot.value.videoId == null && !fixture.model.uiState.value.visible }
                }
            }
        }
    }

    @Test fun controllerStopCancelsAnExtractionThatHasNotAttachedItsSource() {
        val blockReads = AtomicBoolean(false)
        val reading = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        OfflineWatchFixture("stopLoad001", onStreamRead = {
            if (blockReads.get()) {
                reading.countDown()
                try { check(release.await(10, TimeUnit.SECONDS)) { "Extraction gate was not released" } }
                finally { returned.countDown() }
            }
        }).use { fixture ->
            try {
                fixture.launch()
                BoundService().use { binding ->
                    withController(binding.service) { controller ->
                        blockReads.set(true)
                        instrumentation.runOnMainSync { fixture.model.openVideo("stopNext001") }
                        assertTrue("Replacement extraction must be in flight", reading.await(5, TimeUnit.SECONDS))
                        lateinit var oldPlayJob: Job
                        instrumentation.runOnMainSync {
                            assertFalse(fixture.engine.snapshot.value.prepared)
                            oldPlayJob = fixture.engine.javaClass.getDeclaredField("playJob")
                                .apply { isAccessible = true }.get(fixture.engine) as Job
                            assertTrue("The gated playback request must still be active", oldPlayJob.isActive)
                            controller.stop()
                        }
                        fixture.await("STOP must cancel the pending extraction") {
                            fixture.engine.snapshot.value.videoId == null && !fixture.model.uiState.value.visible
                        }
                        val prepared = fixture.engine.mediaPrepareCount
                        blockReads.set(false)
                        release.countDown()
                        assertTrue(returned.await(5, TimeUnit.SECONDS))
                        runBlocking { withTimeout(5_000) { oldPlayJob.join() } }
                        instrumentation.runOnMainSync {
                            val snapshot = fixture.engine.snapshot.value
                            assertNull(snapshot.videoId)
                            assertFalse(snapshot.prepared)
                            assertFalse(snapshot.userWantsPlay)
                            assertFalse(fixture.model.uiState.value.visible)
                            assertEquals("STOP alone must reject the completed old source", prepared, fixture.engine.mediaPrepareCount)
                        }
                        instrumentation.runOnMainSync { fixture.model.openVideo("stopFresh01") }
                        fixture.await("Only the fresh request may attach after STOP") {
                            fixture.engine.snapshot.value.videoId == "stopFresh01" && fixture.engine.snapshot.value.isPlaying
                        }
                        assertEquals("The cancelled source must never prepare", prepared + 1, fixture.engine.mediaPrepareCount)
                    }
                }
            } finally { release.countDown() }
        }
    }

    @Test fun replacedAndHiddenWrappersCannotStopTheCurrentPlayback() {
        OfflineWatchFixture("stopOwner01").use { fixture ->
            fixture.launch()
            BoundService().use { binding ->
                val service = binding.service
                instrumentation.runOnMainSync {
                    val old = routingPlayer(service)
                    old.stop()
                    service.attach(object : ForwardingPlayer(rawPlayer(service)) {})
                    old.stop() // Rejected immediately as well as when the earlier post executes.
                    service.hide()
                    routingPlayer(service).stop()
                    service.show("Current playback", null, null)
                }
                awaitMainCommands()
                instrumentation.runOnMainSync {
                    assertEquals(fixture.id, fixture.engine.snapshot.value.videoId)
                    assertTrue(fixture.engine.snapshot.value.prepared)
                    assertTrue(fixture.engine.snapshot.value.userWantsPlay)
                }
                withController(service) { controller ->
                    instrumentation.runOnMainSync { controller.stop() }
                    fixture.await { fixture.engine.snapshot.value.videoId == null }
                }
            }
        }
    }

    @Test fun queuedCommandsCannotReachAReplacementRouterHandler() {
        val previous = PlaybackCommandRouter.handler
        val old = RecordingHandler()
        val current = RecordingHandler()
        try {
            instrumentation.runOnMainSync {
                PlaybackCommandRouter.handler = old
                PlaybackCommandRouter.stop()
                PlaybackCommandRouter.handler = current
            }
            awaitMainCommands()
            assertEquals(0, old.stops)
            assertEquals(0, current.stops)
            instrumentation.runOnMainSync { PlaybackCommandRouter.stop() }
            awaitMainCommands()
            assertEquals(1, current.stops)
        } finally {
            instrumentation.runOnMainSync { PlaybackCommandRouter.handler = previous }
        }
    }

    @Test fun aDestroyedServiceCannotDispatchFromItsRetainedWrapper() {
        val previous = PlaybackCommandRouter.handler
        val handler = RecordingHandler()
        var raw: ExoPlayer? = null
        try {
            ActivityScenario.launch(ExtractionTestActivity::class.java).use {
                val binding = BoundService()
                val retained: Player
                try {
                    lateinit var wrapper: Player
                    instrumentation.runOnMainSync {
                        PlaybackCommandRouter.handler = handler
                        val player = ExoPlayer.Builder(instrumentation.targetContext).build()
                        raw = player
                        binding.service.attach(player)
                        wrapper = routingPlayer(binding.service)
                    }
                    retained = wrapper
                } finally { binding.close() }
                val deadline = android.os.SystemClock.elapsedRealtime() + 5_000
                while (!binding.destroyed && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
                assertTrue("The bound-only service must finish destruction", binding.destroyed)
                instrumentation.runOnMainSync { retained.stop() }
                awaitMainCommands()
                assertEquals(0, handler.stops)
            }
        } finally {
            instrumentation.runOnMainSync {
                raw?.release()
                PlaybackCommandRouter.handler = previous
            }
        }
    }

    @Test fun endingAPendingCastSessionRejectsItsLateConnectedCallback() {
        // Loopback endpoint open so the real channel cannot fail its handshake before the test drives the listener.
        ServerSocket(0).use { endpoint ->
            lateinit var callback: CastV2Session.Listener
            val started = AtomicInteger()
            val cast = CastController(instrumentation.targetContext, OkHttpClient(),
                sessionFactory = { host, port, listener ->
                    callback = listener
                    CastV2Session.forDefaultMediaReceiver(host, port, listener)
                })
            try {
                instrumentation.runOnMainSync {
                    val type = CastController::class.java.declaredClasses.single { it.simpleName == "Endpoint" }
                    val constructor = type.declaredConstructors.single().apply { isAccessible = true }
                    @Suppress("UNCHECKED_CAST")
                    val routes = CastController::class.java.getDeclaredField("endpoints").apply { isAccessible = true }
                        .get(cast) as MutableMap<String, Any>
                    routes["pending"] = constructor.newInstance("Receiver", "127.0.0.1", endpoint.localPort)
                    cast.onSessionStarted = { started.incrementAndGet() }
                    cast.selectDevice("pending")
                    assertFalse(cast.state.value.chromecastSession)
                    // Explicit Stop invokes both operations even while chromecastSession is false (covered by the VM test).
                    cast.stopCasting()
                    cast.endSession()
                    callback.onConnected()
                }
                awaitMainCommands()
                assertEquals("A stopped handshake cannot start casting", 0, started.get())
                assertFalse(cast.state.value.chromecastSession)
                // Positive control: a fresh session's callback remains valid.
                instrumentation.runOnMainSync {
                    cast.selectDevice("pending")
                    callback.onConnected()
                }
                awaitMainCommands()
                assertEquals(1, started.get())
                assertTrue(cast.state.value.chromecastSession)
            } finally {
                instrumentation.runOnMainSync { cast.stopCasting(); cast.endSession() }
            }
        }
    }

    /** Wait only for already queued commands, independently of rendering or binder idleness. */
    private fun awaitMainCommands() {
        val reached = CountDownLatch(1)
        assertTrue("The main-thread command barrier must be accepted",
            Handler(Looper.getMainLooper()).post { reached.countDown() })
        assertTrue("Queued main-thread commands must finish within 5 seconds",
            reached.await(5, TimeUnit.SECONDS))
    }

    private fun routingPlayer(service: PlaybackService) = field(service, "routingPlayer") as Player
    private fun rawPlayer(service: PlaybackService) = field(service, "player") as Player
    private fun field(service: PlaybackService, name: String): Any? =
        PlaybackService::class.java.getDeclaredField(name).apply { isAccessible = true }.get(service)

    private fun withController(service: PlaybackService, block: (MediaController) -> Unit) {
        lateinit var future: ListenableFuture<MediaController>
        instrumentation.runOnMainSync {
            val session = field(service, "session") as MediaSession
            future = MediaController.Builder(instrumentation.targetContext, session.token).buildAsync()
        }
        val controller = future.get(5, TimeUnit.SECONDS)
        try { block(controller) }
        finally { instrumentation.runOnMainSync { controller.release() } }
    }

    private inner class BoundService : Closeable {
        private val context = instrumentation.targetContext.applicationContext
        private val intent = Intent(context, PlaybackService::class.java)
        private val connected = CountDownLatch(1)
        private lateinit var binder: PlaybackService.LocalBinder
        val service: PlaybackService get() = requireNotNull(binder.getService())
        val destroyed: Boolean get() = binder.getService() == null
        private val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                binder = service as PlaybackService.LocalBinder
                connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        init {
            instrumentation.runOnMainSync {
                assertTrue(context.bindService(intent, connection, Context.BIND_AUTO_CREATE))
            }
            check(connected.await(5, TimeUnit.SECONDS)) { "Playback service did not connect" }
        }
        override fun close() {
            instrumentation.runOnMainSync { context.unbindService(connection) }
        }
    }

    private class RecordingHandler : PlaybackCommandRouter.Handler {
        var stops = 0
        override fun onPlayPause() = Unit
        override fun onPlay() = Unit
        override fun onPause() = Unit
        override fun onStop() { stops++ }
        override fun onNext() = Unit
        override fun onPrevious() = Unit
    }
}
