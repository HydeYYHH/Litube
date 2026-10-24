package com.hhst.youtubelite.browser

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.hhst.youtubelite.core.DeviceEvidence
import com.hhst.youtubelite.extension.PreferenceKeys
import com.hhst.youtubelite.player.engine.OfflineWatchFixture
import com.hhst.youtubelite.watch.domain.WatchContent
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
            compose.onNodeWithTag("watch-scroll").performScrollToNode(hasTestTag("watch-thumb-" + item.id))
            repeat(2) {
                compose.onNodeWithTag("watch-thumb-" + item.id).performTouchInput { longClick() }
                compose.onNodeWithTag("media-actions").assertIsDisplayed()
                assertSame(surface, fixture.surface())
                assertTrue(fixture.engine.snapshot.value.isPlaying)
                DeviceEvidence.shell("input keyevent KEYCODE_BACK")
                compose.waitForIdle()
                compose.onNodeWithTag("media-actions").assertDoesNotExist()
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
