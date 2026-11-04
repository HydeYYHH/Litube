package com.hhst.youtubelite.home

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.hhst.youtubelite.home.domain.*
import com.hhst.youtubelite.home.presentation.HomeScreen
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class HomePreloadAndroidTest {
    @get:Rule val compose = createComposeRule()
    private val videos = (0..25).map { FeedItem.Video("video-$it", "Video $it", "Channel", "Views", "bunny", "1:00", "channel") }

    @Test fun settledViewportRetargetsPreparationBeforeAClick() {
        val warmed=mutableListOf<String>()
        compose.mainClock.autoAdvance=false
        compose.setContent { HomeScreen(HomeUiState(content=HomeContent.Feed(HomePage(videos))), {}, onWarmCandidate={warmed+=it}) }
        compose.mainClock.advanceTimeBy(1200);compose.waitForIdle()
        compose.runOnIdle { assertEquals(listOf("video-0"),warmed) }
        compose.onNodeWithTag("home-feed").performScrollToIndex(12)
        compose.mainClock.advanceTimeBy(1200);compose.waitForIdle()
        compose.runOnIdle { assertTrue(warmed.size>=2);assertNotEquals("video-0",warmed.last()) }
        compose.onNodeWithTag("feed-item-${warmed.last()}").assertIsDisplayed()
    }

    @Test fun anOverlayCancelsPendingSpeculation() {
        val warmed=mutableListOf<String>()
        var state by mutableStateOf(HomeUiState(content=HomeContent.Feed(HomePage(videos))))
        compose.mainClock.autoAdvance=false
        compose.setContent { HomeScreen(state, {}, onWarmCandidate={warmed+=it}) }
        compose.mainClock.advanceTimeBy(200);compose.waitForIdle()
        compose.runOnIdle { state=state.copy(overlay=HomeOverlay.Search) }
        compose.mainClock.advanceTimeBy(1500);compose.waitForIdle()
        compose.runOnIdle { assertTrue(warmed.isEmpty()) }
    }

    @Test fun leavingHomeCancelsPendingPreparationAndReturningCanPrepareAgain() {
        val warmed=mutableListOf<String>()
        lateinit var owner:LifecycleOwner
        compose.mainClock.autoAdvance=false
        compose.setContent {
            val visible=remember { object:LifecycleOwner {
                override val lifecycle=LifecycleRegistry(this).apply { currentState=Lifecycle.State.RESUMED }
            } }
            owner=visible
            CompositionLocalProvider(LocalLifecycleOwner provides visible) {
                HomeScreen(HomeUiState(content=HomeContent.Feed(HomePage(videos))),{},onWarmCandidate={warmed+=it})
            }
        }
        compose.mainClock.advanceTimeBy(200);compose.waitForIdle()
        compose.runOnIdle { (owner.lifecycle as LifecycleRegistry).currentState=Lifecycle.State.CREATED }
        compose.mainClock.advanceTimeBy(1500);compose.waitForIdle()
        compose.runOnIdle { assertTrue(warmed.isEmpty());(owner.lifecycle as LifecycleRegistry).currentState=Lifecycle.State.RESUMED }
        compose.mainClock.advanceTimeBy(1200);compose.waitForIdle()
        compose.runOnIdle { assertEquals(listOf("video-0"),warmed) }
    }
}
