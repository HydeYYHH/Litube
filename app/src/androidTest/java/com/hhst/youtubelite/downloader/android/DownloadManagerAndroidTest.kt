package com.hhst.youtubelite.downloader.android

import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hhst.youtubelite.R
import com.hhst.youtubelite.downloader.ui.DownloadManagerScreen
import com.hhst.youtubelite.downloader.ui.DownloadTokens
import com.hhst.youtubelite.ui.theme.AppTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadManagerAndroidTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun reset() {
        resetOverlayState()
    }

    @Test
    fun filtersAndRowActions_withCoordinatorFakes() {
        val harness = DownloadAndroidHarness()
        val seeded = runBlocking { harness.seedList() }
        val video = runBlocking { harness.viewModel.observeVideo(vid("yyyyyyyyyyy")).first() }
        assertFalse(video.watchPageDownloaded)
        assertTrue(video.tasks.single().attachmentsOnly)

        composeRule.setContent {
            AppTheme(darkTheme = true, dynamicColor = false) {
                DownloadManagerScreen(
                    viewModel = harness.viewModel,
                    initialBatchId = null,
                    initialTaskId = null,
                    initialDest = null,
                    onClose = {},
                )
            }
        }
        composeRule.waitForIdle()

        val all = composeRule.activity.getString(R.string.download_filter_all)
        val inProgress = composeRule.activity.getString(R.string.download_filter_in_progress)
        val completed = composeRule.activity.getString(R.string.download_filter_completed)
        composeRule.onNodeWithText(all).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithText(inProgress).assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithText(completed).assertHeightIsAtLeast(48.dp)
        assertEquals(16, DownloadTokens.PAGE_INSET_DP)
        assertEquals(48, DownloadTokens.MIN_TOUCH_DP)

        composeRule.onNodeWithText("Running clip").assertIsDisplayed()
        composeRule.onNodeWithText("Completed clip").assertIsDisplayed()
        composeRule.onNodeWithText("Missing clip").assertIsDisplayed()
        composeRule.onNodeWithText("Subs only").assertIsDisplayed()

        composeRule.onNodeWithText(inProgress).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Running clip").assertIsDisplayed()
        composeRule.onNodeWithText("Waiting clip").assertIsDisplayed()
        assertTrue(
            composeRule.onAllNodesWithText("Completed clip").fetchSemanticsNodes().isEmpty(),
        )

        val pause = composeRule.activity.getString(R.string.action_pause)
        composeRule.onNodeWithContentDescription(pause).assertHeightIsAtLeast(48.dp).performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithContentDescription(composeRule.activity.getString(R.string.download_resume))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        composeRule.onNodeWithText(completed).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Completed clip").assertIsDisplayed()
        composeRule.onNodeWithText("Missing clip").assertIsDisplayed()
        composeRule.onNodeWithText("Subs only").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithText("Running clip").fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.download_file_not_found))
            .assertIsDisplayed()

        val more = composeRule.activity.getString(R.string.download_more_actions)
        composeRule.onAllNodesWithContentDescription(more)[0]
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.download_redownload))
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)

        assertTrue(seeded.runningBatchId.isNotBlank())
        DeviceEvidence.captureScene("03-download-manager")
    }

    @Test
    fun lightDarkAndLargeFontStillShowFilters() {
        val harness = DownloadAndroidHarness()
        runBlocking { harness.seedList() }
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                AppTheme(darkTheme = false, dynamicColor = false) {
                    DownloadManagerScreen(
                        viewModel = harness.viewModel,
                        initialBatchId = null,
                        initialTaskId = null,
                        initialDest = null,
                        onClose = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.downloads)).assertIsDisplayed()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.download_filter_all)).assertIsDisplayed()
        DeviceEvidence.captureScene("light-200pct-font")
    }

    @Test
    fun landscapeKeepsFilters() {
        val harness = DownloadAndroidHarness()
        runBlocking { harness.seedList() }
        composeRule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        composeRule.waitForIdle()
        composeRule.setContent {
            AppTheme(darkTheme = true, dynamicColor = false) {
                DownloadManagerScreen(
                    viewModel = harness.viewModel,
                    initialBatchId = null,
                    initialTaskId = null,
                    initialDest = null,
                    onClose = {},
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.download_filter_all)).assertIsDisplayed()
        composeRule.onNodeWithText("Waiting clip").assertIsDisplayed()
        DeviceEvidence.captureScene("05-resume-after-network")
        composeRule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
}
