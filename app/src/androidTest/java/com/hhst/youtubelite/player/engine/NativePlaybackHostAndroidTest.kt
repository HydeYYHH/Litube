package com.hhst.youtubelite.player.engine

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.hhst.youtubelite.home.HomeActivity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NativePlaybackHostAndroidTest {
    @get:Rule val compose = createEmptyComposeRule()

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
