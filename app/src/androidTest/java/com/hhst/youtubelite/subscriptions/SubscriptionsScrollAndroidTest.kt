package com.hhst.youtubelite.subscriptions

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.hhst.youtubelite.subscriptions.data.FixtureSubscriptionsRepository
import com.hhst.youtubelite.subscriptions.domain.*
import com.hhst.youtubelite.subscriptions.presentation.SubscriptionsScreen
import com.hhst.youtubelite.ui.design.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class SubscriptionsScrollAndroidTest(private val width: Int, private val fontScale: Float) {
    @get:Rule val compose = createComposeRule()

    private val channels = FixtureSubscriptionsRepository.channels()
    private val videos = (0 until 24).map { index ->
        FixtureSubscriptionsRepository.sampleVideos().first().copy(id = "scroll-video-$index")
    }
    private val state = mutableStateOf(SubscriptionsUiState(signedIn = true,
        channels = SubscriptionsChannels.Ready(channels),
        content = SubscriptionsContent.Feed(SubscriptionsPage(videos, channels))))
    private var refreshes = 0

    private fun show() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                AppTheme {
                    Box(Modifier.requiredSize(width.dp, 844.dp)) {
                        SubscriptionsScreen(state.value, { if (it == SubscriptionsAction.Retry) refreshes++ })
                    }
                }
            }
        }
    }

    @Test fun browsingHidesTheWholeHeaderAndReverseScrollRestoresIt() {
        show()
        val feedBefore = compose.onNodeWithTag("subscriptions-feed").fetchSemanticsNode().boundsInRoot
        val navBefore = compose.onNodeWithTag("main-navigation").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("subscriptions-feed").performTouchInput { swipeUp() }
        listOf("subscriptions-topbar", "subscriptions-channels", "subscriptions-chips").forEach {
            compose.onNodeWithTag(it).assertIsNotDisplayed()
        }
        val feedAfter = compose.onNodeWithTag("subscriptions-feed").fetchSemanticsNode().boundsInRoot
        assertTrue("The feed must reclaim the header space", feedAfter.top < feedBefore.top - 100f)
        assertEquals(navBefore, compose.onNodeWithTag("main-navigation").fetchSemanticsNode().boundsInRoot)
        // A short reverse drag reveals the controls before returning to the first row.
        compose.onNodeWithTag("subscriptions-feed").performTouchInput {
            swipe(Offset(center.x, 100f), Offset(center.x, 180f), durationMillis = 500)
        }
        listOf("subscriptions-topbar", "subscriptions-channels", "subscriptions-chips").forEach {
            compose.onNodeWithTag(it).assertIsDisplayed()
        }
        assertEquals(0, refreshes)
    }

    @Test fun changingFilterRestoresTheHeaderAndFeedStart() {
        show()
        compose.onNodeWithTag("subscriptions-feed").performTouchInput { swipeUp() }
        compose.onNodeWithTag("subscriptions-topbar").assertIsNotDisplayed()
        compose.runOnIdle { state.value = state.value.copy(selectedChip = SubscriptionsChip.Today) }
        compose.onNodeWithTag("subscriptions-topbar").assertIsDisplayed()
        compose.onNodeWithTag("subscriptions-chips").assertIsDisplayed()
        compose.onNodeWithTag("subscriptions-feed").performTouchInput { swipeDown() }
        compose.waitForIdle()
        assertEquals("The reset feed must be at the refresh edge", 1, refreshes)
    }

    @Test fun horizontalStripAndPullRefreshKeepTheHeaderVisible() {
        show()
        compose.onNodeWithTag("subscriptions-chips").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("subscriptions-topbar").assertIsDisplayed()
        compose.onNodeWithTag("subscriptions-feed").performTouchInput { swipeDown() }
        compose.onNodeWithTag("subscriptions-topbar").assertIsDisplayed()
        compose.onNodeWithTag("subscriptions-channels").assertIsDisplayed()
        assertEquals(1, refreshes)
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "width={0},font={1}")
        fun configurations() = listOf(arrayOf<Any>(390, 1f), arrayOf<Any>(390, 2f), arrayOf<Any>(800, 1f))
    }
}
