package com.hhst.youtubelite.player.engine

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PlaybackFailureHostAndroidTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun terminalSourceFailureOpensOnceAndRemainsAvailableAfterDismissal() {
        OfflineWatchFixture("failure0001", refuseMedia = true).use { fixture ->
            fixture.launch(expectFailure = true) { compose.waitForIdle() }
            compose.onNodeWithTag("playback-failure-dialog").assertIsDisplayed()
            val report = requireNotNull(fixture.engine.snapshot.value.failureReport)
            assertEquals(fixture.id, report.videoId)
            assertTrue(report.text.contains("http_status"))
            compose.onNodeWithTag("playback-failure-close").performClick()
            compose.onNodeWithTag("playback-failure-dialog").assertDoesNotExist()
            compose.runOnIdle { fixture.model.openVideo(fixture.id) }
            compose.onNodeWithTag("playback-failure-dialog").assertDoesNotExist()
            compose.onNodeWithTag("playback-error-details").performClick()
            compose.onNodeWithTag("playback-failure-dialog").assertIsDisplayed()
            assertEquals(report.id, fixture.model.uiState.value.failureReport?.id)
        }
    }
}
