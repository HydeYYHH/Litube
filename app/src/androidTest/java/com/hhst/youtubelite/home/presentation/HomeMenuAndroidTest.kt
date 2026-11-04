package com.hhst.youtubelite.home.presentation

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.hhst.youtubelite.home.domain.*
import com.hhst.youtubelite.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class HomeMenuAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun deferredOptionsLoadAndRetryWithoutBlockingExistingActions() {
        var state by mutableStateOf(HomeOverlay.Menu("video", loading = true))
        var video by mutableStateOf(FeedItem.Video("video", "Title", "Author", "", "", "", "channel"))
        val actions = mutableListOf<HomeAction>()
        compose.setContent { AppTheme { VideoMenu(actions::add, live = true, signedIn = true, video = video, state = state) } }
        compose.onNodeWithTag("home-menu-loading").assertIsDisplayed()
        compose.onNodeWithText("Add to queue").assertIsDisplayed()
        compose.onNodeWithText("Not interested").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(loading = false, failed = true) }
        compose.onNodeWithTag("home-menu-retry").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(HomeAction.OpenMenu("video"), actions.single())
            video = video.copy(notInterestedToken = "video-token", dontRecommendToken = "channel-token")
            state = state.copy(failed = false)
        }
        compose.onNodeWithTag("home-menu-retry").assertDoesNotExist()
        compose.onNodeWithText("Not interested").assertIsDisplayed()
        compose.onNodeWithText("Don't recommend channel").assertIsDisplayed()
        compose.onNodeWithText("Add to queue").performClick()
        compose.runOnIdle { assertEquals(HomeAction.MenuSelected(FeedMenuAction.Queue), actions.last()) }
    }
}
