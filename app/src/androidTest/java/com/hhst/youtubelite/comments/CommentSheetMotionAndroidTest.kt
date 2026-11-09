package com.hhst.youtubelite.comments.presentation

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.hhst.youtubelite.comments.domain.CommentsContent
import com.hhst.youtubelite.comments.domain.CommentsUiState
import com.hhst.youtubelite.ui.design.AppTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CommentSheetMotionAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun smallFastDragsBounceAndThresholdFeedbackDoesNotRepeat() {
        lateinit var motion: CommentSheetMotion
        var closes = 0
        var pulses = 0
        compose.setContent {
            val scope = rememberCoroutineScope()
            motion = remember { CommentSheetMotion(scope, 600f, 1f, { true }, { true }, { closes++ },
                onThreshold = { pulses++ }) }
            Box(Modifier.size(100.dp))
        }
        compose.runOnIdle {
            assertEquals(20f, motion.drag(20f), .001f)
            assertTrue(motion.offset < 20f)
            motion.settle(4_000f)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(0, closes)
            assertEquals(0f, motion.offset, .01f)
            assertEquals(0, pulses)
            motion.drag(160f)
            assertEquals(1, pulses)
            motion.drag(-40f)
            motion.drag(40f)
            assertEquals(1, pulses)
            motion.settle(0f)
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, closes); assertEquals(1, pulses) }
    }

    @Test fun systemBackKeepsCompactCommentsUntilTheExitCompletes() {
        var open by mutableStateOf(true)
        var closes = 0
        compose.mainClock.autoAdvance = false
        compose.setContent { AppTheme { CompositionLocalProvider(LocalDensity provides Density(1f)) {
            if (open) CommentsPanel(CommentsUiState(videoId = "comments-motion", content = CommentsContent.Empty),
                "0", {}, { closes++; open = false }, Modifier.requiredSize(390.dp, 844.dp))
        } } }
        compose.mainClock.advanceTimeBy(240)
        compose.runOnIdle {
            compose.activity.onBackPressedDispatcher.onBackPressed()
            compose.activity.onBackPressedDispatcher.onBackPressed()
        }
        compose.mainClock.advanceTimeBy(16)
        compose.onNodeWithTag("comments-panel").assertExists()
        compose.runOnIdle { assertEquals(0, closes) }
        compose.mainClock.advanceTimeBy(200)
        compose.onNodeWithTag("comments-panel").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, closes) }
        compose.mainClock.autoAdvance = true
    }

    @Test fun entryAndRepeatedBackRetainOneExitAcrossKeyboardResize() {
        lateinit var motion: CommentSheetMotion
        var closes = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val scope = rememberCoroutineScope()
            motion = remember { CommentSheetMotion(scope, 600f, 1f, { true }, { true }, { closes++ }, startHidden = true) }
            LaunchedEffect(motion) { motion.enter() }
            DisposableEffect(motion) { onDispose { motion.cancel() } }
            Box(Modifier.size((600f - motion.offset).coerceAtLeast(1f).dp))
        }
        compose.mainClock.advanceTimeBy(64)
        compose.runOnIdle {
            assertTrue("Compact comments must enter from below", motion.offset > 0f && motion.offset < 600f)
            val fraction = motion.offset / 600f
            motion.resize(420f)
            assertEquals("Keyboard resize preserves the displayed fraction", fraction, motion.offset / 420f, 0.001f)
            motion.dismiss()
        }
        repeat(5) {
            compose.mainClock.advanceTimeBy(16)
            compose.runOnIdle { motion.dismiss() }
        }
        compose.mainClock.advanceTimeBy(160)
        compose.runOnIdle { assertEquals(1, closes); assertEquals(420f, motion.offset, 0.01f) }
        compose.mainClock.autoAdvance = true
    }

    @Test fun removingThePanelCancelsItsPendingDismissal() {
        lateinit var motion: CommentSheetMotion
        var open by mutableStateOf(true)
        var closes = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            if (open) {
                val scope = rememberCoroutineScope()
                motion = remember { CommentSheetMotion(scope, 600f, 1f, { true }, { true }, { closes++ }) }
                DisposableEffect(motion) { onDispose { motion.cancel() } }
                Box(Modifier.size(100.dp))
            }
        }
        compose.mainClock.advanceTimeBy(32)
        compose.runOnIdle { motion.dismiss() }
        compose.mainClock.advanceTimeBy(32)
        compose.runOnIdle { open = false }
        compose.mainClock.advanceTimeBy(240)
        compose.runOnIdle { assertEquals(0, closes) }
        compose.mainClock.autoAdvance = true
    }
}
