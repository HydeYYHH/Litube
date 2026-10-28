package com.hhst.youtubelite.library

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.hhst.youtubelite.library.domain.*
import com.hhst.youtubelite.library.presentation.YouScreen
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** The device/account switch on the library page, composed directly so no session is needed. */
class LibrarySourceAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun page() = YouPage(AccountProfile("Litube Studio", "@litube", "sintel", "litube"),
        com.hhst.youtubelite.library.data.FixtureLibraryRepository.historyEntries(),
        com.hhst.youtubelite.library.data.FixtureLibraryRepository.collections())

    @Test fun signedInLibraryOffersTheSourceSwitchAndRoutesTaps() {
        val actions = mutableListOf<LibraryAction>()
        compose.setContent {
            YouScreen(LibraryUiState(signedIn = true, content = LibraryContent.Ready(page()),
                source = LibrarySource.Account, sourceSelectable = true), actions::add)
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("you-library").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("you-source").assertIsDisplayed()
        compose.onNodeWithTag("you-source-account").assertIsDisplayed()
        // The device option sits one row down; scroll it into view and select it.
        compose.onNodeWithTag("you-library").performScrollToNode(hasTestTag("you-source-device"))
        compose.onNodeWithTag("you-source-device").performClick()
        assertEquals(listOf(LibraryAction.SourceSelected(LibrarySource.Device)), actions)
    }

    @Test fun deviceSourceHidesTheAccountOnlyLikedRow() {
        compose.setContent {
            YouScreen(LibraryUiState(signedIn = true, content = LibraryContent.Ready(page()),
                source = LibrarySource.Device, sourceSelectable = true), {})
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("you-library").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("you-source").assertIsDisplayed()
        compose.onAllNodesWithTag("library-row-LikedVideos").assertCountEquals(0)
        compose.onNodeWithTag("library-row-WatchLater").assertExists()
    }

    @Test fun withoutAStoreThereIsNoSwitchToRender() {
        compose.setContent {
            YouScreen(LibraryUiState(signedIn = true, content = LibraryContent.Ready(page())), {})
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("you-library").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithTag("you-source").assertCountEquals(0)
        compose.onNodeWithTag("library-row-LikedVideos").assertExists()
    }
}
