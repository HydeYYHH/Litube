package com.hhst.youtubelite.library

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.data.media.MediaSession
import org.junit.Assume.*
import org.junit.Rule
import org.junit.Test

class AnonymousLibraryAndroidTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun deviceLibraryDoesNotOfferAccountOnlyMenuRows() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("network") == "1")
        assumeFalse(MediaSession.signedIn())
        ActivityScenario.launch<YouActivity>(YouActivity.intent(ApplicationProvider.getApplicationContext(), false)).use {
            compose.waitUntil(10_000) { compose.onAllNodesWithText("On this device").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("More").performClick()
            compose.onNodeWithText("Sign in").assertIsDisplayed()
            compose.onNodeWithText("Sign out").assertDoesNotExist()
            compose.onNodeWithText("Switch account").assertDoesNotExist()
        }
    }
}
