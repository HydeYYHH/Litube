package com.hhst.youtubelite.library

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.hhst.youtubelite.library.domain.*
import com.hhst.youtubelite.library.presentation.YouScreen
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Library page follows the session identity: signed in shows the account lane, anonymous hides it. */
class LibraryIdentityAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun page() = YouPage(AccountProfile("Litube Studio", "@litube", "sintel", "litube"),
        com.hhst.youtubelite.library.data.FixtureLibraryRepository.historyEntries(),
        com.hhst.youtubelite.library.data.FixtureLibraryRepository.collections())

    @Test fun signedInLibraryShowsTheLikedRowAndNoSourceSwitch() {
        val actions = mutableListOf<LibraryAction>()
        compose.setContent {
            YouScreen(LibraryUiState(signedIn = true, content = LibraryContent.Ready(page())), actions::add)
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("you-library").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithTag("you-source").assertCountEquals(0)
        compose.onNodeWithTag("library-row-LikedVideos").assertExists()
        compose.onNodeWithTag("library-row-WatchLater").assertExists()
    }

    @Test fun theLibraryPageNeverRendersASourceSwitch() {
        compose.setContent {
            YouScreen(LibraryUiState(signedIn = false, content = LibraryContent.Ready(page())), {})
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("you-library").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithTag("you-source").assertCountEquals(0)
        compose.onAllNodesWithTag("library-row-LikedVideos").assertCountEquals(0)
        compose.onNodeWithTag("library-row-WatchLater").assertExists()
    }
}
