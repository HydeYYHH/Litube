@file:OptIn(UnstableApi::class)
package com.hhst.youtubelite.player.engine

import android.app.Activity
import android.content.Intent
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.annotation.OptIn
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.lifecycle.Lifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.hhst.youtubelite.channel.ChannelActivity
import com.hhst.youtubelite.data.media.ContentRepositories
import com.hhst.youtubelite.home.HomeActivity
import com.hhst.youtubelite.search.SearchResultsActivity
import com.hhst.youtubelite.player.surface.PlayerUi
import com.hhst.youtubelite.player.surface.MiniPlayerStore
import com.hhst.youtubelite.navigation.WatchTransition
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.koin.core.context.GlobalContext

class BackMiniHandoffAndroidTest {
    @Test fun fullscreenRoundTripRetainsTheLiveDecoderAndSurface() {
        OfflineWatchFixture().use { fixture ->
            fixture.launch()
            fixture.await { fixture.engine.snapshot.value.firstFrameRendered }
            val player = fixture.engine.javaClass.getDeclaredField("player").apply { isAccessible = true }
                .get(fixture.engine) as ExoPlayer
            fun frames(): Int = player.videoDecoderCounters?.let { it.ensureUpdated(); it.renderedOutputBufferCount } ?: 0
            fixture.await { frames() >= 5 }
            var original: SurfaceView? = null
            var prepares = 0
            var switches = 0
            fixture.instrumentation.runOnMainSync {
                original = fixture.surface()
                prepares = fixture.engine.mediaPrepareCount
                switches = fixture.engine.surfaceSwitchCount
                fixture.model.setFullscreen(true)
            }
            fixture.await {
                fixture.model.uiState.value.fullscreen &&
                    (windowBounds(fixture.activity.window.decorView)?.height ?: 0f) >= fixture.activity.window.decorView.height - 2f
            }
            var beforeExit = 0
            fixture.instrumentation.runOnMainSync { beforeExit = frames(); fixture.model.setFullscreen(false) }
            fixture.await {
                !fixture.model.uiState.value.fullscreen &&
                    (windowBounds(fixture.activity.window.decorView)?.height ?: Float.MAX_VALUE) < fixture.activity.window.decorView.height * .8f
            }
            fixture.await { frames() > beforeExit + 2 }
            fixture.instrumentation.runOnMainSync {
                assertSame("Fullscreen round trip must retain the SurfaceView", original, fixture.surface())
                assertEquals("Fullscreen must not reprepare media", prepares, fixture.engine.mediaPrepareCount)
                assertEquals("Fullscreen must keep the attached decoder output", switches, fixture.engine.surfaceSwitchCount)
                assertTrue(fixture.engine.snapshot.value.isPlaying)
            }
        }
    }

    @Test fun backCollapsesWhileTheWatchSurfaceKeepsRendering() = checkHandoff(null)

    @Test fun backFromATopDockKeepsTheSameBoundsAcrossHosts() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val store = GlobalContext.get().get<MiniPlayerStore>()
        lateinit var previous: MiniPlayerStore.State
        instrumentation.runOnMainSync {
            previous = store.load()
            // Force each host to apply its top-edge clamp rather than a placement left by another test or drag.
            store.save(0, 0f, -10_000f)
        }
        try {
            checkHandoff(null)
        } finally {
            instrumentation.runOnMainSync {
                store.save(previous.widthDp, previous.translationXDp, previous.translationYDp)
            }
        }
    }

    @Test fun backAfterTheBrowsePageStopsKeepsRenderingUntilDocked() = checkHandoff(null, waitForSourceStop = true)

    @Test fun channelNavigationCollapsesOnTheBrowseDestination() = checkHandoff(ChannelActivity::class.java)

    @Test fun searchNavigationCollapsesOnTheBrowseDestination() = checkHandoff(SearchResultsActivity::class.java)

    @Test fun miniRestoreKeepsTheSourcePictureUntilTheNewWatchWindowDraws() = withPortraitRotation {
        OfflineWatchFixture().use { fixture ->
            val homeIntent = Intent(fixture.app, HomeActivity::class.java)
                .putExtra(ContentRepositories.EXTRA_FIXTURE_SCENE, true).putExtra("playback_test", true)
            ActivityScenario.launch<HomeActivity>(homeIntent).use { source ->
                lateinit var home: HomeActivity
                source.onActivity { home = it }
                fixture.launch(source = home)
                fixture.await { fixture.surface()?.let(fixture.model::hasRenderedFrame) == true }
                fixture.instrumentation.runOnMainSync { fixture.activity.onBackPressedDispatcher.onBackPressed() }
                fixture.await { home.hasWindowFocus() && fixture.model.uiState.value.mini &&
                    findSurface(home.window.decorView)?.let(fixture.model::hasRenderedFrame) == true }
                val oldSurface = requireNotNull(findSurface(home.window.decorView))
                var target: com.hhst.youtubelite.watch.WatchActivity? = null
                var allowTargetDraw = false
                var coveredSourceDraws = 0
                val blockTarget = ViewTreeObserver.OnPreDrawListener { allowTargetDraw }
                val sourceDraw = ViewTreeObserver.OnDrawListener {
                    if (!fixture.model.uiState.value.mini && WatchTransition.miniRestoreFrame(home) != null)
                        coveredSourceDraws++
                }
                val monitor = ActivityLifecycleMonitorRegistry.getInstance()
                val callback = ActivityLifecycleCallback { activity, stage ->
                    if (activity is com.hhst.youtubelite.watch.WatchActivity && activity !== fixture.activity && stage == Stage.CREATED) {
                        target = activity
                        activity.window.decorView.viewTreeObserver.addOnPreDrawListener(blockTarget)
                    }
                }
                fixture.instrumentation.runOnMainSync {
                    home.window.decorView.viewTreeObserver.addOnDrawListener(sourceDraw)
                    monitor.addLifecycleCallback(callback)
                }
                try {
                    fixture.instrumentation.runOnMainSync { fixture.model.onMiniRestore() }
                    fixture.await { target != null && !fixture.model.uiState.value.mini &&
                        !oldSurface.isAttachedToWindow && coveredSourceDraws > 0 }
                    lateinit var cover: WatchTransition.MiniRestoreFrame
                    fixture.instrumentation.runOnMainSync {
                        cover = requireNotNull(WatchTransition.miniRestoreFrame(home))
                        assertTrue("The source must remain started until the blocked target draws",
                            home.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
                        assertNull("The source must release its live SurfaceView", findSurface(home.window.decorView))
                    }
                    // Read the source window buffer after its live surface is gone; a state-only assertion would miss the empty frame.
                    val image = Bitmap.createBitmap(home.window.decorView.width, home.window.decorView.height, Bitmap.Config.ARGB_8888)
                    try {
                        val copied = CountDownLatch(1)
                        var result = -1
                        fixture.instrumentation.runOnMainSync {
                            PixelCopy.request(home.window, image, { result = it; copied.countDown() }, Handler(Looper.getMainLooper()))
                        }
                        assertTrue("Source window PixelCopy timed out", copied.await(5, TimeUnit.SECONDS))
                        assertEquals(PixelCopy.SUCCESS, result)
                        val center = cover.bounds.center
                        val color = image.getPixel(center.x.toInt(), center.y.toInt())
                        assertTrue("The detached source must still show its decoded blue picture: ${Integer.toHexString(color)}",
                            Color.blue(color) > Color.red(color) + 80 && Color.blue(color) > Color.green(color) + 80)
                    } finally { image.recycle() }
                    fixture.instrumentation.runOnMainSync {
                        allowTargetDraw = true
                        target!!.window.decorView.postInvalidateOnAnimation()
                    }
                    fixture.await { home.lifecycle.currentState == Lifecycle.State.CREATED &&
                        WatchTransition.miniRestoreFrame(home) == null &&
                        target?.let { findSurface(it.window.decorView) }?.let(fixture.model::hasRenderedFrame) == true }
                } finally {
                    fixture.instrumentation.runOnMainSync {
                        monitor.removeLifecycleCallback(callback)
                        home.window.decorView.viewTreeObserver.takeIf { it.isAlive }?.removeOnDrawListener(sourceDraw)
                        target?.window?.decorView?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnPreDrawListener(blockTarget)
                        fixture.model.onMiniClose()
                        target?.finish()
                    }
                }
            }
        }
    }

    private fun findSurface(view: View): SurfaceView? {
        if (view is SurfaceView && view.isShown) return view
        if (view is ViewGroup) for (index in 0 until view.childCount)
            findSurface(view.getChildAt(index))?.let { return it }
        return null
    }

    private data class WatchFrame(
        val width: Float,
        val surface: SurfaceView?,
        val surfaceSwitches: Int,
        val renderedFrames: Int,
    )

    private fun assertBoundsEqual(message: String, expected: Rect, actual: Rect) {
        assertEquals("$message: left", expected.left, actual.left, 2f)
        assertEquals("$message: top", expected.top, actual.top, 2f)
        assertEquals("$message: width", expected.width, actual.width, 2f)
        assertEquals("$message: height", expected.height, actual.height, 2f)
    }

    private fun windowBounds(view: View): Rect? {
        fun node(root: SemanticsNode): SemanticsNode? =
            if (root.config.getOrNull(SemanticsProperties.TestTag) == "player-window") root
            else root.children.firstNotNullOfOrNull(::node)
        val owner = runCatching { view.javaClass.getMethod("getSemanticsOwner").invoke(view) as SemanticsOwner }.getOrNull()
        owner?.let { node(it.rootSemanticsNode) }?.let { return it.boundsInWindow }
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            windowBounds(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun checkHandoff(destination: Class<out Activity>?, waitForSourceStop: Boolean = false) {
        withPortraitRotation { checkPortraitHandoff(destination, waitForSourceStop) }
    }

    private fun withPortraitRotation(block: () -> Unit) {
        if (InstrumentationRegistry.getArguments().getString("rotation_managed_externally") == "1") {
            block()
            return
        }
        fun shell(command: String): String {
            val result = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
            return android.os.ParcelFileDescriptor.AutoCloseInputStream(result).use { it.readBytes().decodeToString() }.trim()
        }
        // A flat emulator's orientation sensor can otherwise turn Back into a fullscreen exit.
        val rotation = shell("settings get system accelerometer_rotation")
        shell("settings put system accelerometer_rotation 0")
        try {
            block()
        } finally {
            shell(if (rotation == "null") "settings delete system accelerometer_rotation"
                else "settings put system accelerometer_rotation $rotation")
        }
    }

    private fun checkPortraitHandoff(destination: Class<out Activity>?, waitForSourceStop: Boolean) {
        OfflineWatchFixture().use { fixture ->
            val intent = Intent(fixture.app, HomeActivity::class.java)
                .putExtra(ContentRepositories.EXTRA_FIXTURE_SCENE, true)
                .putExtra("playback_test", true)
            ActivityScenario.launch<HomeActivity>(intent).use { source ->
                lateinit var home: HomeActivity
                source.onActivity { home = it }
                fixture.launch(source = home)
                fixture.await { fixture.engine.snapshot.value.firstFrameRendered }
                fixture.await {
                    !fixture.model.uiState.value.mini &&
                        (windowBounds(fixture.activity.window.decorView)?.width ?: 0f) > 0f
                }
                if (waitForSourceStop) fixture.await { home.lifecycle.currentState == Lifecycle.State.CREATED }
                val player = fixture.engine.javaClass.getDeclaredField("player").apply { isAccessible = true }
                    .get(fixture.engine) as ExoPlayer
                fun renderedFrames(): Int {
                    val counters = player.videoDecoderCounters ?: return 0
                    counters.ensureUpdated()
                    return counters.renderedOutputBufferCount
                }
                fixture.await { renderedFrames() > 0 }
                var warmupFrames = 0
                fixture.instrumentation.runOnMainSync { warmupFrames = renderedFrames() }
                fixture.await { renderedFrames() >= warmupFrames + 3 }
                var initial = Rect.Zero
                val widths = mutableListOf<Float>()
                val browseBounds = mutableListOf<Rect>()
                val watchFrames = mutableListOf<WatchFrame>()
                val miniActions = mutableListOf<PlayerUi.PlayPauseAction>()
                var originalSurface: SurfaceView? = null
                var originalSurfaceSwitches = 0
                var outgoingWidth = 0f
                var outgoingBounds: Rect? = null
                var watchPauseCount = 0
                var browseResumeCount = 0
                var browse: Activity? = null
                var observer: ViewTreeObserver? = null
                var watchObserver: ViewTreeObserver? = null
                val draw = ViewTreeObserver.OnDrawListener {
                    browse?.let { windowBounds(it.window.decorView) }?.let {
                        widths += it.width
                        browseBounds += it
                        miniActions += PlayerUi.playPauseAction(fixture.model.uiState.value)
                    }
                }
                val watchDraw = ViewTreeObserver.OnDrawListener {
                    windowBounds(fixture.activity.window.decorView)?.let {
                        watchFrames += WatchFrame(it.width, fixture.surface(), fixture.engine.surfaceSwitchCount, renderedFrames())
                        if (fixture.model.uiState.value.mini) {
                            miniActions += PlayerUi.playPauseAction(fixture.model.uiState.value)
                        }
                    }
                }
                val monitor = ActivityLifecycleMonitorRegistry.getInstance()
                val callback = ActivityLifecycleCallback { activity, stage ->
                    if (activity === fixture.activity && stage == Stage.PAUSED) {
                        watchPauseCount++
                        outgoingBounds = windowBounds(activity.window.decorView)
                        outgoingWidth = outgoingBounds?.width ?: 0f
                    }
                    if (stage == Stage.RESUMED && (if (destination == null) activity === home else destination.isInstance(activity))) {
                        browseResumeCount++
                        browse = activity
                        observer = activity.window.decorView.viewTreeObserver.also { it.addOnDrawListener(draw) }
                    }
                }
                fixture.instrumentation.runOnMainSync {
                    initial = requireNotNull(windowBounds(fixture.activity.window.decorView))
                    originalSurface = requireNotNull(fixture.surface())
                    originalSurfaceSwitches = fixture.engine.surfaceSwitchCount
                    watchObserver = fixture.activity.window.decorView.viewTreeObserver.also { it.addOnDrawListener(watchDraw) }
                    monitor.addLifecycleCallback(callback)
                }
                val prepares = fixture.engine.mediaPrepareCount
                try {
                    fixture.instrumentation.runOnMainSync {
                        if (destination == null) {
                            fixture.activity.onBackPressedDispatcher.onBackPressed()
                            // A second press during the local collapse must not navigate again.
                            fixture.activity.onBackPressedDispatcher.onBackPressed()
                        }
                        else fixture.activity.startActivity(Intent(fixture.app, destination)
                            .putExtra(ContentRepositories.EXTRA_FIXTURE_SCENE, true).putExtra("playback_test", true))
                    }
                    fixture.await { fixture.model.uiState.value.mini && browse?.hasWindowFocus() == true }
                    fixture.await {
                        browse?.let { windowBounds(it.window.decorView) }?.width?.let { it < initial.width - 2f } == true &&
                            fixture.engine.snapshot.value.firstFrameRendered && fixture.engine.snapshot.value.isPlaying
                    }
                    if (destination == null) fixture.await {
                        val width = browse?.let { windowBounds(it.window.decorView) }?.width
                        widths.isNotEmpty() && width != null && kotlin.math.abs(width - outgoingWidth) <= 2f
                    } else Thread.sleep(400)
                    fixture.instrumentation.runOnMainSync {
                        val settled = requireNotNull(windowBounds(requireNotNull(browse).window.decorView))
                        if (destination == null) {
                            val intermediate = watchFrames.filter { it.width < initial.width - 4f && it.width > settled.width + 4f }
                            assertTrue("Watch must draw multiple collapse frames: $watchFrames",
                                intermediate.map { it.width }.distinct().size >= 3)
                            assertTrue("Every collapse frame must retain the original SurfaceView: $intermediate",
                                intermediate.all { it.surface === originalSurface })
                            assertTrue("The engine must keep the original surface throughout collapse: $intermediate",
                                intermediate.all { it.surfaceSwitches == originalSurfaceSwitches })
                            assertTrue("The original decoder must output new video frames during collapse: $intermediate",
                                intermediate.last().renderedFrames > intermediate.first().renderedFrames)
                            assertEquals("Watch must finish docking before it pauses", settled.width, outgoingWidth, 2f)
                            val outgoing = requireNotNull(outgoingBounds)
                            assertBoundsEqual("The first browse frame must align with the outgoing Watch frame",
                                outgoing, browseBounds.first())
                            assertBoundsEqual("The final browse frame must keep the outgoing Watch bounds", outgoing, settled)
                            assertTrue("The browse page must start at the docked width without collapsing again: $widths",
                                widths.all { kotlin.math.abs(it - settled.width) <= 2f })
                            assertEquals("Repeated Back must pause Watch only once", 1, watchPauseCount)
                            assertEquals("Repeated Back must return to the browse page only once", 1, browseResumeCount)
                        } else {
                            assertEquals("Watch must keep its full-size frame until navigation", initial.width, outgoingWidth, 2f)
                            assertTrue("The destination must show the outgoing frame before docking: $widths",
                                widths.any { it >= initial.width - 2f })
                            val intermediate = widths.filter { it < initial.width - 4f && it > settled.width + 4f }.distinct()
                            assertTrue("The visible collapse must draw multiple intermediate frames: $widths", intermediate.size >= 3)
                        }
                        assertTrue("The destination must finish in mini mode", settled.width < initial.width - 2f)
                        assertTrue("The mini player must draw playback controls", miniActions.isNotEmpty())
                        assertTrue("Playing video must keep Pause throughout the mini handoff: $miniActions",
                            miniActions.all { it == PlayerUi.PlayPauseAction.Pause })
                    }
                    assertEquals("Navigation must preserve the media session", prepares, fixture.engine.mediaPrepareCount)
                    if (destination == null) assertTrue(fixture.activity.isFinishing)
                } finally {
                    fixture.instrumentation.runOnMainSync {
                        monitor.removeLifecycleCallback(callback)
                        observer?.takeIf { it.isAlive }?.removeOnDrawListener(draw)
                        watchObserver?.takeIf { it.isAlive }?.removeOnDrawListener(watchDraw)
                        fixture.model.onMiniClose()
                        fixture.engine.stop()
                        if (destination != null) browse?.finish()
                    }
                }
            }
        }
    }
}
