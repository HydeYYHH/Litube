package com.hhst.youtubelite.account

import android.app.Activity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.hhst.youtubelite.account.SignInActivity
import com.hhst.youtubelite.core.UrlPolicy
import com.hhst.youtubelite.channel.ChannelActivity
import com.hhst.youtubelite.extension.ExtensionManager
import com.hhst.youtubelite.extension.PreferenceKeys
import com.hhst.youtubelite.library.YouActivity
import com.hhst.youtubelite.library.data.LibraryScenario
import com.hhst.youtubelite.library.domain.LibraryNavigationRequest
import com.hhst.youtubelite.settings.SettingsActivity
import com.hhst.youtubelite.settings.domain.SettingsPage
import com.hhst.youtubelite.ui.about.AboutActivity
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext

class AccountSettingsAndroidTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var scenario: ActivityScenario<*>? = null

    private fun launchYou(): ActivityScenario<YouActivity> {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        return ActivityScenario.launch<YouActivity>(YouActivity.intent(context, true)
            .putExtra(YouActivity.EXTRA_SCENARIO, LibraryScenario.Content.argument)).also {
            scenario = it
            compose.waitUntil(15_000) {
                runCatching { compose.onAllNodesWithTag("you-library").fetchSemanticsNodes() }.getOrDefault(emptyList()).isNotEmpty()
            }
        }
    }

    private fun openAccountMenu(host: ActivityScenario<YouActivity>) {
        compose.onNodeWithTag("you-account").performClick()
        compose.waitUntil(5_000) {
            runCatching { compose.onAllNodesWithTag("account-menu").fetchSemanticsNodes() }.getOrDefault(emptyList()).isNotEmpty()
        }
        compose.onNodeWithTag("account-menu-header").assertIsDisplayed()
    }

    @After fun close() {
        scenario?.close()
        ActivityTestSupport.finish(SignInActivity::class.java)
        ActivityTestSupport.finish(SettingsActivity::class.java)
        ActivityTestSupport.finish(AboutActivity::class.java)
        ActivityTestSupport.finish(ChannelActivity::class.java)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    @Test fun accountMenuShowsTheFixtureIdentityAndTypedRows() {
        val host = launchYou()
        openAccountMenu(host)
        compose.onAllNodes(hasText("Litube Studio") and hasAnyAncestor(hasTestTag("account-menu")))
            .assertCountEquals(1)
        compose.onAllNodes(hasText("@litube") and hasAnyAncestor(hasTestTag("account-menu")))
            .assertCountEquals(1)
        compose.onNodeWithText("Switch account").assertIsDisplayed()
        compose.onNodeWithText("Settings").assertIsDisplayed()
        compose.onNodeWithText("About Litube").assertIsDisplayed()
        // Opening the menu is local UI state, not a navigation request.
        host.onActivity { assertNull(it.lastNavigationRequest) }
    }

    @Test fun headerRoutesToTheChannelAsATypedRequest() {
        val host = launchYou()
        openAccountMenu(host)
        compose.onNodeWithTag("account-menu-header").performClick()
        compose.waitForIdle()
        host.onActivity {
            assertEquals(LibraryNavigationRequest.Channel("litube"), it.lastNavigationRequest)
        }
        ActivityTestSupport.waitForResumed(compose, ChannelActivity::class.java)
    }

    @Test fun switchAccountOpensTheNativeSwitcherWithTheFixtureAccounts() {
        val host = launchYou()
        openAccountMenu(host)
        compose.onNodeWithTag("account-action-switch").performClick()
        host.onActivity {
            assertEquals(LibraryNavigationRequest.SwitchAccount, it.lastNavigationRequest)
        }
        compose.onNodeWithTag("account-switch").assertIsDisplayed()
        compose.onNodeWithTag("account-switch-litube").assertIsDisplayed()
        compose.onNodeWithTag("account-switch-litube-maker").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("account-switch").assertDoesNotExist()
    }

    @Test fun signOutAsksForConfirmationAndStaysLocalInFixtures() {
        val host = launchYou()
        openAccountMenu(host)
        compose.onNodeWithTag("account-action-signout").performClick()
        host.onActivity {
            assertEquals(LibraryNavigationRequest.SignOut, it.lastNavigationRequest)
        }
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithText("Sign out?").assertIsDisplayed() }.isSuccess
        }
        compose.onNodeWithTag("account-signout-confirm").performClick()
        compose.waitForIdle()
        // Fixture mode performs no session write; the page stays signed in.
        compose.onNodeWithTag("you-library").assertIsDisplayed()
    }

    @Test fun signInOpensTheNativeDeviceCodeFlowInsideTheLoginBoundary() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val host = ActivityScenario.launch<YouActivity>(YouActivity.intent(context, false))
        scenario = host
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("you-signed-out").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNode(hasText("Sign in") and hasAnyAncestor(hasTestTag("account-menu"))).performClick()
        ActivityTestSupport.waitForResumed(compose, SignInActivity::class.java)
        host.onActivity {
            assertEquals(LibraryNavigationRequest.SignIn, it.lastNavigationRequest)
        }
        compose.onNodeWithTag("signin-ready").assertIsDisplayed()
        compose.onNodeWithTag("signin-code").assertIsDisplayed()
        var url: String? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            url = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<SignInActivity>().single().currentUrl
        }
        assertFalse("The device flow must request a verification URL", url.isNullOrBlank())
        assertTrue("the verification URL must stay inside the allowlisted login boundary", UrlPolicy.isLoginUrl(url))
    }

    @Test fun settingsRowOpensTheNativeSettingsHost() {
        val host = launchYou()
        openAccountMenu(host)
        compose.onNodeWithTag("account-action-settings").performClick()
        ActivityTestSupport.waitForResumed(compose, SettingsActivity::class.java)
        host.onActivity { assertEquals(LibraryNavigationRequest.Settings, it.lastNavigationRequest) }
        compose.onNodeWithTag("settings-screen").assertIsDisplayed()
        compose.onNodeWithTag("settings-row-general").assertIsDisplayed()
    }

    @Test fun anonymousUsersCanOpenSettingsWithoutSigningIn() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val host = ActivityScenario.launch<YouActivity>(YouActivity.intent(context, false))
        scenario = host
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("you-signed-out").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNode(hasText("Sign in") and hasAnyAncestor(hasTestTag("account-menu"))).assertIsDisplayed()
        compose.onNodeWithTag("account-action-settings").performClick()
        ActivityTestSupport.waitForResumed(compose, SettingsActivity::class.java)
        host.onActivity { assertEquals(LibraryNavigationRequest.Settings, it.lastNavigationRequest) }
    }

    @Test fun aboutRowReusesTheExistingAboutPage() {
        val host = launchYou()
        openAccountMenu(host)
        compose.onNodeWithTag("account-action-about").performClick()
        ActivityTestSupport.waitForResumed(compose, AboutActivity::class.java)
        host.onActivity { assertEquals(LibraryNavigationRequest.About, it.lastNavigationRequest) }
    }
}

/** Native settings are backed by the real preference store, not a display-only mock. */
class SettingsActivityAndroidTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var scenario: ActivityScenario<SettingsActivity>? = null

    private fun launch(page: SettingsPage = SettingsPage.Home): ActivityScenario<SettingsActivity> {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        return ActivityScenario.launch<SettingsActivity>(SettingsActivity.intent(context, page)).also {
            scenario = it
            compose.waitUntil(15_000) {
                runCatching { compose.onAllNodesWithTag("settings-screen").fetchSemanticsNodes() }.getOrDefault(emptyList()).isNotEmpty()
            }
        }
    }

    @After fun close() {
        scenario?.close()
        ActivityTestSupport.finish(AboutActivity::class.java)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    @Test fun homeRowsOpenSubpagesAndSurviveRecreation() {
        launch()
        compose.onNodeWithTag("settings-row-general").assertIsDisplayed()
        compose.onNodeWithTag("settings-row-playback").assertIsDisplayed()
        compose.onNodeWithTag("settings-row-notifications").assertIsDisplayed()
        compose.onNodeWithTag("settings-row-about").assertIsDisplayed()
        compose.onNodeWithTag("settings-row-general").performClick()
        compose.onNodeWithTag("settings-page-general").assertIsDisplayed()
        compose.onNodeWithTag("settings-row-theme").assertIsDisplayed()
        scenario?.recreate()
        compose.waitUntil(15_000) {
            runCatching { compose.onAllNodesWithTag("settings-page-general").fetchSemanticsNodes() }.getOrDefault(emptyList()).isNotEmpty()
        }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("settings-page-home").assertIsDisplayed()
    }

    @Test fun playbackToggleWritesTheRealPreferenceAndUpdatesItsState() {
        val prefs = GlobalContext.get().get<ExtensionManager>()
        val key = PreferenceKeys.ENABLE_BACKGROUND_PLAY
        val original = prefs.isEnabled(key)
        try {
            launch(SettingsPage.Playback)
            val before = prefs.isEnabled(key)
            val toggle = compose.onNodeWithTag("settings-toggle-$key")
            if (before) toggle.assertIsOn() else toggle.assertIsOff()
            compose.onNodeWithTag("settings-toggle-$key").performClick()
            compose.waitForIdle()
            assertEquals(!before, prefs.isEnabled(key))
            if (before) toggle.assertIsOff() else toggle.assertIsOn()
        } finally {
            prefs.setEnabled(key, original)
        }
    }

    @Test fun notificationsPageReflectsSystemStateAndOffersItsSettings() {
        launch(SettingsPage.Notifications)
        compose.onNodeWithTag("settings-notifications-state").assertIsDisplayed()
        compose.onNodeWithTag("settings-open-notification-settings").assertIsDisplayed()
    }

    @Test fun aboutRowKeepsUsingTheExistingActivity() {
        launch()
        compose.onNodeWithTag("settings-row-about").performClick()
        ActivityTestSupport.waitForResumed(compose, AboutActivity::class.java)
    }
}

/** Writes from outside the screen (Advanced host, backup import) must reach the visible toggles. */
class SettingsListenerAndroidTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var scenario: ActivityScenario<SettingsActivity>? = null

    @After fun close() {
        scenario?.close()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun launch(page: SettingsPage) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        scenario = ActivityScenario.launch(SettingsActivity.intent(context, page))
        compose.waitUntil(15_000) {
            runCatching { compose.onAllNodesWithTag("settings-screen").fetchSemanticsNodes() }.getOrDefault(emptyList()).isNotEmpty()
        }
    }

    private fun assertToggleState(key: String, enabled: Boolean) {
        compose.waitUntil(5_000) {
            runCatching {
                val toggle = compose.onNodeWithTag("settings-toggle-$key")
                if (enabled) toggle.assertIsOn() else toggle.assertIsOff()
            }.isSuccess
        }
    }

    @Test fun anExternalWriteUpdatesTheVisibleToggleWithoutReentering() {
        val prefs = GlobalContext.get().get<ExtensionManager>()
        val key = PreferenceKeys.ENABLE_HIDE_SHORTS
        val original = prefs.isEnabled(key)
        try {
            launch(SettingsPage.General)
            assertToggleState(key, original)
            prefs.setEnabled(key, !original)
            assertToggleState(key, !original)
        } finally {
            prefs.setEnabled(key, original)
        }
    }

    @Test fun aBulkResetRefreshesEveryVisibleToggle() {
        val prefs = GlobalContext.get().get<ExtensionManager>()
        val key = PreferenceKeys.ENABLE_HIDE_SHORTS
        val snapshot = prefs.allPreferences()
        val haptics = prefs.hapticStrength()
        try {
            prefs.setEnabled(key, !PreferenceKeys.DEFAULTS.getValue(key))
            launch(SettingsPage.General)
            assertToggleState(key, !PreferenceKeys.DEFAULTS.getValue(key))
            prefs.resetToDefault()
            assertToggleState(key, PreferenceKeys.DEFAULTS.getValue(key))
        } finally {
            snapshot.forEach { (k, v) -> prefs.setEnabled(k, v) }
            prefs.setHapticStrength(haptics)
        }
    }
}

private object ActivityTestSupport {
    private fun live(clazz: Class<out Activity>): List<Activity> {
        val found = mutableListOf<Activity>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val registry = ActivityLifecycleMonitorRegistry.getInstance()
            Stage.values().forEach { stage ->
                found += registry.getActivitiesInStage(stage).filter { clazz.isInstance(it) }
            }
        }
        return found
    }

    fun waitForResumed(compose: androidx.compose.ui.test.junit4.ComposeTestRule, clazz: Class<out Activity>) {
        compose.waitUntil(15_000) { live(clazz).isNotEmpty() }
    }

    fun finish(clazz: Class<out Activity>) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targets = live(clazz)
        instrumentation.runOnMainSync { targets.forEach { it.finish() } }
        instrumentation.waitForIdleSync()
    }

    fun intentExtra(clazz: Class<out Activity>, key: String): String? {
        var value: String? = null
        val target = live(clazz).firstOrNull()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            value = target?.intent?.getStringExtra(key)
        }
        return value
    }
}
