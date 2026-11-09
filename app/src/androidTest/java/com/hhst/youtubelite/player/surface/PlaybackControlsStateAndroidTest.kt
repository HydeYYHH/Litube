@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hhst.youtubelite.player.surface

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.R
import com.hhst.youtubelite.player.PlayerUiState
import com.hhst.youtubelite.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PlaybackControlsStateAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun playerShowsLoadingThenPauseThenReplay() = exercise(mini = false)

    @Test fun miniPlayerShowsLoadingThenPauseThenReplay() = exercise(mini = true)

    @Test fun missingFramesDoNotIntroduceAnArtworkPlaceholder() {
        val video = mutableStateOf("first")
        val ready = mutableStateOf(false)
        var displayed: Bitmap? = null
        compose.setContent {
            displayed = com.hhst.youtubelite.watch.presentation.rememberWatchTransitionFrame(
                video.value, null, ready.value)
        }
        compose.runOnIdle { assertNull(displayed); ready.value = true }
        compose.runOnIdle { assertNull(displayed); ready.value = false }
        compose.runOnIdle { assertNull("A holder reset must not introduce a poster", displayed); video.value = "second" }
        compose.runOnIdle { assertNull("A different video with no captured frame stays uncovered", displayed) }
    }

    @Test fun aHandoffIsRevealedOnceAndANewSnapshotWaitsForItsOwnFrame() {
        val captured = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val replacement = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val handoff = mutableStateOf<Bitmap?>(null)
        val video = mutableStateOf("video")
        val ready = mutableStateOf(true)
        var displayed: Bitmap? = null
        compose.setContent {
            displayed = com.hhst.youtubelite.watch.presentation.rememberWatchTransitionFrame(
                video.value, handoff.value, ready.value)
        }
        compose.runOnIdle { assertNull(displayed); handoff.value = captured; ready.value = false }
        compose.runOnIdle { assertSame(captured, displayed); ready.value = true }
        compose.runOnIdle { assertNull(displayed); ready.value = false }
        compose.runOnIdle {
            assertNull("A holder reset must not flash an already-revealed snapshot", displayed)
            handoff.value = replacement
        }
        compose.runOnIdle { assertSame("A new handoff must wait for its output frame", replacement, displayed); ready.value = true }
        compose.runOnIdle { assertNull(displayed); ready.value = false }
        compose.runOnIdle { assertNull("The replacement snapshot is consumed independently", displayed); video.value = "other-video" }
        compose.runOnIdle { assertSame("A new video cannot inherit snapshot consumption", replacement, displayed) }
    }

    @Test @SdkSuppress(minSdkVersion = 26)
    fun aValidatedEntryFrameStaysAboveTheOldBufferShutter() {
        val state = mutableStateOf(PlayerUiState(
            videoId = "old-video", visible = true, prepared = true,
            controlsVisible = false, firstFrameRendered = true, hasRenderedVideoFrame = true,
            videoWidth = 640, videoHeight = 360,
        ))
        val entry = Bitmap.createBitmap(16, 9, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.GREEN)
        }
        val frame = mutableStateOf<Bitmap?>(null)
        var output: SurfaceView? = null
        val callbacks = proxyActions("entry-shutter-stack")
        compose.setContent { AppTheme {
            Box(Modifier.size(320.dp, 180.dp).testTag("entry-shutter-stack")) {
                PlayerSurface(
                    state.value, remember { mutableStateOf(0L) }, remember { mutableStateOf(0L) },
                    remember { mutableStateOf(null) }, remember { mutableStateOf(null) },
                    remember { mutableStateOf(emptyList()) }, callbacks, { output = it }, {},
                    managedByHost = true, transitionFrame = frame.value, chromeAlpha = { 0f },
                )
            }
        } }
        compose.waitUntil(5_000) { output?.holder?.surface?.isValid == true }
        val original = requireNotNull(output)
        compose.runOnIdle {
            val canvas = requireNotNull(original.holder.lockCanvas())
            try { canvas.drawColor(android.graphics.Color.RED) }
            finally { original.holder.unlockCanvasAndPost(canvas) }
        }
        fun assertCompositedColor(message: String, expected: Int) {
            val bounds = compose.onNodeWithTag("entry-shutter-stack").fetchSemanticsNode().boundsInWindow
            val windowOrigin = IntArray(2)
            compose.runOnIdle { compose.activity.window.decorView.getLocationOnScreen(windowOrigin) }
            // Compose captureToImage omits the SurfaceView; read the actual display composition after its settled buffer/overlay is presented.
            compose.waitUntil(5_000) {
                val screen = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                if (screen == null) false else try {
                    val x = (windowOrigin[0] + bounds.left + bounds.width * .25f).toInt()
                    val y = (windowOrigin[1] + bounds.top + bounds.height * .25f).toInt()
                    (screen.getPixel(x, y) and 0xFFFFFF) == (expected and 0xFFFFFF)
                } finally { screen.recycle() }
            }
            compose.runOnIdle { assertSame(message, original, output) }
        }
        assertCompositedColor("The real surface starts with the old red buffer", android.graphics.Color.RED)
        compose.runOnIdle {
            // Watch already matched this artwork to the replacement video; PlayerSurface receives the validated frame, not an unscoped poster.
            state.value = state.value.copy(videoId = "replacement-video", loading = true,
                prepared = false, firstFrameRendered = false, hasRenderedVideoFrame = false)
            frame.value = entry
        }
        compose.onNodeWithTag("player-frame-shutter").assertIsDisplayed()
        compose.onNodeWithTag("player-handoff-frame").assertIsDisplayed()
        assertCompositedColor("Loading must retain the valid entry/bridge over the shutter", android.graphics.Color.GREEN)
        compose.runOnIdle { state.value = state.value.copy(casting = true) }
        assertCompositedColor("The casting mask remains above a valid transition frame", android.graphics.Color.BLACK)
        compose.runOnIdle { state.value = state.value.copy(casting = false); frame.value = null }
        compose.onNodeWithTag("player-handoff-frame").assertDoesNotExist()
        assertCompositedColor("Without a valid frame the shutter must hide the old video", android.graphics.Color.BLACK)
        // Prove black came from the shutter, not an empty/rebuilt SurfaceView: PixelCopy reads the still-red native buffer underneath.
        val native = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val copied = CountDownLatch(1)
        var copyResult = -1
        PixelCopy.request(original, native, { result -> copyResult = result; copied.countDown() }, Handler(Looper.getMainLooper()))
        assertTrue("The retained native buffer must be readable", copied.await(5, TimeUnit.SECONDS))
        assertEquals(PixelCopy.SUCCESS, copyResult)
        assertEquals(android.graphics.Color.RED, native.getPixel(4, 4))
        native.recycle()
        compose.runOnIdle { state.value = state.value.copy(loading = false, prepared = true,
            firstFrameRendered = true, hasRenderedVideoFrame = true) }
        compose.onNodeWithTag("player-frame-shutter").assertDoesNotExist()
        assertCompositedColor("The current video's first frame reveals the mounted surface", android.graphics.Color.RED)
    }

    @Test @SdkSuppress(minSdkVersion = 26)
    fun aFittedTransitionFrameAlsoCoversTheLetterboxArea() {
        val frame = Bitmap.createBitmap(16, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.GREEN) }
        compose.setContent {
            Box(Modifier.size(320.dp, 180.dp).background(Color.Red)) { PlayerTransitionFrame(frame) }
        }
        val pixels = compose.onNodeWithTag("player-handoff-frame").captureToImage().toPixelMap()
        assertEquals("Letterbox space must cover the unready surface", Color.Black.toArgb(), pixels[1, 1].toArgb())
        assertEquals(Color.Green.toArgb(), pixels[pixels.width / 2, pixels.height / 2].toArgb())
    }

    @Test @SdkSuppress(minSdkVersion = 26)
    fun transitionFrameMatchesEveryResizeModeInWideAndTallViewports() {
        // Red edges distinguish stretching from cropping; green in the middle distinguishes cropped video from black letterbox or a leaked backdrop.
        val frame = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.RED)
            android.graphics.Canvas(this).drawRect(8f, 8f, 56f, 56f,
                android.graphics.Paint().apply { color = android.graphics.Color.GREEN })
        }
        val viewport = mutableStateOf(IntSize(320, 180))
        val resize = mutableStateOf(ResizeMode.Fit)
        compose.setContent {
            Box(Modifier.size(viewport.value.width.dp, viewport.value.height.dp).background(Color.Magenta)) {
                PlayerTransitionFrame(frame, resize.value)
            }
        }
        data class Probe(val mode: ResizeMode, val left: Color, val top: Color)
        val wide = listOf(
            Probe(ResizeMode.Fit, Color.Black, Color.Red),
            Probe(ResizeMode.Fill, Color.Red, Color.Red),
            Probe(ResizeMode.Zoom, Color.Red, Color.Green),
            Probe(ResizeMode.FixedWidth, Color.Red, Color.Green),
            Probe(ResizeMode.FixedHeight, Color.Black, Color.Red),
        )
        val tall = listOf(
            Probe(ResizeMode.Fit, Color.Red, Color.Black),
            Probe(ResizeMode.Fill, Color.Red, Color.Red),
            Probe(ResizeMode.Zoom, Color.Green, Color.Red),
            Probe(ResizeMode.FixedWidth, Color.Red, Color.Black),
            Probe(ResizeMode.FixedHeight, Color.Green, Color.Red),
        )
        for ((size, probes) in listOf(IntSize(320, 180) to wide, IntSize(180, 320) to tall)) {
            for (probe in probes) {
                compose.runOnIdle { viewport.value = size; resize.value = probe.mode }
                val pixels = compose.onNodeWithTag("player-handoff-frame").captureToImage().toPixelMap()
                val left = pixels[(pixels.width / 32).coerceAtLeast(1), pixels.height / 2].toArgb()
                val top = pixels[pixels.width / 2, (pixels.height / 32).coerceAtLeast(1)].toArgb()
                assertEquals("Left edge must match " + probe.mode + " in " + size, probe.left.toArgb(), left)
                assertEquals("Top edge must match " + probe.mode + " in " + size, probe.top.toArgb(), top)
                assertEquals("The video center must stay visible", Color.Green.toArgb(),
                    pixels[pixels.width / 2, pixels.height / 2].toArgb())
            }
        }
    }

    @Test fun videoBufferIgnoresViewportResizesWhileFitFillAndZoomStillChangeTheView() {
        val state = mutableStateOf(PlayerUiState(
            videoId = "video", visible = true, prepared = true, controlsVisible = false,
            isPlaying = true, firstFrameRendered = true, videoWidth = 640, videoHeight = 360,
        ))
        val width = mutableStateOf(320.dp)
        val height = mutableStateOf(180.dp)
        var surface: SurfaceView? = null
        var attaches = 0
        val buffers = mutableListOf<IntSize>()
        val callbacks = proxyActions("fixed-video-buffer")
        compose.setContent { AppTheme {
            Box(Modifier.size(width.value, height.value)) {
                PlayerSurface(
                    state.value, remember { mutableStateOf(0L) }, remember { mutableStateOf(0L) },
                    remember { mutableStateOf(null) }, remember { mutableStateOf(null) },
                    remember { mutableStateOf(emptyList()) }, callbacks,
                    { output ->
                        surface = output
                        attaches++
                        output.holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) = Unit
                            override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
                            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                                buffers += IntSize(width, height)
                            }
                        })
                    }, {}, managedByHost = true,
                )
            }
        } }
        fun assertBuffer(expected: IntSize) = compose.runOnIdle {
            val buffer = requireNotNull(surface).holder.surfaceFrame
            assertEquals(expected.width, buffer.width())
            assertEquals(expected.height, buffer.height())
        }
        assertBuffer(IntSize(640, 360))
        repeat(5) { step ->
            compose.runOnIdle { width.value = (304 - step * 16).dp; height.value = (204 + step * 24).dp }
            assertBuffer(IntSize(640, 360))
        }
        var fit = IntSize.Zero
        compose.runOnIdle {
            fit = requireNotNull(surface).let { IntSize(it.width, it.height) }
            assertEquals("Fit retains the video aspect", 16f / 9f, fit.width.toFloat() / fit.height, .02f)
            state.value = state.value.copy(resizeMode = ResizeMode.Fill)
        }
        var fill = IntSize.Zero
        assertBuffer(IntSize(640, 360))
        compose.runOnIdle {
            fill = requireNotNull(surface).let { IntSize(it.width, it.height) }
            assertTrue("Fill must stretch the view to the taller viewport: fit=$fit fill=$fill", fill.height > fit.height)
            assertEquals(240f / 300f, fill.width.toFloat() / fill.height, .02f)
            state.value = state.value.copy(resizeMode = ResizeMode.Zoom)
        }
        assertBuffer(IntSize(640, 360))
        compose.runOnIdle {
            val zoom = requireNotNull(surface).let { IntSize(it.width, it.height) }
            assertEquals("Zoom retains the video aspect", 16f / 9f, zoom.width.toFloat() / zoom.height, .02f)
            assertTrue("Zoom must cover the viewport: fill=$fill zoom=$zoom", zoom.width > fill.width && zoom.height >= fill.height)
            assertTrue("Viewport/mode changes must not resize the native buffer: $buffers", buffers.isNotEmpty() && buffers.all { it == IntSize(640, 360) })
            state.value = state.value.copy(videoId = "replacement", videoWidth = 1280, videoHeight = 720)
        }
        assertBuffer(IntSize(1280, 720))
        compose.runOnIdle { state.value = state.value.copy(videoId = "pending", videoWidth = 0, videoHeight = 0) }
        assertBuffer(IntSize(1280, 720))
        compose.runOnIdle { assertEquals("Modes and video changes retain the view", 1, attaches) }
    }

    @Test fun shortsAndSystemPipEachHaveOneLoadingIndicator() {
        val shorts = mutableStateOf(true)
        val active = mutableStateOf(true)
        val state = PlayerUiState(visible = true, prepared = true, loading = true, firstFrameRendered = true)
        var surfaces = 0
        val callbacks = proxyActions("loading-indicator-owner")
        compose.setContent { AppTheme {
            Box(Modifier.size(320.dp, 180.dp)) {
                PlayerSurface(
                    state, remember { mutableStateOf(0L) }, remember { mutableStateOf(0L) },
                    remember { mutableStateOf(null) }, remember { mutableStateOf(null) },
                    remember { mutableStateOf(emptyList()) }, callbacks, { surfaces++ }, {},
                    pip = true, showPipLoadingIndicator = !shorts.value,
                )
                if (shorts.value) com.hhst.youtubelite.shorts.presentation.ShortPlaybackControls(
                    playing = false, onToggle = {}, onHoldStart = {}, onHoldEnd = {}, loading = true,
                    active = active.value,
                ) {}
            }
        } }
        compose.onNodeWithTag("short-buffering").assertIsDisplayed()
        compose.onNodeWithTag("player-buffering").assertDoesNotExist()
        compose.runOnIdle { active.value = false }
        compose.onNodeWithTag("short-buffering").assertDoesNotExist()
        compose.onNodeWithTag("player-buffering").assertDoesNotExist()
        compose.runOnIdle { shorts.value = false }
        compose.onNodeWithTag("short-buffering").assertDoesNotExist()
        compose.onNodeWithTag("player-buffering").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, surfaces) }
    }

    @Test fun miniPlayerKeepsPauseWhileTheReplacementSurfaceWaitsForAFrame() {
        val state = mutableStateOf(PlayerUiState(
            mini = true, prepared = true, isPlaying = true, userWantsPlay = true,
            videoWidth = 1280, firstFrameRendered = true, hasRenderedVideoFrame = true,
        ))
        compose.setContent { AppTheme {
            Box(Modifier.size(320.dp, 180.dp)) {
                MiniPlayerChrome(state.value, {}, {}, {}, {}, {}, {})
            }
        } }
        val pause = compose.activity.getString(R.string.action_pause)
        compose.onNodeWithContentDescription(pause).assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(firstFrameRendered = false) }
        compose.onNodeWithContentDescription(pause).assertIsDisplayed()
        compose.onNodeWithTag("player-buffering").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(loading = true, isPlaying = false) }
        compose.onNodeWithTag("player-buffering").assertIsDisplayed()
        compose.onNodeWithContentDescription(pause).assertDoesNotExist()
    }

    private fun exercise(mini: Boolean) {
        val state = mutableStateOf(PlayerUiState(mini = mini, userWantsPlay = true))
        var clicks = 0
        compose.setContent { AppTheme {
            Box(Modifier.size(320.dp, 180.dp)) {
                if (mini) MiniPlayerChrome(state.value, { clicks++ }, {}, {}, {}, {}, {})
                else CenterControls(state.value, { clicks++ })
            }
        } }
        val pause = compose.activity.getString(R.string.action_pause)
        val replay = compose.activity.getString(R.string.action_replay)
        compose.onNodeWithTag("player-buffering").assertIsDisplayed()
        compose.onNodeWithContentDescription(pause).assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(controlsVisible = false) }
        compose.onNodeWithTag("player-buffering").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(prepared = true, isPlaying = true, controlsVisible = true) }
        compose.onNodeWithTag("player-buffering").assertDoesNotExist()
        compose.onNodeWithContentDescription(pause).assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(loading = true, isPlaying = false) }
        compose.onNodeWithTag("player-buffering").assertIsDisplayed()
        compose.onNodeWithContentDescription(pause).assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(loading = false, ended = true) }
        compose.onNodeWithTag("player-buffering").assertDoesNotExist()
        compose.onNodeWithContentDescription(replay).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, clicks) }
    }
}
