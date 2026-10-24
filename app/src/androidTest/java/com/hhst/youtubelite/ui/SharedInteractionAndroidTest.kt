package com.hhst.youtubelite.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.hhst.youtubelite.ui.design.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.After

class SharedInteractionAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val appearance = com.hhst.youtubelite.ui.theme.YoutubeAppearance.dark
    @After fun restore() { com.hhst.youtubelite.ui.theme.YoutubeAppearance.update(appearance) }

    @Test fun darkSelectedControlsAndSearchStayReadable() {
        com.hhst.youtubelite.ui.theme.YoutubeAppearance.update(true)
        var screen by mutableIntStateOf(0)
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                Box(Modifier.requiredSize(390.dp, 844.dp).testTag("dark-root")) {
                    when (screen) {
                        0 -> com.hhst.youtubelite.home.presentation.HomeScreen(
                            com.hhst.youtubelite.home.domain.HomeUiState(content = com.hhst.youtubelite.home.data.HomeScenario.Feed.initialContent()), {})
                        1 -> com.hhst.youtubelite.watch.presentation.WatchScreen(
                            com.hhst.youtubelite.watch.domain.WatchUiState(content = com.hhst.youtubelite.watch.domain.WatchContent.Ready(
                                com.hhst.youtubelite.watch.data.FixtureWatchRepository.samplePage("bunny"))), {}, {}, playerSlot = {})
                        else -> com.hhst.youtubelite.home.presentation.HomeScreen(
                            com.hhst.youtubelite.home.domain.HomeUiState(overlay = com.hhst.youtubelite.home.domain.HomeOverlay.Search), {})
                    }
                }
            }
        }
        fun assertDarkText(label: String) {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(label, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue("$label must contrast with its pale selected surface", layouts.single().layoutInput.style.color.red < 0.2f)
        }
        assertDarkText("All")
        compose.runOnIdle { screen = 1 }
        assertDarkText("Subscribe")
        compose.runOnIdle { screen = 2 }
        val search = compose.onNodeWithTag("search-overlay").captureToImage().asAndroidBitmap()
        assertTrue("Search toolbar must follow dark appearance", android.graphics.Color.red(search.getPixel(1, 1)) < 80)
        val directory = java.io.File(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .targetContext.getExternalFilesDir(null), "review-acceptance").apply { mkdirs() }
        java.io.File(directory, "dark-search.png").outputStream().use { search.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun dismissalMovesTheSheetAndFadesBeforeRemovingIt() {
        var dismissed = 0
        compose.setContent {
            AppTheme {
                Box(Modifier.fillMaxSize().background(DesignTokens.Background)) {
                    MenuBottomSheet(listOf(MenuSheetItem(null, "Share", "share-row")), { dismissed++ }, {})
                }
            }
        }
        compose.waitForIdle()
        val initialTop = compose.onNodeWithTag("video-menu").fetchSemanticsNode().boundsInRoot.top
        val initialPixel = compose.onRoot().captureToImage().asAndroidBitmap().getPixel(10, 10)
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("Close menu").performClick()
        compose.mainClock.advanceTimeBy(80)
        compose.waitForIdle()
        assertEquals(0, dismissed)
        compose.onNodeWithTag("share-row").assertIsNotEnabled()
        assertTrue(compose.onNodeWithTag("video-menu").fetchSemanticsNode().boundsInRoot.top > initialTop + 1)
        val halfway = compose.onRoot().captureToImage().asAndroidBitmap().getPixel(10, 10)
        assertTrue(android.graphics.Color.red(halfway) > android.graphics.Color.red(initialPixel))
        compose.mainClock.advanceTimeBy(240)
        compose.runOnIdle { assertEquals(1, dismissed) }
        compose.mainClock.autoAdvance = true
    }

    @Test fun filledButtonShowsPressFeedbackAndDisabledButtonCannotRun() {
        var enabled by mutableStateOf(true)
        var clicks = 0
        compose.setContent {
            AppTheme {
                AppIconButton(AppIcon.Search, "Search", Modifier.testTag("filled-button"),
                    background = Color(0xFFEEEEEE), enabled = enabled) { clicks++ }
            }
        }
        val node = compose.onNodeWithTag("filled-button")
        val initial = node.captureToImage().asAndroidBitmap()
        node.performTouchInput { down(center) }
        compose.waitForIdle()
        val pressed = node.captureToImage().asAndroidBitmap()
        assertFalse("Opaque backgrounds must not cover the press layer", initial.sameAs(pressed))
        node.performTouchInput { up() }
        compose.runOnIdle { assertEquals(1, clicks); enabled = false }
        node.assertIsNotEnabled()
        node.performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, clicks) }
    }
}
