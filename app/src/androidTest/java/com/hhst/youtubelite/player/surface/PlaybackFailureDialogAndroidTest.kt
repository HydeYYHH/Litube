package com.hhst.youtubelite.player.surface

import android.content.ClipboardManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.hhst.youtubelite.diagnostics.PlaybackFailureReport
import com.hhst.youtubelite.ui.theme.AppTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PlaybackFailureDialogAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun copyContainsTheCompleteFrozenReportAndDoesNotRetry() {
        val report = PlaybackFailureReport("report", "GjjK6yD5I9I", "Failed", "Video: GjjK6yD5I9I\nMEDIA_ERROR_BODY\n" + "Trace\n".repeat(100))
        var dismissed = 0
        var retries = 0
        compose.setContent { AppTheme { PlaybackFailureDialog(report, { dismissed++ }, { retries++ }) } }
        compose.onNodeWithTag("playback-failure-copy").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(report.text, compose.activity.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString())
            assertEquals(0, retries)
            assertEquals(0, dismissed)
        }
        compose.onNodeWithTag("playback-failure-retry").performClick()
        compose.runOnIdle {
            assertEquals(1, retries)
            assertEquals(1, dismissed)
        }
    }

    @Test fun closingKeepsPlaybackCommandsUntouched() {
        var dismissed = false
        var retried = false
        compose.setContent { AppTheme {
            PlaybackFailureDialog(PlaybackFailureReport("report", "FEZS601QSVE", "Failed", "diagnostics"),
                { dismissed = true }, { retried = true })
        } }
        compose.onNodeWithTag("playback-failure-close").performClick()
        compose.runOnIdle { assertTrue(dismissed); assertFalse(retried) }
    }
}
