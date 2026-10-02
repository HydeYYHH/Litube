package com.hhst.youtubelite.downloader.android

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hhst.youtubelite.R
import com.hhst.youtubelite.downloader.ui.DownloadManagerScreen
import com.hhst.youtubelite.ui.theme.AppTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadSceneAndroidTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun reset() {
        resetOverlayState()
        DeviceEvidence.writeMeta()
    }

    @Test
    fun captureManagerScenesAndRecordRuntime() {
        assertTrue("instrumented runtime must be at least minSdk", Build.VERSION.SDK_INT >= 26)
        val harness = DownloadAndroidHarness()
        runBlocking { harness.seedList() }
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
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.downloads)).assertIsDisplayed()
        DeviceEvidence.capture("device-manager-api${Build.VERSION.SDK_INT}.png")
        DeviceEvidence.dumpUi("device-manager-api${Build.VERSION.SDK_INT}.xml")
    }
}
