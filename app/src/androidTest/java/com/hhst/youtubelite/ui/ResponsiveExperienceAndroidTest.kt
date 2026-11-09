package com.hhst.youtubelite.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import com.hhst.youtubelite.R
import com.hhst.youtubelite.extension.ExtensionManager
import com.hhst.youtubelite.extension.PreferenceKeys
import com.hhst.youtubelite.settings.presentation.AdvancedScreen
import com.hhst.youtubelite.settings.presentation.HapticsSettings
import com.hhst.youtubelite.ui.about.AboutScreen
import com.hhst.youtubelite.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext

class ResponsiveExperienceAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun advancedUsesSharedSettingsTypographyAndRowHeight() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                AppTheme {
                    AdvancedScreen(
                        preferences = emptyMap(),
                        onToggle = { _, _ -> },
                        onBack = {},
                        onOpenData = {},
                        onOpenDownloads = {},
                        haptics = HapticsSettings(30) {},
                    )
                }
            }
        }
        val label = compose.onNodeWithText(compose.activity.getString(R.string.interface_category))
        val results = mutableListOf<TextLayoutResult>()
        label.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertEquals(14.sp, results.single().layoutInput.style.fontSize)
        assertEquals(20.sp, results.single().layoutInput.style.lineHeight)
        assertEquals(FontWeight.Medium, results.single().layoutInput.style.fontWeight)
        val row = compose.onNodeWithTag("settings-toggle-${PreferenceKeys.ENABLE_DISPLAY_DISLIKES}")
        row.performScrollTo().assertIsDisplayed()
        assertTrue("Touch target too short", row.fetchSemanticsNode().boundsInRoot.height >= 48f)
        results.clear()
        compose.onNodeWithText(compose.activity.getString(R.string.settings_advanced))
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertEquals(20.sp, results.single().layoutInput.style.fontSize)
        assertEquals(FontWeight.Medium, results.single().layoutInput.style.fontWeight)
    }

    @Test fun aboutRemainsCenteredAndScrollableWithLargeFontOnWideWindow() {
        var exports = 0
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                AppTheme {
                    AboutScreen("LiTube", "3.0.0-devx", {}, false, "", {}, {}, {}, {}, { exports++ })
                }
            }
        }
        val action = compose.onNodeWithText(compose.activity.getString(R.string.about_export_logs))
        action.performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, exports)
        val bounds = action.fetchSemanticsNode().boundsInRoot
        assertTrue("Body stretched beyond its reading width", bounds.width <= 720f)
        assertTrue("Touch target too short", bounds.height >= 48f)
    }

    @Test fun advancedSliderSupportsZeroWithLargeFont() {
        val manager = GlobalContext.get().get<ExtensionManager>()
        val previous = manager.allPreferences()
        val strength = manager.hapticStrength()
        try {
            compose.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                    AppTheme {
                        AdvancedScreen(
                            preferences = previous,
                            onToggle = { key, enabled -> manager.setEnabled(key, enabled) },
                            onBack = {},
                            onOpenData = {},
                            onOpenDownloads = {},
                            haptics = HapticsSettings(manager.hapticStrength()) { manager.setHapticStrength(it) },
                        )
                    }
                }
            }
            val slider = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
            slider.performScrollTo().assertIsDisplayed()
            slider.performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
            compose.waitForIdle()
            assertEquals(0, manager.hapticStrength())
        } finally {
            previous.forEach(manager::setEnabled)
            manager.setHapticStrength(strength)
        }
    }
}
