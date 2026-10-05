package com.hhst.youtubelite.downloader.android

import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hhst.youtubelite.R
import com.hhst.youtubelite.downloader.core.BatchSnapshot
import com.hhst.youtubelite.downloader.core.BatchSource
import com.hhst.youtubelite.downloader.ui.BatchConfirmSheet
import com.hhst.youtubelite.downloader.ui.DownloadTokens
import com.hhst.youtubelite.downloader.ui.SingleVideoConfirmSheet
import com.hhst.youtubelite.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
class DownloadConfirmSheetAndroidTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun reset() {
        resetOverlayState()
    }

    @Test
    fun singleConfirmSheet_tokensAndQuality() {
        val harness = DownloadAndroidHarness()
        composeRule.setContent {
            AppTheme(darkTheme = true, dynamicColor = false) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SingleVideoConfirmSheet(
                    videoId = vid("dQw4w9wgGcQ"),
                    title = "Sample clip",
                    author = "Channel",
                    thumbnailUrl = null,
                    viewModel = harness.viewModel,
                    onDismiss = {},
                    onSubmitted = {},
                    embedded = true,
                    )
                }
            }
        }
        composeRule.waitUntil(8_000) {
            composeRule.onAllNodesWithText("1080p").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.download_video)).assertIsDisplayed()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.download_audio)).assertIsDisplayed()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.download_quality)).performScrollTo().performClick()
        composeRule.onNodeWithText("720p").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("720p").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.download_more_options)).performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.download_attachments_only))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(composeRule.activity.getString(R.string.download))
            .performScrollTo()
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
        assertEquals(16, DownloadTokens.PAGE_INSET_DP)
        assertEquals(28, DownloadTokens.SHEET_CORNER_DP)
        assertEquals(48, DownloadTokens.MIN_TOUCH_DP)
        DeviceEvidence.captureScene("04-quality-settings")
    }

    @Test
    fun batchConfirmSheet_selectAllAndTouchTargets() {
        val harness = DownloadAndroidHarness()
        val snapshot = BatchSnapshot(
            source = BatchSource.PLAYLIST,
            name = "Playlist batch",
            items = listOf(
                request("dQw4w9wgGcQ", "One"),
                request("jNQXAC9IVRw", "Two"),
            ),
        )
        composeRule.setContent {
            AppTheme(darkTheme = true, dynamicColor = false) {
                BatchConfirmSheet(
                    snapshot = snapshot,
                    viewModel = harness.viewModel,
                    onDismiss = {},
                    onSubmitted = {},
                    onRejected = {},
                    embedded = true,
                )
            }
        }
        composeRule.waitUntil(8_000) {
            composeRule.onAllNodesWithText("Playlist batch").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Playlist batch").assertIsDisplayed()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.download_select_all)).assertIsDisplayed()
        composeRule.onNodeWithText("One").assertIsDisplayed()
        composeRule.onNodeWithText("Two").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(composeRule.activity.getString(R.string.download))
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun singleConfirmSheet_landscapeStillShowsMediaToggle() {
        composeRule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        composeRule.waitForIdle()
        val harness = DownloadAndroidHarness()
        composeRule.setContent {
            AppTheme(darkTheme = false, dynamicColor = false) {
                SingleVideoConfirmSheet(
                    videoId = vid("dQw4w9wgGcQ"),
                    title = "Sample clip",
                    author = "Channel",
                    thumbnailUrl = null,
                    viewModel = harness.viewModel,
                    onDismiss = {},
                    onSubmitted = {},
                    embedded = true,
                )
            }
        }
        composeRule.waitUntil(8_000) {
            composeRule.onAllNodesWithText(composeRule.activity.getString(R.string.download_video))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.download_video)).assertIsDisplayed()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.download_audio)).assertIsDisplayed()
        composeRule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
}
