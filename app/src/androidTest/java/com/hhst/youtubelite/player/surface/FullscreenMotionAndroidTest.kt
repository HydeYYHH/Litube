package com.hhst.youtubelite.player.surface

import android.view.SurfaceView
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.hhst.youtubelite.core.PreferenceStore
import com.hhst.youtubelite.player.PlayerUiState
import com.hhst.youtubelite.ui.design.LocalMotionScale
import com.hhst.youtubelite.ui.theme.AppTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class FullscreenMotionAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun buttonFullscreenAnimatesRetargetsAndReversesOnOneSurface() = checkMotion(1f)
    @Test fun disabledMotionChangesModesWithoutIntermediateFrames() = checkMotion(0f)

    @Test fun playerEntryAndRestoreExitKeepAnimatingAcrossRotation() = checkRotatingSwipe(1f)
    @Test fun rotatingSwipesRespectDisabledMotion() = checkRotatingSwipe(0f)
    @Test fun containerSwipesKeepTheirMotionClockAcrossRotation() = checkRotatingSwipe(1f, containerGesture = true)

    private fun checkRotatingSwipe(motionScale: Float, containerGesture: Boolean = false) {
        var fullscreen by mutableStateOf(false)
        var landscape by mutableStateOf(false)
        var density = 1f
        var handle: FullscreenSwipeHandle? = null
        var surfaces = 0
        var restores = 0
        val store = MiniPlayerStore(PreferenceStore.of(compose.activity, "rotating-swipe-$motionScale").apply { clearAll() })
        compose.mainClock.autoAdvance = false
        try {
            compose.setContent { AppTheme { CompositionLocalProvider(LocalMotionScale provides motionScale) {
                density = LocalDensity.current.density
                MiniPlayerWindow(if (landscape) 600 else 320, 0, store, false, {},
                    mini = false, fillsWindow = fullscreen,
                    embeddedTopDp = 60, embeddedWidthDp = if (landscape) 600 else 320, embeddedHeightDp = 180,
                    onFullscreenSwipe = { fullscreen = it },
                    onContentFullscreenPull = { fullscreen = it },
                    onFullscreenSwipeRestore = { fullscreen = false; restores++ },
                    modifier = Modifier.requiredSize(if (landscape) 600.dp else 320.dp, if (landscape) 320.dp else 600.dp)) {
                    handle = LocalFullscreenSwipeHandle.current
                    AndroidView(factory = { surfaces++; SurfaceView(it).apply { holder.setFixedSize(640, 360) } },
                        modifier = Modifier.fillMaxSize())
                }
            } } }
            compose.mainClock.advanceTimeBy(64)
            for (enter in listOf(true, false)) {
                val distance = (if (enter || containerGesture) -120f else 120f) * density
                compose.runOnIdle {
                    if (enter && containerGesture) handle!!.dragContentPull(-distance) else handle!!.drag(distance)
                }
                compose.mainClock.advanceTimeByFrame()
                compose.runOnIdle {
                    assertTrue(if (enter && containerGesture) handle!!.releaseContentPull(distance, 0f) else handle!!.release(distance, 0f))
                }
                compose.mainClock.advanceTimeBy(64)
                compose.runOnIdle { assertEquals(enter, fullscreen); landscape = enter }
                // Simulate the destination window's orientation arriving during release.
                compose.mainClock.advanceTimeBy(48)
                val inFlight = compose.onNodeWithTag("player-window").fetchSemanticsNode().boundsInRoot
                val fullHeight = (if (enter) 320f else 600f) * density
                val targetHeight = if (enter) fullHeight else 180f * density
                if (motionScale > 0f && (containerGesture || !enter)) {
                    assertTrue("Rotation must retain intermediate container bounds: $inFlight", inFlight.height > (if (enter) 0f else 180f * density))
                    assertTrue("Rotation must not snap to fullscreen: $inFlight", inFlight.height < fullHeight)
                } else assertEquals(targetHeight, inFlight.height, 2f)
                // Include two layout/frame handoffs, while still finishing sooner
                // than a fresh 240 ms animation started after the rotation.
                compose.mainClock.advanceTimeBy(192)
                assertEquals("Rotation must not restart the release clock", targetHeight,
                    compose.onNodeWithTag("player-window").fetchSemanticsNode().boundsInRoot.height, 2f)
                compose.runOnIdle { assertEquals(1f, handle!!.pictureScale, 0f); assertEquals(1, surfaces) }
            }
            compose.runOnIdle { assertEquals(if (containerGesture) 0 else 1, restores) }
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test @androidx.media3.common.util.UnstableApi
    fun normalFullscreenGesturesTransformThePictureWithoutResizingThePlayer() {
        var fullscreen by mutableStateOf(false)
        var density = 1f
        var handle: FullscreenSwipeHandle? = null
        var surface: SurfaceView? = null
        var creations = 0
        val commits = mutableListOf<String>()
        val callbacks = proxyActions("picture-fullscreen-motion")
        val frame = android.graphics.Bitmap.createBitmap(320, 180, android.graphics.Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.GREEN)
            android.graphics.Canvas(this).drawRect(120f, 60f, 200f, 120f,
                android.graphics.Paint().apply { color = android.graphics.Color.RED })
        }
        val store = MiniPlayerStore(PreferenceStore.of(compose.activity, "picture-fullscreen-motion").apply { clearAll() })
        compose.mainClock.autoAdvance = false
        try {
            compose.setContent { AppTheme { CompositionLocalProvider(LocalMotionScale provides 1f) {
                density = LocalDensity.current.density
                MiniPlayerWindow(320, 0, store, false, {}, mini = false, fillsWindow = fullscreen,
                    embeddedTopDp = 60, embeddedHeightDp = 180,
                    onFullscreenSwipe = { fullscreen = it; commits += "normal" },
                    onFullscreenSwipeRestore = { fullscreen = false; commits += "restore" },
                    modifier = Modifier.size(320.dp, 600.dp)) {
                    handle = LocalFullscreenSwipeHandle.current
                    PlayerSurface(
                        PlayerUiState(videoId = "picture", visible = true, prepared = true,
                            fullscreen = fullscreen, controlsVisible = false, videoWidth = 640, videoHeight = 360),
                        remember { mutableStateOf(0L) }, remember { mutableStateOf(0L) },
                        remember { mutableStateOf(null) }, remember { mutableStateOf(null) },
                        remember { mutableStateOf(emptyList()) }, callbacks,
                        { surface = it; creations++ }, {}, managedByHost = true, transitionFrame = frame, chromeAlpha = { 0f },
                    )
                }
            } } }
            fun bounds() = compose.onNodeWithTag("player-window").fetchSemanticsNode().boundsInRoot
            fun marker(): Rect {
                val pixels = compose.onNodeWithTag("player-window").captureToImage().toPixelMap()
                var left = pixels.width; var top = pixels.height; var right = 0; var bottom = 0
                for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
                    val color = pixels[x, y]
                    if (color.red > .8f && color.green < .2f && color.blue < .2f) {
                        left = minOf(left, x); top = minOf(top, y); right = maxOf(right, x); bottom = maxOf(bottom, y)
                    }
                }
                assertTrue("The real PlayerSurface must draw the picture marker", right > left && bottom > top)
                return Rect(left.toFloat(), top.toFloat(), (right + 1).toFloat(), (bottom + 1).toFloat())
            }
            compose.mainClock.advanceTimeBy(64)
            val embedded = bounds()
            val initialMarker = marker()
            var nativeSize = androidx.compose.ui.unit.IntSize.Zero
            compose.runOnIdle { nativeSize = androidx.compose.ui.unit.IntSize(surface!!.width, surface!!.height); handle!!.drag(-60f * density) }
            compose.mainClock.advanceTimeByFrame()
            val zoomed = marker()
            assertEquals("Upward entry leaves the player frame fixed", embedded, bounds())
            assertTrue("Only the picture magnifies", zoomed.width > initialMarker.width * 1.1f && zoomed.height > initialMarker.height * 1.1f)
            compose.runOnIdle {
                assertEquals(nativeSize, androidx.compose.ui.unit.IntSize(surface!!.width, surface!!.height))
                handle!!.end(false)
            }
            compose.mainClock.advanceTimeBy(64)
            assertTrue("Cancelled entry visibly settles the picture", marker().width < zoomed.width)
            assertEquals(embedded, bounds())
            compose.mainClock.advanceTimeBy(300)
            assertEquals(initialMarker.width, marker().width, 2f)
            compose.runOnIdle { assertTrue(commits.isEmpty()); handle!!.drag(-60f * density); handle!!.release(-60f * density, 0f) }
            compose.mainClock.advanceTimeBy(400)
            val full = bounds()
            val fullMarker = marker()
            compose.runOnIdle { nativeSize = androidx.compose.ui.unit.IntSize(surface!!.width, surface!!.height); handle!!.drag(120f * density) }
            compose.mainClock.advanceTimeByFrame()
            val translated = marker()
            assertEquals("Downward exit leaves the fullscreen frame fixed", full, bounds())
            assertEquals("Downward exit never shrinks the picture", fullMarker.width, translated.width, 2f)
            assertEquals(fullMarker.height, translated.height, 2f)
            assertEquals(fullMarker.left, translated.left, 2f)
            assertTrue("The unscaled picture follows the downward finger", translated.top > fullMarker.top + 20f * density)
            compose.runOnIdle { handle!!.drag(180f * density) }
            compose.mainClock.advanceTimeByFrame()
            val farther = marker()
            compose.runOnIdle { handle!!.drag(240f * density) }
            compose.mainClock.advanceTimeByFrame()
            val farthest = marker()
            assertTrue("Equal finger increments produce less visible picture travel farther out",
                farthest.top - farther.top < farther.top - translated.top)
            assertTrue("A long downward drag remains close to the player", farthest.top - fullMarker.top < 80f * density)
            assertEquals("Resistance never scales the picture", fullMarker.width, farthest.width, 2f)
            assertEquals(full, bounds())
            compose.runOnIdle { handle!!.drag(120f * density) }
            compose.mainClock.advanceTimeByFrame()
            assertEquals("Retracing the finger retraces the same resisted position", translated.top, marker().top, 2f)
            compose.runOnIdle {
                assertEquals(nativeSize, androidx.compose.ui.unit.IntSize(surface!!.width, surface!!.height))
                handle!!.end(false)
            }
            compose.mainClock.advanceTimeBy(64)
            assertTrue("Cancelled exit returns with visible translation", marker().top < translated.top)
            assertEquals(full, bounds())
            compose.mainClock.advanceTimeBy(300)
            assertEquals(fullMarker.top, marker().top, 2f)
            compose.runOnIdle { handle!!.drag(120f * density) }
            compose.mainClock.advanceTimeByFrame()
            val releasedFrom = marker()
            compose.runOnIdle {
                handle!!.release(120f * density, 0f)
                assertFalse("Confirmed downward exit restores the mode immediately", fullscreen)
                assertEquals(listOf("normal", "restore"), commits)
            }
            compose.mainClock.advanceTimeBy(64)
            val leaving = marker()
            val returning = bounds()
            assertTrue("Confirmed exit joins the normal window transition", returning.height > embedded.height && returning.height < full.height)
            assertEquals("The picture retains its size while the available width is unchanged", releasedFrom.width, leaving.width, 2f)
            assertTrue("Release must not throw the picture farther down the screen",
                leaving.top + returning.top <= releasedFrom.top + full.top + 2f)
            compose.mainClock.advanceTimeBy(400)
            assertEquals(embedded, bounds())
            compose.runOnIdle {
                assertEquals(listOf("normal", "restore"), commits)
                assertEquals(1, creations)
                assertEquals(640, surface!!.holder.surfaceFrame.width())
                assertEquals(360, surface!!.holder.surfaceFrame.height())
            }
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun containerEntryAndUpwardExitMeetIncreasingResistanceWhileDragging() {
        var fullscreen by mutableStateOf(false)
        var density = 1f
        var handle: FullscreenSwipeHandle? = null
        var commits = 0
        val store = MiniPlayerStore(PreferenceStore.of(compose.activity, "container-resistance").apply { clearAll() })
        compose.mainClock.autoAdvance = false
        try {
            compose.setContent { AppTheme { CompositionLocalProvider(LocalMotionScale provides 1f) {
                density = LocalDensity.current.density
                MiniPlayerWindow(320, 0, store, false, {}, mini = false, fillsWindow = fullscreen,
                    embeddedTopDp = 60, embeddedHeightDp = 180,
                    onFullscreenSwipe = { commits++; fullscreen = it },
                    onContentFullscreenPull = { commits++; fullscreen = it },
                    modifier = Modifier.size(320.dp, 600.dp)) {
                    handle = LocalFullscreenSwipeHandle.current
                    Box(Modifier.fillMaxSize())
                }
            } } }
            compose.mainClock.advanceTimeBy(64)
            for (enter in listOf(true, false)) {
                compose.runOnIdle { fullscreen = !enter }
                compose.mainClock.advanceTimeBy(400)
                val heights = listOf(60f, 120f, 180f, 240f).map { distance ->
                    compose.runOnIdle {
                        if (enter) handle!!.dragContentPull(distance * density) else handle!!.drag(-distance * density)
                    }
                    compose.mainClock.advanceTimeByFrame()
                    compose.onNodeWithTag("player-window").fetchSemanticsNode().boundsInRoot.height
                }
                val increments = heights.zipWithNext { a, b -> kotlin.math.abs(b - a) }
                assertTrue("Both container directions become progressively harder to drag: $heights",
                    increments[0] > increments[1] && increments[1] > increments[2])
                compose.runOnIdle { assertEquals(1f, handle!!.pictureScale, 0f); handle!!.end(false) }
                compose.mainClock.advanceTimeBy(400)
                assertEquals((if (enter) 180f else 600f) * density,
                    compose.onNodeWithTag("player-window").fetchSemanticsNode().boundsInRoot.height, 2f)
            }
            compose.runOnIdle { assertEquals("Cancelled resistant drags do not change mode", 0, commits) }
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun swipeExitLocksTheCurrentDirectionWithoutReplacingTheButtonsRestoreOrientation() {
        compose.setContent { Box(Modifier.fillMaxSize()) }
        compose.runOnIdle {
            val activity = compose.activity
            val original = activity.requestedOrientation
            val portrait = activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
            val host = PlayerWindowHost(activity, activity.getSystemService(AudioManager::class.java), {}, { false }, { null })
            try {
                host.applyImmersive(true, if (portrait) 1 else 2, if (portrait) 2 else 1)
                host.applyImmersive(false, keepOrientation = true)
                assertEquals(ActivityInfo.SCREEN_ORIENTATION_LOCKED, activity.requestedOrientation)
                host.applyImmersive(true, if (portrait) 1 else 2, if (portrait) 2 else 1)
                host.applyImmersive(false)
                assertEquals("The next button exit restores the original orientation policy", original, activity.requestedOrientation)
            } finally { activity.requestedOrientation = original; host.refreshSystemBars(false) }
        }
    }

    @Test fun downwardExitRestoresTheLatestEntryAfterAnUpwardExit() {
        compose.setContent { Box(Modifier.fillMaxSize()) }
        compose.runOnIdle {
            val activity = compose.activity
            val original = activity.requestedOrientation
            val host = PlayerWindowHost(activity, activity.getSystemService(AudioManager::class.java), {}, { false }, { null })
            try {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                host.applyImmersive(true, 640, 360)
                host.applyImmersive(false, keepOrientation = true)
                assertEquals(ActivityInfo.SCREEN_ORIENTATION_LOCKED, activity.requestedOrientation)
                host.applyImmersive(true, 640, 360, keepOrientation = true)
                host.applyImmersive(false, restoreEntryOrientation = true)
                assertEquals("Restore uses the immediate locked entry, not the older portrait session",
                    ActivityInfo.SCREEN_ORIENTATION_LOCKED, activity.requestedOrientation)
            } finally { activity.requestedOrientation = original; host.refreshSystemBars(false) }
        }
    }

    @Test fun committedFullscreenSwipesNeverCollapseTheNativeSurfaceLayout() {
        var fullscreen by mutableStateOf(false)
        var density = 1f
        var handle: FullscreenSwipeHandle? = null
        var surface: SurfaceView? = null
        var creations = 0
        val commits = mutableListOf<String>()
        val layouts = mutableListOf<androidx.compose.ui.unit.IntSize>()
        val store = MiniPlayerStore(PreferenceStore.of(compose.activity, "fullscreen-release-layout").apply { clearAll() })
        compose.mainClock.autoAdvance = false
        try {
            compose.setContent { AppTheme { CompositionLocalProvider(LocalMotionScale provides 1f) {
                density = LocalDensity.current.density
                MiniPlayerWindow(320, 0, store, false, {}, mini = false, fillsWindow = fullscreen,
                    embeddedTopDp = 60, embeddedWidthDp = 320, embeddedHeightDp = 180,
                    onFullscreenSwipe = { fullscreen = it; commits += "player" },
                    onContentFullscreenPull = { fullscreen = it; commits += "content" },
                    onFullscreenSwipeRestore = { fullscreen = false; commits += "restore" },
                    modifier = Modifier.size(320.dp, 600.dp)) {
                    handle = LocalFullscreenSwipeHandle.current
                    AndroidView(factory = { context ->
                        androidx.media3.ui.AspectRatioFrameLayout(context).apply {
                            setAspectRatio(16f / 9f)
                            addView(SurfaceView(context).also { video ->
                                creations++
                                surface = video
                                video.holder.setFixedSize(640, 360)
                                video.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
                                    layouts += androidx.compose.ui.unit.IntSize(right - left, bottom - top)
                                }
                            }, android.widget.FrameLayout.LayoutParams(
                                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                                android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.view.Gravity.CENTER))
                        }
                    }, modifier = Modifier.fillMaxSize())
                }
            } } }
            compose.mainClock.advanceTimeBy(64)
            var minimum = androidx.compose.ui.unit.IntSize.Zero
            compose.runOnIdle {
                minimum = androidx.compose.ui.unit.IntSize(surface!!.width, surface!!.height)
                assertTrue("The embedded native surface must be laid out", minimum.width > 1 && minimum.height > 1)
            }
            // Player, content pull and fallback releases in both directions; the listener retains
            // transient native layouts even if the next frame repairs them.
            for (route in listOf("player", "content", "fallback")) for (enter in listOf(true, false)) {
                val distance = -120f * density
                val contentPull = route == "content" && enter
                compose.runOnIdle {
                    layouts.clear()
                    if (contentPull) handle!!.dragContentPull(-distance) else handle!!.drag(distance)
                }
                compose.mainClock.advanceTimeByFrame()
                if (contentPull || !enter) {
                    val held = compose.onNodeWithTag("player-window").fetchSemanticsNode().boundsInRoot
                    assertTrue("The container follows the finger between embedded and fullscreen bounds",
                        held.height > 180 * density && held.height < 600 * density)
                    compose.runOnIdle { assertEquals("Container drags do not zoom the picture", 1f, handle!!.pictureScale, 0f) }
                }
                compose.runOnIdle {
                    when {
                        contentPull -> assertTrue(handle!!.releaseContentPull(distance, 0f))
                        route == "fallback" -> handle!!.end(true)
                        else -> assertTrue(handle!!.release(distance, 0f))
                    }
                }
                repeat(24) { compose.mainClock.advanceTimeByFrame() }
                compose.runOnIdle {
                    assertEquals(enter, fullscreen)
                    assertEquals("Content pulls and player swipes must use distinct commit callbacks",
                        if (contentPull) "content" else "player", commits.last())
                    assertEquals("Each release commits once", 1, commits.size)
                    commits.clear()
                    val observed = layouts + androidx.compose.ui.unit.IntSize(surface!!.width, surface!!.height)
                    assertTrue("Fullscreen release must never measure a tiny native output: route=$route enter=$enter layouts=$observed",
                        observed.all { it.width >= minimum.width - 1 && it.height >= minimum.height - 1 })
                    assertEquals("Swipes must retain the SurfaceView", 1, creations)
                    assertEquals(640, surface!!.holder.surfaceFrame.width())
                    assertEquals(360, surface!!.holder.surfaceFrame.height())
                }
            }
            compose.runOnIdle { handle!!.dragContentPull(40f * density) }
            compose.mainClock.advanceTimeByFrame()
            assertTrue(compose.onNodeWithTag("player-window").fetchSemanticsNode().boundsInRoot.height > 180 * density)
            compose.runOnIdle { assertTrue(handle!!.releaseContentPull(-40f * density, 0f)) }
            compose.mainClock.advanceTimeBy(400)
            compose.runOnIdle { assertFalse(fullscreen); assertTrue("Cancelled pulls never commit", commits.isEmpty()) }
            assertEquals(180 * density, compose.onNodeWithTag("player-window").fetchSemanticsNode().boundsInRoot.height, 2f)
            compose.runOnIdle { fullscreen = true }
            compose.mainClock.advanceTimeBy(400)
            compose.runOnIdle { handle!!.drag(120f * density); assertTrue(handle!!.release(120f * density, 0f)) }
            compose.mainClock.advanceTimeBy(400)
            compose.runOnIdle {
                assertFalse(fullscreen)
                assertEquals("Downward exit has a separate restore route", listOf("restore"), commits)
            }
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun regrabbingAnEnteringContainerKeepsItsFrameAndReversingPastTheOriginCancels() {
        var fullscreen by mutableStateOf(false)
        var density = 1f
        var handle: FullscreenSwipeHandle? = null
        var commits = 0
        val store = MiniPlayerStore(PreferenceStore.of(compose.activity, "fullscreen-regrab").apply { clearAll() })
        compose.mainClock.autoAdvance = false
        try {
            compose.setContent { AppTheme { CompositionLocalProvider(LocalMotionScale provides 1f) {
                density = LocalDensity.current.density
                MiniPlayerWindow(320, 0, store, false, {}, mini = false, fillsWindow = fullscreen,
                    embeddedTopDp = 60, embeddedHeightDp = 180,
                    onFullscreenSwipe = { fullscreen = it; commits++ },
                    onContentFullscreenPull = { fullscreen = it; commits++ },
                    modifier = Modifier.size(320.dp, 600.dp)) {
                    handle = LocalFullscreenSwipeHandle.current
                    Box(Modifier.fillMaxSize())
                }
            } } }
            compose.mainClock.advanceTimeBy(64)
            compose.runOnIdle {
                handle!!.dragContentPull(120f * density)
                assertTrue(handle!!.releaseContentPull(-120f * density, -600f * density))
            }
            compose.mainClock.advanceTimeBy(64)
            val before = compose.onNodeWithTag("player-window").fetchSemanticsNode().boundsInRoot
            compose.runOnIdle { handle!!.drag(-20f * density) }
            compose.mainClock.advanceTimeByFrame()
            val grabbed = compose.onNodeWithTag("player-window").fetchSemanticsNode().boundsInRoot
            assertTrue("Regrabbing must not jump to the fullscreen endpoint", grabbed.height <= before.height + 2f)
            assertTrue("A small drag must continue from the displayed rectangle", grabbed.height >= before.height - 16f * density)
            compose.runOnIdle {
                handle!!.drag(140f * density)
                assertTrue(handle!!.release(140f * density, 0f))
            }
            compose.mainClock.advanceTimeBy(400)
            compose.runOnIdle {
                assertTrue("Crossing back past the drag origin must not exit fullscreen", fullscreen)
                assertEquals(1, commits)
            }
            assertEquals(600f * density, compose.onNodeWithTag("player-window").fetchSemanticsNode().boundsInRoot.height, 2f)
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun cardEntrySlotUpdatesDoNotExtendTheLandingClock() {
        var top by mutableIntStateOf(60)
        var progress = 0f
        val store = MiniPlayerStore(PreferenceStore.of(compose.activity, "card-insets-motion").apply { clearAll() })
        compose.mainClock.autoAdvance = false
        compose.setContent { AppTheme { CompositionLocalProvider(LocalMotionScale provides 1f) {
            MiniPlayerWindow(320, 0, store, false, {}, mini = false,
                embeddedTopDp = top, embeddedHeightDp = 180, initialBounds = Rect(24f, 120f, 240f, 242f),
                onEntryProgress = { progress = it }, modifier = Modifier.size(320.dp, 600.dp)) {
                Box(Modifier.fillMaxSize())
            }
        } } }
        compose.mainClock.advanceTimeBy(64)
        repeat(6) {
            compose.runOnIdle { top++ }
            compose.mainClock.advanceTimeBy(16)
        }
        compose.mainClock.advanceTimeBy(224)
        compose.runOnIdle { assertEquals("Slot updates must not prolong card entry", 1f, progress, 0f) }
        compose.mainClock.autoAdvance = true
    }

    @Test fun fullscreenExitKeepsItsClockAndVideoBufferThroughInsetUpdates() {
        var fullscreen by mutableStateOf(true)
        var height by mutableStateOf(600.dp)
        var top by mutableIntStateOf(60)
        var density = 1f
        var surface: SurfaceView? = null
        val store = MiniPlayerStore(PreferenceStore.of(compose.activity, "fullscreen-insets-motion").apply { clearAll() })
        compose.mainClock.autoAdvance = false
        compose.setContent { AppTheme { CompositionLocalProvider(LocalMotionScale provides 1f) {
            density = LocalDensity.current.density
            MiniPlayerWindow(320, 0, store, false, {}, mini = false, fillsWindow = fullscreen,
                embeddedTopDp = top, embeddedHeightDp = 180, modifier = Modifier.size(320.dp, height)) {
                Box(Modifier.fillMaxSize()) {
                    AndroidView(factory = { context -> SurfaceView(context).also {
                        surface = it; it.holder.setFixedSize(640, 360)
                    } }, modifier = Modifier.fillMaxSize())
                }
            }
        } } }
        compose.mainClock.advanceTimeBy(32)
        compose.runOnIdle {
            assertEquals(640, surface!!.holder.surfaceFrame.width())
            fullscreen = false
        }
        compose.mainClock.advanceTimeBy(80)
        repeat(6) {
            compose.runOnIdle { top++; height += 1.dp }
            compose.mainClock.advanceTimeBy(16)
        }
        compose.runOnIdle {
            assertEquals("Insets must not resize the decoder buffer during flight", 640, surface!!.holder.surfaceFrame.width())
            assertEquals(360, surface!!.holder.surfaceFrame.height())
        }
        compose.mainClock.advanceTimeBy(128)
        val landed = compose.onNodeWithTag("player-window").fetchSemanticsNode().boundsInRoot
        assertEquals("Insets must not restart the 240 ms clock", 180 * density, landed.height, 2f)
        assertEquals(66 * density, landed.top, 2f)
        compose.mainClock.autoAdvance = true
    }

    private fun checkMotion(scale: Float) {
        var fullscreen by mutableStateOf(false)
        var height by mutableStateOf(600.dp)
        var surfaces = 0
        var density = 1f
        val progress = mutableListOf<Float>()
        val store = MiniPlayerStore(PreferenceStore.of(compose.activity, "fullscreen-button-motion").apply { clearAll() })
        compose.mainClock.autoAdvance = false
        compose.setContent { AppTheme { CompositionLocalProvider(LocalMotionScale provides scale) {
            density = LocalDensity.current.density
            MiniPlayerWindow(320, 0, store, false, {}, mini = false, fillsWindow = fullscreen,
                embeddedTopDp = 60, embeddedHeightDp = 180,
                onFullscreenProgress = { progress += it }, modifier = Modifier.size(320.dp, height)) {
                AndroidView(factory = { surfaces++; SurfaceView(it) }, modifier = Modifier.fillMaxSize())
            }
        } } }
        fun bounds(): Rect = compose.onNodeWithTag("player-window").fetchSemanticsNode().boundsInRoot
        compose.mainClock.advanceTimeBy(32)
        val embedded = bounds()
        compose.runOnIdle { fullscreen = true }
        compose.mainClock.advanceTimeBy(64)
        val entering = bounds()
        if (scale > 0f) {
            assertTrue("Button entry must have intermediate picture bounds", entering.height > embedded.height && entering.height < 600 * density)
            compose.runOnIdle { height = 640.dp }
            compose.mainClock.advanceTimeBy(32)
            assertTrue("Inset changes must retarget the running animation", bounds().height < 640 * density)
        } else assertEquals(600 * density, entering.height, 2f)
        val beforeReverse = bounds()
        compose.runOnIdle { fullscreen = false }
        compose.mainClock.advanceTimeBy(48)
        val leaving = bounds()
        if (scale > 0f) assertTrue("Back must reverse from the current picture: $beforeReverse -> $leaving; embedded=$embedded", leaving.height < beforeReverse.height && leaving.height > embedded.height)
        compose.mainClock.advanceTimeBy(400)
        val restored = bounds()
        assertEquals(embedded.top, restored.top, 2f)
        assertEquals(embedded.height, restored.height, 2f)
        compose.runOnIdle { fullscreen = true }
        compose.mainClock.advanceTimeBy(400)
        assertEquals(if (scale > 0f) 640 * density else 600 * density, bounds().height, 2f)
        compose.runOnIdle { assertEquals(1f, progress.last(), 0.001f); fullscreen = false }
        compose.mainClock.advanceTimeBy(400)
        assertEquals(embedded.height, bounds().height, 2f)
        compose.runOnIdle {
            assertEquals("Mode changes retain the decoder's surface", 1, surfaces)
            assertEquals(0f, progress.last(), 0.001f)
            if (scale > 0f) assertTrue(progress.any { it > 0f && it < 1f })
            else assertTrue(progress.all { it == 0f || it == 1f })
        }
        compose.mainClock.autoAdvance = true
    }
}
