package com.hhst.youtubelite.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.hhst.youtubelite.ui.design.AppText
import com.hhst.youtubelite.ui.design.AppTheme
import com.hhst.youtubelite.ui.design.RefreshableContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Exercises real nested scrolling, including a body with no scrollable children. */
class PullRefreshAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var loading by mutableStateOf(false)
    private var body by mutableStateOf("Original content")
    private var enabled by mutableStateOf(true)
    private var atTop by mutableStateOf(true)
    private var identity by mutableStateOf(0)
    private var refreshes = 0

    private fun host() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                AppTheme {
                    Box(Modifier.requiredSize(390.dp, 760.dp)) {
                        RefreshableContent(body, loading, {
                            refreshes++
                            body = "Loading replacement"
                            loading = true
                        }, Modifier.fillMaxSize().testTag("refresh-body"),
                            enabled = enabled, contentKey = identity, canPull = { atTop }, tag = "test-refresh") { text ->
                            AppText(text, Modifier.testTag("painted-content"))
                        }
                    }
                }
            }
        }
    }

    private fun pull() = compose.onNodeWithTag("refresh-body").performTouchInput {
        swipe(Offset(center.x, 120f), Offset(center.x, 640f))
    }

    @Test fun contentFollowsTheFingerThenStaysUntilRefreshCompletes() {
        host()
        val originalTop = compose.onNodeWithTag("painted-content").fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithTag("refresh-body").performTouchInput {
            down(Offset(center.x, 120f))
            moveBy(Offset(0f, 350f))
        }
        compose.waitForIdle()
        val pulledTop = compose.onNodeWithTag("painted-content").fetchSemanticsNode().boundsInRoot.top
        assertTrue(pulledTop > originalTop + 56f)
        assertEquals(0, refreshes)
        compose.mainClock.advanceTimeBy(600)
        assertEquals(pulledTop, compose.onNodeWithTag("painted-content").fetchSemanticsNode().boundsInRoot.top, 0.5f)
        compose.onNodeWithTag("refresh-body").performTouchInput { up() }
        compose.waitForIdle()
        assertEquals(1, refreshes)
        compose.onNodeWithText("Original content").assertIsDisplayed()
        compose.onNodeWithText("Loading replacement").assertDoesNotExist()
        assertEquals(originalTop + 56f, compose.onNodeWithTag("painted-content").fetchSemanticsNode().boundsInRoot.top, 1f)
        pull()
        assertEquals(1, refreshes)
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { body = "Updated content"; loading = false }
        var sawIntermediatePosition = false
        val positions = mutableListOf<Float>()
        for (frame in 0 until 30) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            val top = compose.onNodeWithTag("painted-content").fetchSemanticsNode().boundsInRoot.top
            positions += top
            if (top > originalTop + 0.5f && top < originalTop + 55.5f) {
                sawIntermediatePosition = true
                break
            }
        }
        compose.onNodeWithText("Updated content").assertIsDisplayed()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithText("Updated content").assertIsDisplayed()
        assertEquals("A pull made while busy must not become a new request on completion", 1, refreshes)
        compose.onNodeWithTag("test-refresh-indicator").assertDoesNotExist()
        assertEquals(originalTop, compose.onNodeWithTag("painted-content").fetchSemanticsNode().boundsInRoot.top, 0.5f)
        assertTrue("Completion must animate through intermediate positions: $positions", sawIntermediatePosition)
    }

    @Test fun shortAndCancelledPullsReturnWithoutRequests() {
        host()
        compose.onNodeWithTag("refresh-body").performTouchInput {
            swipe(Offset(center.x, 120f), Offset(center.x, 160f))
        }
        compose.waitForIdle()
        compose.onNodeWithTag("test-refresh-indicator").assertDoesNotExist()
        compose.onNodeWithTag("refresh-body").performTouchInput {
            down(Offset(center.x, 120f))
            moveBy(Offset(0f, 350f))
            cancel()
        }
        compose.waitForIdle()
        assertEquals(0, refreshes)
        compose.onNodeWithTag("test-refresh-indicator").assertDoesNotExist()
    }

    @Test fun disabledAndNonTopBodiesDoNotRefresh() {
        host()
        compose.runOnIdle { atTop = false }
        pull()
        compose.runOnIdle { atTop = true; enabled = false }
        pull()
        compose.waitForIdle()
        assertEquals(0, refreshes)
        compose.onNodeWithTag("test-refresh-indicator").assertDoesNotExist()
    }

    @Test fun changingIdentityDuringRefreshNeverRetainsTheOldBody() {
        host()
        pull()
        compose.onNodeWithText("Original content").assertIsDisplayed()
        compose.runOnIdle { identity++; body = "Another account" }
        compose.waitForIdle()
        compose.onNodeWithText("Original content").assertDoesNotExist()
        compose.onNodeWithText("Another account").assertIsDisplayed()
        compose.onNodeWithTag("test-refresh-indicator").assertDoesNotExist()
    }
}
