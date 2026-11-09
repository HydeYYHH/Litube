package com.hhst.youtubelite.downloader.android

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.hhst.youtubelite.R
import com.hhst.youtubelite.core.DeviceEvidence
import com.hhst.youtubelite.downloader.ui.SingleVideoConfirmSheet
import com.hhst.youtubelite.ui.theme.AppTheme
import com.hhst.youtubelite.ui.theme.NeutralControls
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals

@OptIn(ExperimentalMaterial3Api::class)
class DownloadCompactLayoutAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun reset() = resetOverlayState()

    @Test fun modalKeepsItsIdentityAndPrimaryActionVisibleAcrossThemes() {
        val harness = DownloadAndroidHarness()
        var dark by mutableStateOf(true)
        val scale = compose.activity.resources.configuration.fontScale
        InstrumentationRegistry.getArguments().getString("expectedFontScale")?.toFloat()?.let {
            assertEquals("System font configuration must reach the host", it, scale, 0.01f)
        }
        compose.setContent {
            AppTheme(darkTheme = dark, dynamicColor = false) {
                NeutralControls {
                    Surface(Modifier.fillMaxSize()) {
                        SingleVideoConfirmSheet(vid("dQw4w9wgGcQ"), "Sample clip", "Channel", null,
                            harness.viewModel, {}, {})
                    }
                }
            }
        }
        compose.waitUntil(8_000) { compose.onAllNodesWithText("1080p").fetchSemanticsNodes().isNotEmpty() }
        for (mode in listOf(true, false)) {
            compose.runOnIdle { dark = mode }
            compose.onNodeWithText("Sample clip").assertIsDisplayed()
            compose.onNodeWithText("Channel").assertIsDisplayed()
            compose.onNodeWithContentDescription(compose.activity.getString(R.string.download))
                .assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            DeviceEvidence.captureScene("download-modal-${if (mode) "dark" else "light"}-font${scale.toInt()}")
        }
    }
}
