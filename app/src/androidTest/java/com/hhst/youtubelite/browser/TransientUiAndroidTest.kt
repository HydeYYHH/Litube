package com.hhst.youtubelite.browser

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.hhst.youtubelite.R
import com.hhst.youtubelite.core.DeviceEvidence
import com.hhst.youtubelite.extension.PreferenceKeys
import com.hhst.youtubelite.player.engine.OfflineWatchFixture
import com.hhst.youtubelite.watch.domain.WatchContent
import com.hhst.youtubelite.watch.domain.WatchOverlay
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import org.junit.After

class TransientUiAndroidTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Before fun enterPhoneWindow() = com.hhst.youtubelite.core.PhoneWindow.enter()

    @After fun exitPhoneWindow() {
        com.hhst.youtubelite.core.PhoneWindow.exit()
    }

    @Test fun modalInteractionPreservesPlayback() {
        OfflineWatchFixture().use { fixture ->
            fixture.launch { compose.waitForIdle() }
            val surface = fixture.surface()
            val page = (fixture.activity.watchViewModel.uiState.value.content as WatchContent.Ready).page
            val item = page.related.first()
            val menuDescription = fixture.activity.getString(R.string.a11y_more_actions_for, item.title)
            val navigationBefore = fixture.activity.lastNavigationRequest
            compose.onNodeWithTag("watch-scroll").performScrollToNode(hasContentDescription(menuDescription))
            repeat(2) {
                compose.onNodeWithContentDescription(menuDescription).performClick()
                compose.onNodeWithTag("watch-video-menu").assertIsDisplayed()
                assertEquals(WatchOverlay.ItemMenu(item.id), fixture.activity.watchViewModel.uiState.value.overlay)
                assertSame(surface, fixture.surface())
                assertEquals(fixture.id, fixture.engine.snapshot.value.videoId)
                assertTrue(fixture.engine.snapshot.value.isPlaying)
                assertEquals(navigationBefore, fixture.activity.lastNavigationRequest)
                DeviceEvidence.shell("input keyevent KEYCODE_BACK")
                compose.waitForIdle()
                compose.onNodeWithTag("watch-video-menu").assertDoesNotExist()
                assertEquals(WatchOverlay.None, fixture.activity.watchViewModel.uiState.value.overlay)
                assertEquals(navigationBefore, fixture.activity.lastNavigationRequest)
                assertFalse(fixture.activity.isFinishing)
                assertEquals(fixture.id, fixture.engine.snapshot.value.videoId)
                assertTrue(fixture.engine.snapshot.value.isPlaying)
            }
            fixture.instrumentation.runOnMainSync {
                fixture.prefs.importPreferences(mapOf(PreferenceKeys.ENABLE_PIP to false), 42)
            }
            fixture.await { !fixture.model.uiState.value.pipAvailable }
        }
    }
}
