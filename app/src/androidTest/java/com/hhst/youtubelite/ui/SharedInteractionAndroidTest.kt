package com.hhst.youtubelite.ui

import android.content.ContextWrapper
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.hhst.youtubelite.ui.design.*
import com.hhst.youtubelite.ui.components.WatchSheetHost
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.After

class SharedInteractionAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val appearance = com.hhst.youtubelite.ui.theme.AppAppearance.mode
    @After fun restore() { com.hhst.youtubelite.ui.theme.AppAppearance.update(appearance) }

    @Test fun darkSelectedControlsAndSearchStayReadable() {
        com.hhst.youtubelite.ui.theme.ThemeMode.Dark.let { com.hhst.youtubelite.ui.theme.AppAppearance.update(it) }
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
        // With "remove animations" (animator scale 0) the sheet closes instantly; mid-flight assertions only apply to the animated path.
        val motion = readMotionScale(compose.activity.contentResolver)
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
        compose.onNodeWithContentDescription(compose.activity.getString(com.hhst.youtubelite.R.string.a11y_close_menu)).performClick()
        compose.mainClock.advanceTimeBy(80)
        compose.waitForIdle()
        assertEquals(if (motion > 0f) 0 else 1, dismissed)
        if (motion > 0f) {
            compose.onNodeWithTag("share-row").assertIsNotEnabled()
            assertTrue(compose.onNodeWithTag("video-menu").fetchSemanticsNode().boundsInRoot.top > initialTop + 1)
            val halfway = compose.onRoot().captureToImage().asAndroidBitmap().getPixel(10, 10)
            assertTrue(android.graphics.Color.red(halfway) > android.graphics.Color.red(initialPixel))
        }
        compose.mainClock.advanceTimeBy(240)
        compose.runOnIdle { assertEquals(1, dismissed) }
        compose.mainClock.autoAdvance = true
    }

    @Test fun watchMenuWrapsContentAndScrollsWithinThePlayerBoundary() {
        var rowCount by mutableIntStateOf(7)
        var fillWatchHeight by mutableStateOf(true)
        var availableHeight by mutableIntStateOf(420)
        var containerBottom by mutableIntStateOf(0)
        val host = object : ContextWrapper(compose.activity), WatchSheetHost {
            override val sheetPlayerBottomPx: Int get() = containerBottom - availableHeight
            override val sheetWindowHeightPx: Int get() = 560
        }
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f), LocalContext provides host) {
                AppTheme {
                    Box(Modifier.requiredSize(320.dp, 560.dp).testTag("menu-viewport")
                        .onGloballyPositioned { containerBottom = it.boundsInWindow().bottom.toInt() }) {
                        val items = List(rowCount) { MenuSheetItem(null, "Action $it", "action-$it") }
                        if (fillWatchHeight) MenuBottomSheet(items, {}, {})
                        else MenuBottomSheet(items, {}, {}, fillWatchHeight = false)
                    }
                }
            }
        }
        compose.waitForIdle()
        val initialViewport = compose.onNodeWithTag("menu-viewport").fetchSemanticsNode().boundsInRoot
        val initialSheet = compose.onNodeWithTag("video-menu").fetchSemanticsNode().boundsInRoot
        assertEquals("Menus retain the full watch height by default", initialViewport.bottom - 420f, initialSheet.top, 1f)
        compose.runOnIdle { fillWatchHeight = false }
        fun assertCompact(lastRow: Int) {
            val sheet = compose.onNodeWithTag("video-menu").fetchSemanticsNode().boundsInRoot
            val last = compose.onNodeWithTag("action-$lastRow").fetchSemanticsNode().boundsInRoot
            assertEquals("No empty panel below the final action", last.bottom, sheet.bottom, 1f)
            val viewport = compose.onNodeWithTag("menu-viewport").fetchSemanticsNode().boundsInRoot
            assertEquals("The compact menu remains bottom aligned", viewport.bottom - 24f, sheet.bottom, 1f)
        }
        assertCompact(6)

        // A shorter viewport must keep the player clear and remaining actions reachable.
        compose.runOnIdle { availableHeight = 160; rowCount = 20 }
        val viewport = compose.onNodeWithTag("menu-viewport").fetchSemanticsNode().boundsInRoot
        val sheet = compose.onNodeWithTag("video-menu").fetchSemanticsNode().boundsInRoot
        assertEquals("Long menus stop at the player boundary", viewport.bottom - 160f, sheet.top, 1f)
        compose.onNodeWithTag("action-19").performScrollTo().assertIsDisplayed()

        // Replacing a long menu must not retain its expanded height or scroll position.
        compose.runOnIdle { availableHeight = 420; rowCount = 1 }
        compose.onNodeWithTag("action-0").assertIsDisplayed()
        assertCompact(0)
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
