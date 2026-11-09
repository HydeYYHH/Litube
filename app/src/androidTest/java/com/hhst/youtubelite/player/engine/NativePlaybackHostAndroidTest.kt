package com.hhst.youtubelite.player.engine

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.hhst.youtubelite.home.HomeActivity
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NativePlaybackHostAndroidTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun resumingDuringPreparationKeepsThePendingExtraction() {
        val reading = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val reads = java.util.concurrent.atomic.AtomicInteger()
        OfflineWatchFixture(onStreamRead = {
            reads.incrementAndGet()
            reading.countDown()
            check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
        }).use { fixture ->
            try {
                fixture.launch {
                    assertTrue(reading.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    fixture.instrumentation.runOnMainSync {
                        assertFalse(fixture.engine.snapshot.value.prepared)
                        fixture.engine.pause()
                        fixture.engine.play()
                        assertTrue(fixture.engine.snapshot.value.userWantsPlay)
                        assertNull(fixture.engine.snapshot.value.error)
                    }
                    release.countDown()
                    compose.waitForIdle()
                }
                fixture.await { fixture.engine.snapshot.value.firstFrameRendered }
                assertEquals(1, reads.get())
                assertTrue(fixture.engine.snapshot.value.isPlaying)
            } finally { release.countDown() }
        }
    }

    @Test fun aNewPlaybackKeepsItsPlayIntentThroughoutPreparation() {
        OfflineWatchFixture().use { fixture ->
            val snapshots = mutableListOf<PlaybackSnapshot>()
            val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)
            val observer = scope.launch {
                fixture.engine.snapshot.collect { if (it.videoId == fixture.id) snapshots += it }
            }
            try {
                fixture.launch { compose.waitForIdle() }
                fixture.await { fixture.engine.snapshot.value.firstFrameRendered }
                fixture.instrumentation.runOnMainSync {
                    assertTrue(snapshots.any { !it.prepared && it.isBuffering })
                    assertTrue("Preparation lost the user's play intent", snapshots.all { it.userWantsPlay })
                }
            } finally { observer.cancel(); scope.cancel() }
        }
    }

    @Test fun firstFrameReadinessBelongsToTheAttachedSurface() {
        OfflineWatchFixture().use { fixture ->
            fixture.launch { compose.waitForIdle() }
            fixture.await { fixture.engine.snapshot.value.firstFrameRendered }
            val surface = requireNotNull(fixture.surface())
            fixture.instrumentation.runOnMainSync {
                fixture.model.detachSurface(surface)
                assertFalse(fixture.engine.snapshot.value.firstFrameRendered)
                fixture.model.attachSurface(surface)
            }
            fixture.await { fixture.engine.snapshot.value.firstFrameRendered }
            assertEquals(fixture.id, fixture.engine.snapshot.value.videoId)
        }
    }

    @Test fun recreatedHolderNeedsANewFrameEvenWhenTheViewIsUnchanged() {
        OfflineWatchFixture().use { fixture ->
            fixture.launch { compose.waitForIdle() }
            fixture.await { fixture.engine.snapshot.value.firstFrameRendered }
            val surface = requireNotNull(fixture.surface())
            fixture.instrumentation.runOnMainSync { surface.visibility = android.view.View.INVISIBLE }
            fixture.await { !surface.holder.surface.isValid && !fixture.engine.snapshot.value.firstFrameRendered }
            assertTrue(fixture.engine.snapshot.value.isPlaying)
            fixture.instrumentation.runOnMainSync { surface.visibility = android.view.View.VISIBLE }
            fixture.await { surface.holder.surface.isValid && fixture.model.hasRenderedFrame(surface) }
            assertFalse(fixture.model.uiState.value.loading)
        }
    }

    @Test fun backToMiniThenRestoreAndPlayKeepsTheLivePictureVisible() {
        val app = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        androidx.test.core.app.ActivityScenario.launch<HomeActivity>(Intent(app, HomeActivity::class.java)
            .putExtra(HomeActivity.EXTRA_SCENARIO, "empty").putExtra("playback_test", true)).use { homeScenario ->
            lateinit var home: HomeActivity
            homeScenario.onActivity { home = it }
            OfflineWatchFixture(videoAsset = "player/freeze-motion/video.ts").use { fixture ->
                fixture.launch(source = home, startSeconds = 0) { compose.waitForIdle() }
                fixture.await { fixture.engine.snapshot.value.firstFrameRendered }
                repeat(3) {
                    val previousHost = fixture.activity
                    fixture.instrumentation.runOnMainSync { previousHost.onBackPressedDispatcher.onBackPressed() }
                    compose.waitForIdle()
                    fixture.await { previousHost.isDestroyed && fixture.model.uiState.value.mini }
                    // The mini's pause action and browse interaction use the production callbacks; no decoder state is injected.
                    fixture.instrumentation.runOnMainSync { fixture.model.onPlayPause() }
                    fixture.await { !fixture.engine.snapshot.value.userWantsPlay && !fixture.engine.snapshot.value.isPlaying }
                    compose.onNode(hasContentDescription("Search YouTube") and hasAnyAncestor(hasTestTag("home-topbar")),
                        useUnmergedTree = true).performClick()
                    compose.waitForIdle()
                    com.hhst.youtubelite.core.DeviceEvidence.shell("input keyevent KEYCODE_BACK")
                    compose.waitForIdle()
                    val monitor = fixture.instrumentation.addMonitor(
                        com.hhst.youtubelite.watch.WatchActivity::class.java.name, null, false)
                    try {
                        fixture.instrumentation.runOnMainSync { fixture.model.onMiniRestore() }
                        fixture.activity = requireNotNull(monitor.waitForActivityWithTimeout(10_000)) as com.hhst.youtubelite.watch.WatchActivity
                    } finally { fixture.instrumentation.removeMonitor(monitor) }
                    compose.waitForIdle()
                    fixture.await { !fixture.model.uiState.value.mini && fixture.model.uiState.value.visible && fixture.surface() != null }
                    assertFalse(fixture.engine.snapshot.value.userWantsPlay)
                    val position = fixture.engine.snapshot.value.positionMs
                    fixture.instrumentation.runOnMainSync { fixture.model.onPlayPause() }
                    fixture.await {
                        fixture.engine.snapshot.value.isPlaying && fixture.engine.snapshot.value.positionMs > position + 500 &&
                            fixture.surface()?.let(fixture.model::hasRenderedFrame) == true && fixture.model.uiState.value.firstFrameRendered
                    }
                    assertEquals(com.hhst.youtubelite.player.surface.PlayerUi.PlayPauseAction.Pause,
                        com.hhst.youtubelite.player.surface.PlayerUi.playPauseAction(fixture.model.uiState.value))
                    assertFalse(fixture.model.uiState.value.loading)
                    compose.onNodeWithTag("player-handoff-frame").assertDoesNotExist()
                    val before = surfaceImageHash(fixture)
                    val capturedAt = fixture.engine.snapshot.value.positionMs
                    fixture.await { fixture.engine.snapshot.value.positionMs > capturedAt + 650 }
                    assertNotEquals("The timeline advanced but the surface picture stayed frozen",
                        before, surfaceImageHash(fixture))
                }
            }
        }
    }

    /** Inspect the current output itself; decoder counters cannot prove a moving picture. */
    private fun surfaceImageHash(fixture: OfflineWatchFixture): Int {
        val bitmap = android.graphics.Bitmap.createBitmap(64, 36, android.graphics.Bitmap.Config.ARGB_8888)
        val copied = java.util.concurrent.CountDownLatch(1)
        var result = -1
        try {
            fixture.instrumentation.runOnMainSync {
                android.view.PixelCopy.request(requireNotNull(fixture.surface()), bitmap, {
                    result = it
                    copied.countDown()
                }, android.os.Handler(android.os.Looper.getMainLooper()))
            }
            assertTrue("Timed out reading the live surface", copied.await(3, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(android.view.PixelCopy.SUCCESS, result)
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            return pixels.contentHashCode()
        } finally { bitmap.recycle() }
    }

    @Test fun commentOverlayKeepsThePlayingSurfaceAndMiniRestoresTheSameVideo() {
        OfflineWatchFixture().use { fixture ->
            fixture.launch { compose.waitForIdle() }
            val initialPrepares = fixture.engine.mediaPrepareCount
            val surface = fixture.surface()
            assertNotNull(surface)
            compose.onNodeWithTag("watch-comments").performClick()
            compose.waitForIdle()
            fixture.await { fixture.activity.watchViewModel.uiState.value.commentsOpen }
            assertSame(surface, fixture.surface())
            assertTrue(fixture.engine.snapshot.value.isPlaying)
            fixture.instrumentation.runOnMainSync {
                fixture.activity.watchViewModel.onAction(com.hhst.youtubelite.watch.domain.WatchAction.CloseComments)
            }
            fixture.instrumentation.runOnMainSync {
                fixture.activity.startActivity(Intent(fixture.app, HomeActivity::class.java)
                    .putExtra(HomeActivity.EXTRA_SCENARIO, "empty").putExtra("playback_test", true))
            }
            var home: HomeActivity? = null
            try {
                fixture.await {
                    home = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<HomeActivity>().firstOrNull()
                    home != null
                }
                fixture.await { fixture.model.uiState.value.mini }
                // Mini state is published before the new window mounts its surface.
                compose.waitForIdle()
                fixture.instrumentation.runOnMainSync {
                    val state = fixture.engine.snapshot.value
                    assertTrue("Mini handoff lost play intent: $state", state.userWantsPlay)
                    assertEquals("Mini handoff changed video: $state", fixture.id, state.videoId)
                    assertNull("Mini handoff failed: $state", state.error)
                    assertEquals("Mini handoff submitted another source: $state", initialPrepares, fixture.engine.mediaPrepareCount)
                }
                fixture.await("Mini handoff did not resume the same video") {
                    fixture.engine.snapshot.value.let { state ->
                        state.videoId == fixture.id && state.isPlaying && fixture.model.uiState.value.mini
                    }
                }
                assertEquals("Mini handoff must retain the prepared source", initialPrepares, fixture.engine.mediaPrepareCount)
                compose.onNodeWithTag("home-topbar").assertIsDisplayed()
                compose.onNode(hasContentDescription("Search YouTube") and hasAnyAncestor(hasTestTag("home-topbar"))).performClick()
                compose.waitForIdle()
                assertEquals(com.hhst.youtubelite.home.domain.HomeOverlay.Search, home!!.homeViewModel.uiState.value.overlay)
                com.hhst.youtubelite.core.DeviceEvidence.shell("input keyevent KEYCODE_BACK")
                compose.waitForIdle()
                assertTrue(fixture.model.uiState.value.mini)
                fixture.instrumentation.runOnMainSync { fixture.model.onMiniRestore() }
                fixture.await { !fixture.model.uiState.value.mini && fixture.model.uiState.value.visible }
                fixture.await { fixture.surface()?.let(fixture.model::hasRenderedFrame) == true }
                compose.onNodeWithTag("player-handoff-frame").assertDoesNotExist()
                assertEquals(fixture.id, fixture.engine.snapshot.value.videoId)
                assertTrue(fixture.engine.snapshot.value.isPlaying)
                assertEquals("Mini restore must retain the prepared source", initialPrepares, fixture.engine.mediaPrepareCount)
            } finally { fixture.instrumentation.runOnMainSync { home?.finish() } }
        }
    }
}
