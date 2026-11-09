@file:OptIn(UnstableApi::class)
package com.hhst.youtubelite.player.engine

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.hhst.youtubelite.core.DeviceEvidence
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Vertical fullscreen swipe via real motion events against a real WatchActivity: the same SurfaceView
 * and prepared media survive both ways, the picture follows the finger, and a reversed or short drag cancels.
 */
class WatchFullscreenSwipeAndroidTest {

    private fun windowBounds(fixture: OfflineWatchFixture): androidx.compose.ui.geometry.Rect {
        fun node(root: SemanticsNode): SemanticsNode? =
            if (root.config.getOrNull(SemanticsProperties.TestTag) == "player-window") root
            else root.children.firstNotNullOfOrNull(::node)
        fun find(view: android.view.View): SemanticsNode? {
            val owner = runCatching { view.javaClass.getMethod("getSemanticsOwner").invoke(view) as SemanticsOwner }.getOrNull()
            owner?.let { node(it.rootSemanticsNode) }?.let { return it }
            if (view is android.view.ViewGroup) for (i in 0 until view.childCount) {
                find(view.getChildAt(i))?.let { return it }
            }
            return null
        }
        var bounds: androidx.compose.ui.geometry.Rect? = null
        val read = { bounds = find(fixture.activity.window.decorView)?.boundsInWindow }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) read()
        else fixture.instrumentation.runOnMainSync { read() }
        return requireNotNull(bounds) { "Player window semantics are unavailable" }
    }

    private fun surfaceBounds(fixture: OfflineWatchFixture): Rect? {
        val view = fixture.surface() ?: return null
        val at = IntArray(2)
        view.getLocationOnScreen(at)
        return Rect(at[0], at[1], at[0] + view.width, at[1] + view.height)
    }

    private fun swipe(fixture: OfflineWatchFixture, fromX: Float, fromY: Float, toY: Float, steps: Int, stepMs: Long, holdMs: Long = 0) {
        val downTime = SystemClock.uptimeMillis()
        fixture.instrumentation.sendPointerSync(
            MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_DOWN, fromX, fromY, 0))
        for (i in 1..steps) {
            val y = fromY + (toY - fromY) * i / steps
            fixture.instrumentation.sendPointerSync(
                MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, fromX, y, 0))
            Thread.sleep(stepMs)
        }
        if (holdMs > 0) Thread.sleep(holdMs)
        fixture.instrumentation.sendPointerSync(
            MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, fromX, toY, 0))
    }

    private fun hideChrome(fixture: OfflineWatchFixture) {
        fixture.instrumentation.runOnMainSync { fixture.model.onHideControls() }
        fixture.await { !fixture.model.uiState.value.controlsVisible }
        Thread.sleep(400) // Let the fade-out remove the chrome from the hit path.
    }

    private fun shell(command: String): String {
        val fd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes().decodeToString() }
    }

    /** Auto-rotate would let the emulator's flat orientation sensor auto-enter fullscreen. */
    private fun <T> withAutoRotateOff(block: () -> T): T {
        val previous = shell("settings get system accelerometer_rotation").trim().ifBlank { "1" }
        val confirmation = shell("settings get secure immersive_mode_confirmations").trim()
        shell("settings put system accelerometer_rotation 0")
        shell("settings put secure immersive_mode_confirmations confirmed")
        try {
            return block()
        } finally {
            shell("settings put system accelerometer_rotation $previous")
            shell(if (confirmation == "null" || confirmation.isEmpty()) "settings delete secure immersive_mode_confirmations"
                else "settings put secure immersive_mode_confirmations $confirmation")
        }
    }

    @Test fun swipeFromRealWatchInterpolatesCommitsCancelsAndSurvivesRotation() = withAutoRotateOff {
        val metrics = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics
        assumeTrue(
            "Requires the phone-aspect host (wm size 1080x2400); the 1600x1200 " +
                "matrix host letterboxes landscape fullscreen by design",
            metrics.heightPixels.toFloat() / metrics.widthPixels >= 16f / 9f - 0.02f,
        )
        OfflineWatchFixture("swipeFs0001").use { fixture ->
            fixture.launch()
            val activity = fixture.activity
            val engine = fixture.engine
            val model = fixture.model

            fixture.instrumentation.runOnMainSync { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            fixture.await { activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
            fixture.await { val frame = windowBounds(fixture); abs(frame.height - frame.width * 9f / 16f) < 4f }
            val embeddedWindow = windowBounds(fixture)
            val embeddedSurface = fixture.surface()!!
            val embeddedBuffer = Rect(embeddedSurface.holder.surfaceFrame)
            val embedded = surfaceBounds(fixture)!!
            val preparesAtStart = engine.mediaPrepareCount
            val positionAtStart = model.positionState.value

            // Chrome (center controls, time bar) covers the vertical middle of an embedded 16:9 player,
            // so it is hidden first; swiping with hidden chrome is the primary real-world path.
            hideChrome(fixture)
            val startX = embedded.centerX().toFloat()
            val startY = embedded.centerY().toFloat()
            val downTime = SystemClock.uptimeMillis()
            fixture.instrumentation.sendPointerSync(
                MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_DOWN, startX, startY, 0))
            val heldY = startY - 400f
            for (i in 1..10) {
                val y = startY + (heldY - startY) * i / 10
                fixture.instrumentation.sendPointerSync(
                    MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, startX, y, 0))
                Thread.sleep(40)
            }
            val midBounds = windowBounds(fixture)
            assertEquals("Embedded upward drag keeps the player frame height", embeddedWindow.height, midBounds.height, 2f)
            assertEquals("Embedded upward drag keeps the player frame width", embeddedWindow.width, midBounds.width, 2f)
            assertEquals("Embedded upward drag keeps the player frame top", embeddedWindow.top, midBounds.top, 2f)
            assertEquals("Picture zoom must retain the decoder buffer", embeddedBuffer, embeddedSurface.holder.surfaceFrame)
            assertFalse("Not fullscreen yet mid-drag", model.uiState.value.fullscreen)
            assertSame(embeddedSurface, fixture.surface())
            DeviceEvidence.captureScene("watch-swipe-mid-drag")
            fixture.instrumentation.sendPointerSync(
                MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, startX, heldY, 0))
            fixture.await { model.uiState.value.fullscreen }
            assertFalse("Player swipe uses the fullscreen button's orientation policy", model.uiState.value.fullscreenKeepsOrientation)
            // The player swipe requests landscape just like the button, even with auto-rotate off.
            fixture.await { activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
            fixture.await {
                val frame = windowBounds(fixture)
                model.uiState.value.fullscreen &&
                    abs((surfaceBounds(fixture)?.height() ?: 0) - activity.resources.displayMetrics.heightPixels) < 20 &&
                    abs(frame.width - activity.resources.displayMetrics.widthPixels) < 20 &&
                    abs(frame.height - activity.resources.displayMetrics.heightPixels) < 20
            }
            assertSame("Fullscreen must reuse the embedded SurfaceView", embeddedSurface, fixture.surface())
            assertEquals("Fullscreen must retain the decoder buffer", embeddedBuffer, embeddedSurface.holder.surfaceFrame)
            assertEquals("A swipe transition must not re-prepare media", preparesAtStart, engine.mediaPrepareCount)
            assertTrue(model.uiState.value.isPlaying)
            val landscape = surfaceBounds(fixture)!!
            val positionAfterRotation = model.positionState.value
            assertTrue("Position advances through rotation", positionAfterRotation >= positionAtStart)

            hideChrome(fixture)
            val cancelStartY = landscape.centerY().toFloat()
            val cancelX = landscape.centerX().toFloat()
            val cancelDown = SystemClock.uptimeMillis()
            fixture.instrumentation.sendPointerSync(
                MotionEvent.obtain(cancelDown, SystemClock.uptimeMillis(), MotionEvent.ACTION_DOWN, cancelX, cancelStartY, 0))
            val cancelLow = cancelStartY + landscape.height() * 0.1f
            val points = (1..6).map { cancelStartY + (cancelLow - cancelStartY) * it / 6 } +
                (1..6).map { cancelLow - (cancelLow - cancelStartY) * it / 6 }
            for (y in points) {
                fixture.instrumentation.sendPointerSync(
                    MotionEvent.obtain(cancelDown, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, cancelX, y, 0))
                Thread.sleep(40)
            }
            fixture.instrumentation.sendPointerSync(
                MotionEvent.obtain(cancelDown, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, cancelX, cancelStartY, 0))
            Thread.sleep(600)
            fixture.await { model.uiState.value.fullscreen }
            assertSame(embeddedSurface, fixture.surface())
            assertEquals(preparesAtStart, engine.mediaPrepareCount)

            hideChrome(fixture)
            DeviceEvidence.captureScene("watch-fullscreen-before-exit")
            DeviceEvidence.writeJson("watch-fullscreen-exit-geometry.json", com.hhst.youtubelite.core.TestJson.any(mapOf(
                "surface" to surfaceBounds(fixture).toString(), "previousSurface" to landscape.toString(),
                "frame" to windowBounds(fixture).toString(), "x" to cancelX, "y" to cancelStartY,
                "width" to activity.resources.displayMetrics.widthPixels, "height" to activity.resources.displayMetrics.heightPixels,
            )))
            swipe(fixture, cancelX, cancelStartY, cancelStartY - landscape.height() * 0.25f, 12, 40)
            fixture.await { !model.uiState.value.fullscreen }
            fixture.await { activity.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_LOCKED }
            assertTrue("Upward exit keeps the current orientation", model.uiState.value.fullscreenKeepsOrientation)
            assertEquals(Configuration.ORIENTATION_LANDSCAPE, activity.resources.configuration.orientation)
            fixture.await {
                val bounds = surfaceBounds(fixture)
                bounds != null && kotlin.math.abs(bounds.height() - (bounds.width() * 9 / 16)) < 40
            }
            assertSame(embeddedSurface, fixture.surface())
            assertEquals("Exit must not re-prepare either", preparesAtStart, engine.mediaPrepareCount)
            val positionAtEnd = model.positionState.value
            assertTrue("Playback position kept running", positionAtEnd > positionAfterRotation)
            DeviceEvidence.captureScene("watch-swipe-embedded-restored")
        }
    }

    @Test fun shortOffCenterUpwardSwipeEntersAndDownwardExitRestoresPortrait() = withAutoRotateOff {
        OfflineWatchFixture("swipeEase01").use { fixture ->
            fixture.launch()
            val activity = fixture.activity
            fixture.instrumentation.runOnMainSync { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            fixture.await { activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
            fixture.await { val frame = windowBounds(fixture); abs(frame.height - frame.width * 9f / 16f) < 4f }
            val embedded = windowBounds(fixture)
            val surface = fixture.surface()
            val prepares = fixture.engine.mediaPrepareCount
            hideChrome(fixture)
            val x = embedded.left + embedded.width * .25f
            swipe(fixture, x, embedded.center.y, embedded.center.y - 60f * activity.resources.displayMetrics.density, 8, 25)
            fixture.await { fixture.model.uiState.value.fullscreen }
            fixture.await { activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
            fixture.await { abs(windowBounds(fixture).height - activity.window.decorView.height) < 4f }
            hideChrome(fixture)
            val full = windowBounds(fixture)
            swipe(fixture, full.center.x, full.center.y, full.center.y + full.height * .3f, 12, 30)
            fixture.await { !fixture.model.uiState.value.fullscreen && activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
            fixture.await { abs(windowBounds(fixture).height - embedded.height) < 4f }
            assertFalse(fixture.model.uiState.value.fullscreenKeepsOrientation)
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, activity.requestedOrientation)
            assertSame(surface, fixture.surface())
            assertEquals(prepares, fixture.engine.mediaPrepareCount)
        }
    }

    @Test fun belowThresholdDragBouncesBackToEmbedded() = withAutoRotateOff {
        OfflineWatchFixture("swipeFs0002").use { fixture ->
            fixture.launch()
            val surface = fixture.surface()!!
            val embedded = surfaceBounds(fixture)!!
            val prepares = fixture.engine.mediaPrepareCount
            hideChrome(fixture)
            val startX = embedded.centerX().toFloat()
            val startY = embedded.centerY().toFloat()
            swipe(fixture, startX, startY, startY - 20f * fixture.activity.resources.displayMetrics.density, 8, 60)
            Thread.sleep(600)
            fixture.await { !fixture.model.uiState.value.fullscreen }
            assertEquals(embedded, surfaceBounds(fixture))
            assertSame(surface, fixture.surface())
            assertEquals(prepares, fixture.engine.mediaPrepareCount)
        }
    }

    @Test fun centerDownwardDragReturnsToTheSourceAndRestoresWithoutPreparingAgain() = withAutoRotateOff {
        OfflineWatchFixture("swipeMini01").use { fixture ->
            val sourceIntent = android.content.Intent(fixture.app, com.hhst.youtubelite.home.HomeActivity::class.java)
                .putExtra("playback_test", true)
            androidx.test.core.app.ActivityScenario.launch<com.hhst.youtubelite.home.HomeActivity>(sourceIntent).use { source ->
                lateinit var sourceActivity: com.hhst.youtubelite.home.HomeActivity
                source.onActivity { sourceActivity = it }
                fixture.launch(source = sourceActivity)
                val model = fixture.model
                if (!model.uiState.value.controlsVisible) {
                    fixture.instrumentation.runOnMainSync { model.onToggleControls() }
                }
                val originalHost = fixture.activity
                val originalSurface = fixture.surface()
                fixture.await {
                    val frame = windowBounds(fixture)
                    val surface = surfaceBounds(fixture)
                    surface != null && abs(frame.width - surface.width()) < 2 &&
                        abs(frame.height - surface.height()) < 2
                }
                Thread.sleep(300)
                val embedded = surfaceBounds(fixture)!!
                val embeddedWindow = windowBounds(fixture)
                val prepares = fixture.engine.mediaPrepareCount
                val startX = embedded.centerX().toFloat()
                val startY = embedded.centerY().toFloat()
                val endY = startY + fixture.activity.resources.displayMetrics.heightPixels * 0.20f
                val downTime = SystemClock.uptimeMillis()
                var halfwayWidth = embedded.width()
                var halfwayCenterY = embedded.exactCenterY()
                fixture.instrumentation.sendPointerSync(MotionEvent.obtain(
                    downTime, downTime, MotionEvent.ACTION_DOWN, startX, startY, 0))
                for (i in 1..12) {
                    fixture.instrumentation.sendPointerSync(MotionEvent.obtain(downTime, SystemClock.uptimeMillis(),
                        MotionEvent.ACTION_MOVE, startX, startY + (endY - startY) * i / 12, 0))
                    Thread.sleep(40)
                    if (i == 6) {
                        val frame = windowBounds(fixture)
                        halfwayWidth = frame.width.toInt()
                        halfwayCenterY = frame.center.y
                    }
                }
                val held = windowBounds(fixture)
                assertFalse(model.uiState.value.mini)
                assertTrue("The frame shrinks during the drag", halfwayWidth < embedded.width())
                assertTrue("Shrinking continues with finger travel", held.width < halfwayWidth)
                DeviceEvidence.captureScene("watch-mini-held")
                val fingerTravel = endY - startY
                val pictureTravel = held.center.y - embeddedWindow.center.y
                assertTrue("The picture progressively follows the drag", held.center.y > halfwayCenterY)
                assertEquals("The picture stays under the finger", fingerTravel, pictureTravel, 2f)
                assertSame(originalSurface, fixture.surface())
                assertEquals(prepares, fixture.engine.mediaPrepareCount)
                assertTrue(held.top > embedded.top)
                fixture.instrumentation.sendPointerSync(MotionEvent.obtain(
                    downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, startX, endY, 0))
                fixture.await { model.uiState.value.mini && originalHost.isFinishing && sourceActivity.hasWindowFocus() }
                fun sourceSurface(view: android.view.View): SurfaceView? {
                    if (view is SurfaceView && view.isShown) return view
                    if (view is android.view.ViewGroup) for (index in 0 until view.childCount) {
                        sourceSurface(view.getChildAt(index))?.let { return it }
                    }
                    return null
                }
                fixture.await { sourceSurface(sourceActivity.window.decorView) != null }
                assertTrue(model.uiState.value.isPlaying)
                assertEquals(prepares, fixture.engine.mediaPrepareCount)
                val monitor = fixture.instrumentation.addMonitor(com.hhst.youtubelite.watch.WatchActivity::class.java.name, null, false)
                try {
                    fixture.instrumentation.runOnMainSync { model.onMiniRestore() }
                    fixture.activity = requireNotNull(monitor.waitForActivityWithTimeout(10_000)) as com.hhst.youtubelite.watch.WatchActivity
                } finally { fixture.instrumentation.removeMonitor(monitor) }
                fixture.await { !model.uiState.value.mini && surfaceBounds(fixture) == embedded }
                assertTrue(model.uiState.value.isPlaying)
                assertEquals(prepares, fixture.engine.mediaPrepareCount)
            }
        }
    }

    @Test fun restoredWatchRevealsTheCurrentBrowsePageAndKeepsItsVideoBufferThroughSettle() = withAutoRotateOff {
        OfflineWatchFixture("miniRoute01").use { fixture ->
            val homeIntent = android.content.Intent(fixture.app, com.hhst.youtubelite.home.HomeActivity::class.java)
                .putExtra("playback_test", true)
            androidx.test.core.app.ActivityScenario.launch<com.hhst.youtubelite.home.HomeActivity>(homeIntent).use { home ->
                lateinit var homeActivity: com.hhst.youtubelite.home.HomeActivity
                home.onActivity { homeActivity = it }
                fixture.launch(source = homeActivity)
                val watch = fixture.activity
                val prepares = fixture.engine.mediaPrepareCount
                val monitor = fixture.instrumentation.addMonitor(
                    com.hhst.youtubelite.search.SearchResultsActivity::class.java.name, null, false)
                val browse = try {
                    fixture.instrumentation.runOnMainSync {
                        watch.startActivity(com.hhst.youtubelite.search.SearchResultsActivity.intent(watch, "mini return")
                            .putExtra("playback_test", true))
                    }
                    requireNotNull(monitor.waitForActivityWithTimeout(10_000)) as com.hhst.youtubelite.search.SearchResultsActivity
                } finally { fixture.instrumentation.removeMonitor(monitor) }
                try {
                    fixture.await { browse.hasWindowFocus() && fixture.model.uiState.value.mini && fixture.engine.snapshot.value.firstFrameRendered }
                    // A distinctive browse background makes an old Home snapshot,
                    // a duplicate mini window, or a blank handoff observable.
                    val markerDrawn = java.util.concurrent.CountDownLatch(1)
                    fixture.instrumentation.runOnMainSync {
                        val content = browse.findViewById<android.view.ViewGroup>(android.R.id.content)
                        content.addView(object : android.view.View(browse) {
                            override fun onDraw(canvas: android.graphics.Canvas) {
                                super.onDraw(canvas)
                                markerDrawn.countDown()
                            }
                        }.apply { setBackgroundColor(android.graphics.Color.GREEN) },
                            content.childCount - 1, android.view.ViewGroup.LayoutParams(-1, -1))
                    }
                    assertTrue("The current browse backdrop must draw before restoring Watch",
                        markerDrawn.await(25, java.util.concurrent.TimeUnit.SECONDS))
                    fixture.instrumentation.runOnMainSync { fixture.model.onMiniRestore() }
                    fixture.await { watch.hasWindowFocus() && !fixture.model.uiState.value.mini && fixture.engine.snapshot.value.firstFrameRendered }
                    assertSame("Restore reuses the existing WatchActivity below browse", watch, fixture.activity)
                    val backdrop = requireNotNull(com.hhst.youtubelite.navigation.WatchTransition.backdrop)
                    assertEquals(android.graphics.Color.GREEN, backdrop.getPixel(backdrop.width / 2, backdrop.height / 2))
                    Thread.sleep(350)
                    hideChrome(fixture)
                    val originalSurface = requireNotNull(fixture.surface())
                    val bufferSize = Rect(originalSurface.holder.surfaceFrame)
                    assertTrue("The decoded buffer must be ready before dragging", bufferSize.width() > 0 && bufferSize.height() > 0)
                    val embedded = windowBounds(fixture)
                    val x = embedded.center.x
                    val y = embedded.center.y
                    val endY = y + watch.resources.displayMetrics.heightPixels * 0.20f
                    val down = SystemClock.uptimeMillis()
                    fixture.instrumentation.sendPointerSync(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0))
                    for (i in 1..12) {
                        fixture.instrumentation.sendPointerSync(MotionEvent.obtain(down, SystemClock.uptimeMillis(),
                            MotionEvent.ACTION_MOVE, x, y + (endY - y) * i / 12, 0))
                        Thread.sleep(40)
                    }
                    val screenshot = fixture.instrumentation.uiAutomation.takeScreenshot()
                    val backgroundPixel = screenshot.getPixel(20, screenshot.height / 2)
                    assertTrue("Drag must reveal the current green browse page", android.graphics.Color.green(backgroundPixel) > 150)
                    screenshot.recycle()
                    assertSame(originalSurface, fixture.surface())
                    assertEquals("Dragging must preserve the video buffer size", bufferSize, Rect(originalSurface.holder.surfaceFrame))
                    DeviceEvidence.captureScene("mini-current-browse-held")
                    fixture.instrumentation.sendPointerSync(MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, endY, 0))
                    fixture.await { watch.isFinishing }
                    repeat(5) {
                        val bounds = requireNotNull(com.hhst.youtubelite.navigation.WatchTransition.miniBounds)
                        val screen = fixture.instrumentation.uiAutomation.takeScreenshot()
                        java.io.File(fixture.app.getExternalFilesDir(null), "mini-handoff-$it.png").outputStream().use {
                            screen.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                        }
                        val pixel = screen.getPixel((bounds.left + bounds.width * .25f).toInt(),
                            (bounds.top + bounds.height * .45f).toInt())
                        screen.recycle()
                        assertTrue("The blue decoded picture must cover the activity handoff (sample=$it, pixel=$pixel)",
                            android.graphics.Color.blue(pixel) > 60 && android.graphics.Color.green(pixel) < 40)
                        Thread.sleep(20)
                    }
                    fixture.await { watch.isFinishing && browse.hasWindowFocus() && fixture.engine.snapshot.value.firstFrameRendered }
                    val frame = requireNotNull(com.hhst.youtubelite.navigation.WatchTransition.videoFrame)
                    assertEquals(fixture.id, frame.videoId)
                    assertEquals("Settling must preserve the source buffer until handoff", bufferSize,
                        frame.source.get()?.holder?.surfaceFrame?.let { Rect(it) })
                    assertEquals(prepares, fixture.engine.mediaPrepareCount)
                    assertTrue(fixture.model.uiState.value.isPlaying)
                    DeviceEvidence.captureScene("mini-current-browse-settled")
                } finally {
                    fixture.instrumentation.runOnMainSync { browse.finish() }
                }
            }
        }
    }

    @Test fun cancelledDownwardDragReturnsToItsEmbeddedBounds() = withAutoRotateOff {
        OfflineWatchFixture("swipeMini02").use { fixture ->
            fixture.launch()
            hideChrome(fixture)
            val surface = fixture.surface()!!
            val embedded = surfaceBounds(fixture)!!
            val prepares = fixture.engine.mediaPrepareCount
            val x = embedded.centerX().toFloat()
            val startY = embedded.centerY().toFloat()
            val downTime = SystemClock.uptimeMillis()
            fixture.instrumentation.sendPointerSync(MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, startY, 0))
            for (i in 1..8) {
                fixture.instrumentation.sendPointerSync(MotionEvent.obtain(downTime, SystemClock.uptimeMillis(),
                    MotionEvent.ACTION_MOVE, x, startY + fixture.activity.resources.displayMetrics.heightPixels * 0.10f * i / 8, 0))
                Thread.sleep(40)
            }
            fixture.instrumentation.sendPointerSync(MotionEvent.obtain(
                downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, x, startY, 0))
            fixture.await { !fixture.model.uiState.value.mini && surfaceBounds(fixture) == embedded }
            assertSame(surface, fixture.surface())
            assertEquals(prepares, fixture.engine.mediaPrepareCount)
            assertTrue(fixture.model.uiState.value.isPlaying)
        }
    }
    @Test fun pullingBelowThePlayerEntersFullscreenAndAnUpwardPlayerSwipeExits() = withAutoRotateOff {
        OfflineWatchFixture("pullVideo01").use { fixture ->
            fixture.launch()
            fixture.instrumentation.runOnMainSync { fixture.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            fixture.await { fixture.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
            Thread.sleep(500)
            hideChrome(fixture)
            fixture.instrumentation.runOnMainSync { fixture.model.setFullscreenManual(false) }
            fixture.await { val frame = windowBounds(fixture); !fixture.model.uiState.value.fullscreen && abs(frame.height - frame.width * 9f / 16f) < 4f }
            val surface = fixture.surface()!!
            val embedded = surfaceBounds(fixture)!!
            val prepares = fixture.engine.mediaPrepareCount
            val orientation = fixture.activity.resources.configuration.orientation
            val embeddedWindow = windowBounds(fixture)
            val windowHeight = fixture.activity.resources.displayMetrics.heightPixels.toFloat()
            val x = embedded.centerX().toFloat()
            val startY = embedded.bottom + 64f
            val down = SystemClock.uptimeMillis()
            fixture.instrumentation.sendPointerSync(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, startY, 0))
            val endY = (startY + windowHeight * .22f).coerceAtMost(windowHeight - 80f)
            for (i in 1..12) {
                fixture.instrumentation.sendPointerSync(MotionEvent.obtain(down, SystemClock.uptimeMillis(),
                    MotionEvent.ACTION_MOVE, x, startY + (endY - startY) * i / 12, 0))
                Thread.sleep(35)
            }
            val held = windowBounds(fixture)
            assertTrue("Content pull expands the container with the finger", held.height > embeddedWindow.height)
            assertTrue("Content pull has not reached fullscreen mid-drag", held.height < windowHeight)
            assertTrue("The container top moves toward the fullscreen edge", held.top <= embeddedWindow.top)
            assertFalse(fixture.model.uiState.value.fullscreen)
            fixture.instrumentation.sendPointerSync(MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, endY, 0))
            fixture.await { fixture.model.uiState.value.fullscreen }
            assertTrue("Content pull preserves orientation independently of player swipes", fixture.model.uiState.value.fullscreenKeepsOrientation)
            fixture.await { fixture.activity.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_LOCKED }
            fixture.await { abs(windowBounds(fixture).height - fixture.activity.window.decorView.height) < 4f }
            Thread.sleep(500)
            assertEquals("Pull preserves the current device orientation", orientation, fixture.activity.resources.configuration.orientation)
            assertSame(surface, fixture.surface())
            assertEquals(prepares, fixture.engine.mediaPrepareCount)
            hideChrome(fixture)
            val full = surfaceBounds(fixture)!!
            swipe(fixture, full.centerX().toFloat(), full.centerY().toFloat(),
                full.centerY() - windowHeight * .22f, 12, 35)
            fixture.await { !fixture.model.uiState.value.fullscreen }
            fixture.await { abs(windowBounds(fixture).height - embedded.height()) < 4f }
            assertEquals("Upward exit preserves the current direction", orientation, fixture.activity.resources.configuration.orientation)
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_LOCKED, fixture.activity.requestedOrientation)
            assertSame(surface, fixture.surface())
            assertEquals(prepares, fixture.engine.mediaPrepareCount)
            DeviceEvidence.captureScene("watch-content-pull-restored")
        }
    }

    @Test fun aShortContentPullReturnsSmoothlyWithoutEnteringFullscreen() = withAutoRotateOff {
        OfflineWatchFixture("pullVideo02").use { fixture ->
            fixture.launch()
            fixture.instrumentation.runOnMainSync { fixture.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            fixture.await { fixture.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
            Thread.sleep(500)
            fixture.instrumentation.runOnMainSync { fixture.model.setFullscreenManual(false) }
            fixture.await { val frame = windowBounds(fixture); !fixture.model.uiState.value.fullscreen && abs(frame.height - frame.width * 9f / 16f) < 4f }
            val embedded = windowBounds(fixture)
            val x = embedded.center.x
            val y = embedded.bottom + 64f
            swipe(fixture, x, y, y + fixture.activity.resources.displayMetrics.heightPixels * .04f, 10, 70)
            fixture.await { !fixture.model.uiState.value.fullscreen && abs(windowBounds(fixture).height - embedded.height) < 4f }
            assertFalse(fixture.model.uiState.value.mini)
        }
    }

    @Test fun tinyFastFlicksDoNotEnterExitOrMinimize() = withAutoRotateOff {
        OfflineWatchFixture("pullVideo03").use { fixture ->
            fixture.launch()
            fixture.instrumentation.runOnMainSync { fixture.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            fixture.await { fixture.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
            fixture.instrumentation.runOnMainSync { fixture.model.setFullscreenManual(false) }
            fixture.await { val frame = windowBounds(fixture); !fixture.model.uiState.value.fullscreen && abs(frame.height - frame.width * 9f / 16f) < 4f }
            hideChrome(fixture)
            val embedded = windowBounds(fixture)
            val tiny = 18f * fixture.activity.resources.displayMetrics.density
            for (direction in listOf(-1f, 1f)) {
                swipe(fixture, embedded.center.x, embedded.center.y, embedded.center.y + direction * tiny, 4, 8)
                Thread.sleep(500)
                assertFalse(fixture.model.uiState.value.fullscreen)
                assertFalse(fixture.model.uiState.value.mini)
                fixture.await { abs(windowBounds(fixture).height - embedded.height) < 4f }
            }
            swipe(fixture, embedded.center.x, embedded.center.y,
                embedded.center.y - 28f * fixture.activity.resources.displayMetrics.density, 4, 8, holdMs = 350)
            Thread.sleep(500)
            assertFalse("Holding after a quick drag must discard its old fling speed", fixture.model.uiState.value.fullscreen)
            fixture.await { abs(windowBounds(fixture).height - embedded.height) < 4f }
            val y = embedded.bottom + 64f
            swipe(fixture, embedded.center.x, y, y + tiny, 4, 8)
            Thread.sleep(500)
            assertFalse(fixture.model.uiState.value.fullscreen)
            fixture.instrumentation.runOnMainSync { fixture.model.setFullscreenManual(true) }
            fixture.await { fixture.model.uiState.value.fullscreen && abs(windowBounds(fixture).height - fixture.activity.window.decorView.height) < 4f }
            hideChrome(fixture)
            val full = windowBounds(fixture)
            swipe(fixture, full.center.x, full.center.y, full.center.y - tiny, 4, 8)
            Thread.sleep(500)
            assertTrue(fixture.model.uiState.value.fullscreen)
            fixture.await { abs(windowBounds(fixture).height - full.height) < 4f }
        }
    }

}
