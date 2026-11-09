package com.hhst.youtubelite.shorts

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performTouchInput
import com.hhst.youtubelite.shorts.domain.ShortItem
import com.hhst.youtubelite.shorts.domain.ShortsContent
import com.hhst.youtubelite.shorts.domain.ShortsPage
import com.hhst.youtubelite.shorts.domain.ShortsUiState
import com.hhst.youtubelite.shorts.presentation.ShortsScreen
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class ShortFramePresentationAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun losingTheFrameRestoresAnOpaqueCoverInTheNextDraw() {
        assumeTrue(android.os.Build.VERSION.SDK_INT >= 26)
        val ready = mutableStateOf(true)
        val item = ShortItem("frame", "A short", "Author", "channel", "", "", "", "")
        var opaqueCover = Color.Unspecified
        compose.mainClock.autoAdvance = false
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                com.hhst.youtubelite.ui.design.LocalMotionScale provides 1f,
            ) {
                opaqueCover = Color.Black
                ShortsScreen(
                    state = ShortsUiState(content = ShortsContent.Ready(ShortsPage(listOf(item))), currentItemId = item.id),
                    onAction = {},
                    videoContent = { Box(Modifier.fillMaxSize().background(Color.Red)) },
                    isVideoReady = { ready.value },
                )
            }
        }
        compose.mainClock.advanceTimeByFrame()
        val page = compose.onNodeWithTag("short-page-frame")
        val before = page.captureToImage().toPixelMap()
        assertEquals(Color.Red, before[before.width / 2, before.height / 3])
        compose.runOnIdle { ready.value = false }
        compose.mainClock.advanceTimeByFrame()
        val covered = page.captureToImage().toPixelMap()
        assertEquals("A missing frame must not fade through the stale surface", opaqueCover,
            covered[covered.width / 2, covered.height / 3])
        // Interrupt the reveal before it finishes, as a fast swipe/surface swap can.
        compose.runOnIdle { ready.value = true }
        compose.mainClock.advanceTimeBy(48)
        compose.runOnIdle { ready.value = false }
        compose.mainClock.advanceTimeByFrame()
        val interrupted = page.captureToImage().toPixelMap()
        assertEquals("An interrupted reveal must immediately restore the cover", opaqueCover,
            interrupted[interrupted.width / 2, interrupted.height / 3])
        compose.runOnIdle { ready.value = true }
        compose.mainClock.advanceTimeBy(200)
        compose.onNodeWithTag("short-cover-frame").assertDoesNotExist()
        compose.mainClock.autoAdvance = true
    }

    @Test fun replacingTheVideoCannotInheritThePreviousVideosReveal() {
        val original = ShortItem("first", "First", "Author", "channel", "", "", "", "")
        val item = mutableStateOf(original)
        compose.setContent {
            ShortsScreen(
                state = ShortsUiState(content = ShortsContent.Ready(ShortsPage(listOf(item.value))), currentItemId = item.value.id),
                onAction = {},
                videoContent = { Box(Modifier.fillMaxSize().background(Color.Red)) },
                isVideoReady = { it == original.id },
            )
        }
        compose.onNodeWithTag("short-cover-first").assertDoesNotExist()
        compose.runOnIdle { item.value = original.copy(id = "replacement") }
        compose.onNodeWithTag("short-page-first").assertDoesNotExist()
        compose.onNodeWithTag("short-cover-replacement").assertIsDisplayed()
    }

    @Test fun coverWaitsForTheFirstFrameWithoutRemountingTheVideo() {
        val ready = mutableStateOf(false)
        var mounts = 0
        var disposals = 0
        val item = ShortItem("frame", "A short", "Author", "channel", "", "", "", "")
        compose.setContent {
            ShortsScreen(
                state = ShortsUiState(
                    content = ShortsContent.Ready(ShortsPage(listOf(item))),
                    currentItemId = item.id,
                ),
                onAction = {},
                videoContent = {
                    DisposableEffect(Unit) {
                        mounts++
                        onDispose { disposals++ }
                    }
                    Box(Modifier.fillMaxSize().testTag("attached-video"))
                },
                isVideoReady = { ready.value },
            )
        }
        compose.onNodeWithTag("attached-video").assertIsDisplayed()
        compose.onNodeWithTag("short-cover-frame").assertIsDisplayed()
        compose.runOnIdle { ready.value = true }
        compose.onNodeWithTag("short-cover-frame").assertDoesNotExist()
        compose.onNodeWithTag("attached-video").assertIsDisplayed()
        compose.runOnIdle { ready.value = false }
        compose.onNodeWithTag("short-cover-frame").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, mounts)
            assertEquals(0, disposals)
        }
    }

    @Test fun swipingToAnUnpreparedNeighborDoesNotExposeItsPoster() {
        val items = listOf(
            ShortItem("first", "First", "Author", "channel", "dream", "", "", ""),
            ShortItem("second", "Second", "Author", "channel", "bunny", "", "", ""),
        )
        compose.setContent {
            ShortsScreen(ShortsUiState(content = ShortsContent.Ready(ShortsPage(items)), currentItemId = "first"),
                {}, videoContent = { Box(Modifier.fillMaxSize().background(Color.Red)) },
                isVideoReady = { it == "first" })
        }
        compose.onNodeWithTag("shorts-pager").performTouchInput {
            down(center)
            moveBy(Offset(0f, -height * 0.6f), 200)
        }
        compose.onNodeWithTag("short-cover-second").assertIsDisplayed()
        compose.onNodeWithContentDescription("Play Second").assertDoesNotExist()
        val pixels = compose.onNodeWithTag("short-cover-second").captureToImage().toPixelMap()
        assertEquals("An undecoded neighbor must stay black rather than display a poster",
            Color.Black, pixels[pixels.width / 2, pixels.height / 3])
        compose.onNodeWithTag("shorts-pager").performTouchInput { up() }
    }
}
