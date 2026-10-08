package com.hhst.youtubelite.core

import android.os.Build
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.content.ServiceConnection
import android.content.ComponentName
import android.os.IBinder
import android.media.AudioManager
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.MainActivity
import com.hhst.youtubelite.extractor.ExtractionTestActivity
import com.hhst.youtubelite.player.service.PlaybackService
import com.hhst.youtubelite.player.surface.PlayerWindowHost
import com.hhst.youtubelite.extension.ExtensionManager
import com.hhst.youtubelite.extension.PreferenceKeys
import com.hhst.youtubelite.player.PlayerViewModel
import com.hhst.youtubelite.ui.extension.ExtensionViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@UnstableApi
class PlatformCompatibilityAndroidTest {
    @Test fun supportedDevicesEnterManualPip() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(PipSupport.isSupported(context))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { host ->
                PlayerWindowHost(host, host.getSystemService(AudioManager::class.java), {}, { true }, { null }, { 1280 to 720 })
                    .onPip()
            }
            var entered = false
            val deadline = SystemClock.elapsedRealtime() + 5_000
            while (!entered && SystemClock.elapsedRealtime() < deadline) {
                scenario.onActivity { host ->
                    if (Build.VERSION.SDK_INT >= 26) entered = host.isInPictureInPictureMode
                }
                if (!entered) SystemClock.sleep(100)
            }
            assertTrue("Supported device did not enter PiP", entered)
        }
    }
    @Test fun decoderAndPlaybackNotificationWorkOnMinimumSdk() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val media = File(context.cacheDir, "compat-decoder.mp4")
        instrumentation.context.assets.open("downloader/media/fragmented_avc_aac.mp4").use { input ->
            media.outputStream().use(input::copyTo)
        }
        val firstFrame = CountDownLatch(1)
        val serviceBound = CountDownLatch(1)
        val failure = AtomicReference<String>()
        var player: ExoPlayer? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                assertTrue(binder is PlaybackService.LocalBinder)
                serviceBound.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        var bound = false
        ActivityScenario.launch(ExtractionTestActivity::class.java).use { scenario ->
            try {
                scenario.onActivity { host ->
                    player = ExoPlayer.Builder(host).build().apply {
                        volume = 0f
                        addListener(object : Player.Listener {
                            override fun onRenderedFirstFrame() { firstFrame.countDown() }
                            override fun onPlayerError(error: PlaybackException) {
                                failure.set(error.errorCodeName); firstFrame.countDown()
                            }
                        })
                        setVideoSurfaceView(host.surface)
                        setMediaItem(MediaItem.fromUri(Uri.fromFile(media)))
                        prepare(); play()
                    }
                    PlaybackService.start(host)
                    bound = host.bindService(Intent(host, PlaybackService::class.java), connection, Context.BIND_AUTO_CREATE)
                }
                assertTrue("Decoder did not output a frame", firstFrame.await(20, TimeUnit.SECONDS))
                assertNull(failure.get())
                assertTrue("Playback service did not start", serviceBound.await(10, TimeUnit.SECONDS))
                SystemClock.sleep(500)
                scenario.onActivity {
                    player!!.videoDecoderCounters!!.ensureUpdated()
                    assertTrue(player!!.videoDecoderCounters!!.renderedOutputBufferCount > 0)
                }
            } finally {
                scenario.onActivity { host ->
                    player?.release()
                    if (bound) host.unbindService(connection)
                    host.stopService(Intent(host, PlaybackService::class.java))
                }
                media.delete()
            }
        }
    }
    @Test fun unsupportedPipIsHiddenAndAllAutomaticUpdatesAreSafe() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(!PipSupport.isSupported(context))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val model = GlobalContext.get().get<PlayerViewModel>()
                model.refreshPipAvailability()
                assertFalse(model.uiState.value.pipAvailable)
                val settings = GlobalContext.get().get<ExtensionViewModel>()
                assertFalse(settings.uiState.value.toggles.containsKey(PreferenceKeys.ENABLE_PIP))
                activity.setPlayerPipEligible(true)
                PipAutoEnter.apply(activity, autoEnter = true)
                val handle = PipAutoEnter.suppress()
                handle.restore()
                assertFalse(PipAutoEnter.lastRequestedAutoEnter)
                assertNull(PipAutoEnter.lastPushError)
                activity.setPlayerPipEligible(false)
            }
        }
    }

    @Test fun supportedDevicePublishesPipAvailability() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(PipSupport.isSupported(context))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                val model = GlobalContext.get().get<PlayerViewModel>()
                val prefs = GlobalContext.get().get<ExtensionManager>()
                model.refreshPipAvailability()
                assertEquals(prefs.isEnabled(PreferenceKeys.ENABLE_PIP), model.uiState.value.pipAvailable)
            }
        }
    }
}
