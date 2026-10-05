package com.hhst.youtubelite.downloader.android

import android.content.pm.ActivityInfo
import com.hhst.youtubelite.downloader.core.DownloadPhase
import com.hhst.youtubelite.downloader.core.DownloadStatus
import com.hhst.youtubelite.downloader.android.DeviceEvidence
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hhst.youtubelite.R
import com.hhst.youtubelite.downloader.ui.DownloadManagerScreen
import com.hhst.youtubelite.downloader.ui.DownloadItemRow
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
        composeRule.onNode(isPopup()).assertExists()
        composeRule.onNode(isDialog()).assertDoesNotExist()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.download_redownload))
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)

        assertTrue(seeded.runningBatchId.isNotBlank())
        DeviceEvidence.captureScene("03-download-manager")
        // Send Back to the focused popup window, including on API 35 gesture navigation.
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        composeRule.waitUntil(3_000) { composeRule.onAllNodes(isPopup()).fetchSemanticsNodes().isEmpty() }
        composeRule.onNode(isPopup()).assertDoesNotExist()
        composeRule.onAllNodesWithContentDescription(more)[0].performClick()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.download_delete)).performClick()
        composeRule.onNode(isDialog()).assertExists()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.download_delete_local_file))
            .assertIsDisplayed()
        DeviceEvidence.captureScene("neutral-delete-checkbox")
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.cancel)).performClick()
        composeRule.onAllNodesWithContentDescription(more)[0].performClick()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.download_copy_id)).performClick()
        composeRule.waitForIdle()
        composeRule.onNode(isPopup()).assertDoesNotExist()
    }

    @Test
    fun rapidStatusChangesKeepRowHeightAndNeighborPosition() {
        val harness = DownloadAndroidHarness()
        runBlocking { harness.seedList() }
        val base = runBlocking { harness.viewModel.observeDownloads().first().first { it.title == "Running clip" } }
        val row = androidx.compose.runtime.mutableStateOf(base)
        composeRule.setContent {
            AppTheme(darkTheme = true) {
                androidx.compose.foundation.layout.Column {
                    androidx.compose.foundation.layout.Box(Modifier.testTag("stable-row")) {
                        DownloadItemRow(row.value, {}, {})
                    }
                    androidx.compose.material3.Text("Neighbor", modifier = Modifier.testTag("neighbor"))
                }
            }
        }
        composeRule.waitForIdle()
        val height = composeRule.onNodeWithTag("stable-row").fetchSemanticsNode().boundsInRoot.height
        val top = composeRule.onNodeWithTag("neighbor").fetchSemanticsNode().boundsInRoot.top
        val states = listOf(
            base.copy(status = DownloadStatus.QUEUED,
                phase = DownloadPhase.RESOLVE, progressBytes = 0,
                expectedBytes = null, qualityLabel = null, title = "Short", author = null),
            base.copy(progressBytes = 4_096, expectedBytes = 8_000_000, qualityLabel = "1080p"),
            base.copy(status = DownloadStatus.PAUSING),
            base.copy(status = DownloadStatus.PAUSED),
            base.copy(status = DownloadStatus.WAITING_NETWORK),
            base.copy(phase = DownloadPhase.MERGE_VERIFY),
            base.copy(phase = DownloadPhase.COMPLETE),
        )
        repeat(3) { states.forEach { next ->
            composeRule.runOnIdle { row.value = next }
            composeRule.waitForIdle()
            assertEquals(height, composeRule.onNodeWithTag("stable-row").fetchSemanticsNode().boundsInRoot.height, .5f)
            assertEquals(top, composeRule.onNodeWithTag("neighbor").fetchSemanticsNode().boundsInRoot.top, .5f)
        } }
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
    fun narrowLargeFontShowsCompleteStatusAndScrollableFilterLabels() {
        val harness = DownloadAndroidHarness()
        runBlocking { harness.seedList() }
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                AppTheme(darkTheme = false, dynamicColor = false) {
                    androidx.compose.foundation.layout.Box(Modifier.width(320.dp)) {
                        DownloadManagerScreen(harness.viewModel, null, null, null, {})
                    }
                }
            }
        }
        composeRule.waitForIdle()
        fun assertUnclipped(text: String) {
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            composeRule.onAllNodesWithText(text, useUnmergedTree = true)[0]
                .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue("No layout for $text", layouts.isNotEmpty())
            val layout = layouts.single()
            assertFalse("Clipped height: $text", layout.didOverflowHeight)
            assertEquals("Missing characters: $text", text.length,
                layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
            for (line in 0 until layout.lineCount) {
                assertFalse("Ellipsized text: $text", layout.isLineEllipsized(line))
                // Text's intrinsic width is rounded to pixels; tolerate only that rounding.
                assertTrue("Clipped width: $text", layout.getLineRight(line) <= layout.size.width + 1f)
            }
        }
        val waiting = composeRule.activity.getString(R.string.download_waiting_network)
        val complete = composeRule.activity.getString(R.string.download_complete)
        for (status in listOf(waiting, complete)) {
            composeRule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(status))
            DeviceEvidence.captureScene("fixed-status-${if (status == waiting) "waiting" else "complete"}")
            assertUnclipped(status)
        }
        val completed = composeRule.activity.getString(R.string.download_filter_completed)
        composeRule.onNodeWithText(completed).performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        assertUnclipped(completed)
        DeviceEvidence.captureScene("fixed-manager-narrow-large-font")
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
