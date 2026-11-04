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

    @Test fun commentOverlayKeepsThePlayingSurfaceAndMiniRestoresTheSameVideo() {
        OfflineWatchFixture().use { fixture ->
            fixture.launch { compose.waitForIdle() }
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
                assertTrue(fixture.engine.snapshot.value.isPlaying)
                compose.onNodeWithTag("home-topbar").assertIsDisplayed()
                compose.onNode(hasContentDescription("Search YouTube") and hasAnyAncestor(hasTestTag("home-topbar"))).performClick()
                compose.waitForIdle()
                assertEquals(com.hhst.youtubelite.home.domain.HomeOverlay.Search, home!!.homeViewModel.uiState.value.overlay)
                com.hhst.youtubelite.core.DeviceEvidence.shell("input keyevent KEYCODE_BACK")
                compose.waitForIdle()
                assertTrue(fixture.model.uiState.value.mini)
                fixture.instrumentation.runOnMainSync { fixture.model.onMiniRestore() }
                fixture.await { !fixture.model.uiState.value.mini && fixture.model.uiState.value.visible }
                assertEquals(fixture.id, fixture.engine.snapshot.value.videoId)
                assertTrue(fixture.engine.snapshot.value.isPlaying)
            } finally { fixture.instrumentation.runOnMainSync { home?.finish() } }
        }
    }
}
