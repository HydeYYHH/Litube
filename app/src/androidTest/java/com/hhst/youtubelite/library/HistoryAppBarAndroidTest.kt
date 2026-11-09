package com.hhst.youtubelite.library

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.hhst.youtubelite.library.domain.*
import com.hhst.youtubelite.library.presentation.HistoryScreen
import org.junit.Rule
import org.junit.Test

class HistoryAppBarAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun show(count: Int) {
        val entries = (0 until count).map { index -> HistoryEntry("history-$index", "video-$index",
            "Video $index", "Author", "channel", "bunny", "2:00", section = "History") }
        compose.setContent { HistoryScreen(HistoryUiState(content = HistoryContent.Ready(HistoryPage(entries))), {}, {}) }
    }

    @Test fun longHistoryHidesToolbarAndRevealsItWhenDirectionReverses() {
        show(40)
        compose.onAllNodesWithText("History").assertCountEquals(1)
        compose.onNodeWithTag("history-list").performTouchInput { swipeUp() }
        compose.onNodeWithTag("library-subpage-bar").assertIsNotDisplayed()
        compose.onNodeWithTag("history-list").performTouchInput { swipeDown() }
        compose.onNodeWithTag("library-subpage-bar").assertIsDisplayed()
    }

    @Test fun shortHistoryKeepsNavigationVisible() {
        show(1)
        compose.onNodeWithTag("history-list").performTouchInput { swipeUp() }
        compose.onNodeWithTag("library-subpage-bar").assertIsDisplayed()
        compose.onAllNodesWithText("History").assertCountEquals(1)
    }
}
