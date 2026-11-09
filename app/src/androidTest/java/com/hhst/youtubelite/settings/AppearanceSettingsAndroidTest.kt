package com.hhst.youtubelite.settings

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.R
import com.hhst.youtubelite.core.AppLocale
import com.hhst.youtubelite.extension.ExtensionManager
import com.hhst.youtubelite.settings.domain.SettingsPage
import com.hhst.youtubelite.ui.theme.AppAppearance
import com.hhst.youtubelite.ui.theme.ThemeMode
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext

class AppearanceSettingsAndroidTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun themeSelectionPersistsAndSurvivesRecreation() {
        val settings = GlobalContext.get().get<ExtensionManager>()
        val original = settings.themeMode()
        try {
            ActivityScenario.launch<SettingsActivity>(SettingsActivity.intent(context, SettingsPage.General)).use { host ->
                compose.onNodeWithTag("settings-row-theme").performClick()
                compose.onNodeWithTag("theme-dialog-option-2").performClick()
                compose.waitForIdle()
                assertEquals(ThemeMode.Dark, settings.themeMode())
                assertEquals(ThemeMode.Dark, AppAppearance.mode)
                host.recreate()
                compose.onNodeWithTag("settings-row-theme").performClick()
                compose.onNodeWithTag("theme-dialog-option-2").assertIsSelected()
            }
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                settings.setThemeMode(original)
                AppAppearance.update(original)
            }
        }
    }

    @Test fun languageSelectionUpdatesTheHostResourcesAndLayoutDirection() {
        val original = AppLocale.tag(context)
        try {
            ActivityScenario.launch<SettingsActivity>(SettingsActivity.intent(context, SettingsPage.General)).use { host ->
                val index = AppLocale.OPTIONS.indexOfFirst { it.first == "ar" }
                compose.onNodeWithTag("settings-row-language").performClick()
                compose.onNodeWithTag("language-dialog-option-$index").performScrollTo().performClick()
                compose.waitUntil(15_000) {
                    var matches = false
                    host.onActivity { matches = it.resources.configuration.locales[0].language == "ar" }
                    matches
                }
                var title = ""
                host.onActivity {
                    assertEquals(android.view.View.LAYOUT_DIRECTION_RTL, it.resources.configuration.layoutDirection)
                    title = it.getString(R.string.settings_general)
                }
                compose.onNode(hasText(title) and hasAnyAncestor(hasTestTag("settings-topbar"))).assertIsDisplayed()
                host.recreate()
                host.onActivity { assertEquals("ar", it.resources.configuration.locales[0].language) }
            }
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { AppLocale.apply(context, original) }
        }
    }
}
