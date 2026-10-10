package com.hhst.youtubelite.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
import com.hhst.youtubelite.ui.about.AboutScreen
import com.hhst.youtubelite.ui.extension.ExtensionScreen
import com.hhst.youtubelite.ui.extension.ExtensionViewModel
import com.hhst.youtubelite.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext

class ResponsiveExperienceAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun extensionUsesMeasuredWebSettingsTypographyAndRowHeight() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                AppTheme { ExtensionScreen({}) }
            }
        }
        val label = compose.onNodeWithText(compose.activity.getString(R.string.interface_category))
        val results = mutableListOf<TextLayoutResult>()
        label.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertEquals(14.sp, results.single().layoutInput.style.fontSize)
        assertEquals(20.sp, results.single().layoutInput.style.lineHeight)
        assertEquals(FontWeight.Normal, results.single().layoutInput.style.fontWeight)
        assertEquals(48f, label.fetchSemanticsNode().boundsInRoot.height, 1f)
        results.clear()
        compose.onNodeWithText(compose.activity.getString(R.string.extension))
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

    @Test fun extensionSliderSupportsZeroAndResetWithLargeFont() {
        val manager = GlobalContext.get().get<ExtensionManager>()
        val previous = manager.allPreferences()
        val strength = manager.hapticStrength()
        val model = ExtensionViewModel(manager)
        try {
            compose.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                    AppTheme { ExtensionScreen({}, viewModel = model) }
                }
            }
            compose.onNodeWithText(compose.activity.getString(R.string.interface_category)).performClick()
            val slider = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
            slider.performScrollTo().assertIsDisplayed()
            slider.performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
            compose.waitForIdle()
            assertEquals(0, manager.hapticStrength())
            compose.onNodeWithContentDescription(compose.activity.getString(R.string.reset_all_settings)).performClick()
            compose.onNodeWithText(compose.activity.getString(R.string.confirm)).performClick()
            compose.waitForIdle()
            assertEquals(30, manager.hapticStrength())
        } finally {
            previous.forEach(manager::setEnabled)
            manager.setHapticStrength(strength)
        }
    }
}
