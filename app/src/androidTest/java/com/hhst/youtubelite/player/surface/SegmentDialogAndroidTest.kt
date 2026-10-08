package com.hhst.youtubelite.player.surface

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import com.hhst.youtubelite.R
import com.hhst.youtubelite.core.DeviceEvidence
import com.hhst.youtubelite.ui.theme.AppTheme
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** The segment dialog in short landscape at 1.5x font: timestamp row must stay visible and usable. */
class SegmentDialogAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<PlayerTestActivity>()

    @Before fun landscape() {
        compose.runOnUiThread { compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE }
        compose.waitUntil(10000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
    }

    @Test fun segmentTimestampAndActionsRemainVisibleInShortLandscapeAtLargeFont() {
        var jumped = false
        compose.setContent { AppTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                SegmentPreviewDialog("너의 이름은(君の名は。) - 스파클(Sparkle) ".repeat(6), "1:11:12", null,
                    { jumped = true }, {})
            }
        } }
        compose.onNodeWithText("1:11:12").assertIsDisplayed()
        val time = compose.onNodeWithText("1:11:12").fetchSemanticsNode().boundsInRoot
        val jump = compose.onNodeWithText(compose.activity.getString(R.string.jump))
        jump.assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.close)).assertIsDisplayed()
        assertTrue("Timestamp overlaps actions", time.bottom <= jump.fetchSemanticsNode().boundsInRoot.top)
        DeviceEvidence.captureScene("segment-timestamp")
        jump.performClick()
        compose.runOnIdle { assertTrue(jumped) }
    }
}
