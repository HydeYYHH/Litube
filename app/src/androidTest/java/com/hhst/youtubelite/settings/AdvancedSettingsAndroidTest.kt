package com.hhst.youtubelite.settings

import android.view.KeyEvent
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.hhst.youtubelite.extension.ExtensionManager
import com.hhst.youtubelite.extension.PreferenceKeys
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext

class AdvancedSettingsAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<AdvancedActivity>()

    @Test fun eachGestureSelectsBothPlaybackScopesAndSurvivesRecreation() {
        val prefs = GlobalContext.get().get<ExtensionManager>()
        val windowed = PreferenceKeys.GESTURE_DOUBLE_TAP_WINDOWED
        val fullscreen = PreferenceKeys.GESTURE_DOUBLE_TAP_FULLSCREEN
        val original = mapOf(windowed to prefs.isEnabled(windowed), fullscreen to prefs.isEnabled(fullscreen))
        try {
            for (choice in 0..3) {
                awaitScreen()
                compose.onNodeWithTag("gesture-$windowed").performScrollTo().performClick()
                compose.onNodeWithTag("gesture-scope-dialog-option-$choice").performClick()
                compose.runOnIdle {
                    check(prefs.isEnabled(windowed) == (choice and 1 != 0))
                    check(prefs.isEnabled(fullscreen) == (choice and 2 != 0))
                }
                compose.activityRule.scenario.recreate()
                awaitScreen()
                compose.onNodeWithTag("gesture-$windowed").performScrollTo().performClick()
                compose.onNodeWithTag("gesture-scope-dialog-option-$choice").assertIsSelected()
                InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                compose.onNodeWithTag("gesture-scope-dialog").assertDoesNotExist()
            }
        } finally { compose.runOnUiThread { prefs.importPreferences(original, null) } }
    }

    private fun awaitScreen() = compose.waitUntil(10_000) {
        compose.onAllNodesWithTag("advanced-screen").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
    }

    @Test fun togglesReflectTheirPersistedStateWithoutLeavingTheScreen() {
        val prefs = GlobalContext.get().get<ExtensionManager>()
        val key = PreferenceKeys.ENABLE_DISPLAY_DISLIKES
        val original = prefs.isEnabled(key)
        try {
            compose.runOnUiThread { prefs.setEnabled(key, false) }
            val row = compose.onNodeWithTag("settings-toggle-$key")
            row.performScrollTo().assertIsOff().performClick().assertIsOn()
            compose.runOnUiThread { check(prefs.isEnabled(key)) }
            row.performClick().assertIsOff()
        } finally { compose.runOnUiThread { prefs.setEnabled(key, original) } }
    }
}
