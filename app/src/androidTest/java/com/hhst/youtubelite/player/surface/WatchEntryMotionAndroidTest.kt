package com.hhst.youtubelite.player.surface

import android.os.ParcelFileDescriptor
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.core.PreferenceStore
import com.hhst.youtubelite.ui.design.LocalMotionScale
import com.hhst.youtubelite.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Exercises the real destination clock, including its first layout and draw. */
class WatchEntryMotionAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun anEntranceWithoutThumbnailBoundsReportsReadyOnlyAfterContentDraws() {
        var targetHeight by mutableIntStateOf(0)
        var childDrawn = false
        var readyCalls = 0
        val store = MiniPlayerStore(PreferenceStore.of(compose.activity, "watch-entry-no-origin").apply { clearAll() })
        compose.setContent { AppTheme {
            MiniPlayerWindow(320, 0, store, false, {}, mini = false,
                embeddedHeightDp = targetHeight,
                onReadyForDisplay = {
                    assertTrue("Layout alone must not release the source backdrop", childDrawn)
                    readyCalls++
                }, modifier = Modifier.size(320.dp, 500.dp)) {
                Box(Modifier.fillMaxSize().drawWithContent { drawContent(); childDrawn = true })
            }
        } }
        compose.runOnIdle {
            assertEquals(0, readyCalls)
            targetHeight = 180
        }
        compose.onNodeWithTag("player-window").captureToImage()
        compose.runOnIdle { assertEquals(1, readyCalls); targetHeight = 190 }
        compose.onNodeWithTag("player-window").captureToImage()
        compose.runOnIdle { assertEquals("Inset changes must not restart the entrance gate", 1, readyCalls) }
    }

    @Test fun backDuringEntryNeverResumesThePageFadeIn() = withSystemScale("1") {
        var mini by mutableStateOf(false)
        var settled = false
        var surfaces = 0
        var latestEntry = 0f
        var firstExitEntry: Float? = null
        val exitEntries = mutableListOf<Float>()
        val exitFades = mutableListOf<Float>()
        val store = MiniPlayerStore(PreferenceStore.of(compose.activity, "watch-entry-reversal").apply { clearAll() })
        compose.setContent { AppTheme {
            CompositionLocalProvider(LocalMotionScale provides 1f) {
                val density = LocalDensity.current.density
                MiniPlayerWindow(320, 0, store, false, {}, mini = mini,
                    embeddedHeightDp = 180,
                    initialBounds = Rect(20 * density, 220 * density, 180 * density, 310 * density),
                    onEntryProgress = {
                        latestEntry = it
                        if (mini) exitEntries += it
                        else if (it > 0f && it < 0.8f) {
                            firstExitEntry = it
                            mini = true
                        }
                    },
                    onMiniDragProgress = { if (mini) exitFades += it },
                    onMiniSettled = { settled = true },
                    modifier = Modifier.size(320.dp, 500.dp)) {
                    AndroidView(factory = { context -> surfaces++; SurfaceView(context) }, modifier = Modifier.fillMaxSize())
                }
            }
        } }
        compose.waitUntil(5_000) { settled }
        compose.runOnIdle {
            val interrupted = requireNotNull(firstExitEntry)
            assertTrue("Back must interrupt an unfinished entry", interrupted > 0f && interrupted < 0.8f)
            assertTrue("The cancelled entry must not brighten the page during exit: $exitEntries",
                exitEntries.isNotEmpty() && exitEntries.all { it <= interrupted })
            assertEquals(interrupted, latestEntry, 0.001f)
            assertTrue("Exit must only fade out: $exitFades", exitFades.zipWithNext().all { (a, b) -> b >= a })
            assertEquals(1f, exitFades.last(), 0f)
            assertEquals(1, surfaces)
        }
    }

    @Test fun entryWaitsForRealBoundsAndVisibilityThenSharesOneContinuousClock() = withSystemScale("1") {
        var hostReady by mutableStateOf(false)
        var targetHeight by mutableIntStateOf(0)
        var parentHeight by mutableStateOf(500.dp)
        var density = 1f
        var hostPosition = Offset.Zero
        var reportedBounds = Rect.Zero
        var surfaces = 0
        var resizedDuringEntry = false
        val progress = mutableListOf<Float>()
        val intermediateBounds = mutableListOf<Rect>()
        compose.mainClock.autoAdvance = false
        val store = MiniPlayerStore(PreferenceStore.of(compose.activity, "watch-entry-motion").apply { clearAll() })
        compose.setContent { AppTheme {
            CompositionLocalProvider(LocalMotionScale provides 1f) {
                density = LocalDensity.current.density
                Box(Modifier.fillMaxSize().padding(start = 13.dp, top = 37.dp)) {
                    MiniPlayerWindow(320, 0, store, false, {}, mini = false,
                        embeddedTopDp = 24, embeddedHeightDp = targetHeight,
                        initialBounds = Rect(24 * density, 220 * density, 184 * density, 310 * density),
                        entryAnimationReady = hostReady,
                        onEntryProgress = {
                            progress += it
                            if (it > 0f && it < 1f && !resizedDuringEntry) {
                                resizedDuringEntry = true
                                parentHeight = 460.dp
                                targetHeight = 190
                            }
                        },
                        onBoundsChanged = {
                            reportedBounds = it
                            if (progress.lastOrNull()?.let { value -> value > 0f && value < 1f } == true) intermediateBounds += it
                        },
                        modifier = Modifier.size(320.dp, parentHeight)
                            .onGloballyPositioned { hostPosition = it.positionInWindow() }) {
                        AndroidView(factory = { context -> surfaces++; SurfaceView(context) }, modifier = Modifier.fillMaxSize())
                    }
                }
            }
        } }
        compose.mainClock.advanceTimeBy(500)
        compose.runOnIdle {
            assertTrue("A temporary zero-height player cannot complete the page reveal", progress.isNotEmpty() && progress.all { it == 0f })
            assertEquals(0, surfaces)
            targetHeight = 180
        }
        compose.mainClock.advanceTimeBy(500)
        compose.runOnIdle {
            assertTrue("The destination must keep its origin while its host is covered", progress.all { it == 0f })
            assertTrue("The test must include a nonzero destination offset", hostPosition.x > 0f && hostPosition.y > 0f)
            assertEquals(24 * density, reportedBounds.left, 2f)
            assertEquals(220 * density, reportedBounds.top, 2f)
            assertEquals(160 * density, reportedBounds.width, 2f)
            assertEquals(1, surfaces)
        }
        // The manual Compose clock does not drive Android's draw pass: capture the displayed origin
        // before releasing its first-draw gate, or virtual time can elapse while it is still waiting.
        val originImage = compose.onNodeWithTag("player-window").captureToImage()
        assertEquals(160 * density, originImage.width.toFloat(), 2f)
        compose.runOnIdle { hostReady = true }
        compose.mainClock.autoAdvance = true
        // First-draw/snapshot observers and the animation share the test scheduler; the callback changes the target once mid-flight.
        compose.waitUntil(5_000) {
            progress.lastOrNull() == 1f
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1f, progress.last(), 0f)
            assertTrue("The page must receive actual intermediate progress, not stay hidden until the end", progress.any { it > 0f && it < 1f })
            assertTrue("The test must resize the destination during its entry", resizedDuringEntry)
            assertTrue(progress.zipWithNext().all { (before, after) -> after >= before })
            assertTrue("The video must visibly travel through intermediate bounds", intermediateBounds.any { it.width > 160 * density + 2f && it.width < 320 * density - 2f })
            assertEquals(320 * density, reportedBounds.width, 2f)
            assertEquals(190 * density, reportedBounds.height, 2f)
            assertEquals("Animating the window must preserve the same video surface", 1, surfaces)
        }
        compose.mainClock.autoAdvance = true
    }

    @Test fun disabledSystemMotionSnapsAfterTheOriginIsReady() = withSystemScale("0") {
        var hostReady by mutableStateOf(false)
        var density = 1f
        var reportedBounds = Rect.Zero
        val progress = mutableListOf<Float>()
        compose.mainClock.autoAdvance = false
        val store = MiniPlayerStore(PreferenceStore.of(compose.activity, "watch-entry-motion-disabled").apply { clearAll() })
        compose.setContent { AppTheme {
            CompositionLocalProvider(LocalMotionScale provides 0f) {
                density = LocalDensity.current.density
                MiniPlayerWindow(320, 0, store, false, {}, mini = false,
                    embeddedTopDp = 24, embeddedHeightDp = 180,
                    initialBounds = Rect(20 * density, 220 * density, 180 * density, 310 * density),
                    entryAnimationReady = hostReady,
                    onEntryProgress = { progress += it },
                    onBoundsChanged = { reportedBounds = it },
                    modifier = Modifier.size(320.dp, 500.dp)) { Box(Modifier.fillMaxSize()) }
            }
        } }
        compose.mainClock.advanceTimeBy(64)
        compose.runOnIdle {
            assertTrue(progress.isNotEmpty() && progress.all { it == 0f })
        }
        val originImage = compose.onNodeWithTag("player-window").captureToImage()
        assertEquals(160 * density, originImage.width.toFloat(), 2f)
        compose.runOnIdle { hostReady = true }
        compose.mainClock.autoAdvance = true
        compose.waitUntil(5_000) {
            progress.lastOrNull() == 1f
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1f, progress.last(), 0f)
            assertTrue("Disabled motion must not produce decorative intermediate frames", progress.all { it == 0f || it == 1f })
            assertEquals(320 * density, reportedBounds.width, 2f)
            assertEquals(180 * density, reportedBounds.height, 2f)
        }
        compose.mainClock.autoAdvance = true
    }

    private fun shell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes().decodeToString().trim() }
    }

    private fun withSystemScale(scale: String, block: () -> Unit) {
        if (InstrumentationRegistry.getArguments().getString("motion_managed_externally") == "1") {
            block()
            return
        }
        val original = shell("settings get global animator_duration_scale")
        try {
            shell("settings put global animator_duration_scale $scale")
            block()
        } finally {
            if (original.isBlank() || original == "null") shell("settings delete global animator_duration_scale")
            else shell("settings put global animator_duration_scale $original")
            compose.mainClock.autoAdvance = true
        }
    }
}
