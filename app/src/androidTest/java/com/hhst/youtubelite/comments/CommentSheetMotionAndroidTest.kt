package com.hhst.youtubelite.comments

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.hhst.youtubelite.comments.data.FixtureCommentsRepository
import com.hhst.youtubelite.comments.domain.CommentSort
import com.hhst.youtubelite.comments.domain.CommentsContent
import com.hhst.youtubelite.comments.domain.CommentsOverlay
import com.hhst.youtubelite.comments.domain.CommentsUiState
import com.hhst.youtubelite.comments.presentation.CommentsPanel
import com.hhst.youtubelite.ui.design.AppTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CommentSheetMotionAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val overlay = mutableStateOf<CommentsOverlay>(CommentsOverlay.None)
    private lateinit var list: androidx.compose.foundation.lazy.LazyListState
    private var closes = 0

    private fun show() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                AppTheme {
                    list = rememberLazyListState()
                    Box(Modifier.requiredSize(390.dp, 844.dp)) {
                        CommentsPanel(CommentsUiState(videoId = "bunny", overlay = overlay.value,
                            content = CommentsContent.Ready(FixtureCommentsRepository.sampleThreads(CommentSort.Top), null)),
                            "20", {}, { closes++ }, listState = list)
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(32)
    }

    private fun top() = compose.onNodeWithTag("comments-panel").fetchSemanticsNode().boundsInRoot.top

    @Test fun headerTracksTheFingerThenSettlesBeforeDismissal() {
        show()
        val initial = top()
        val header = compose.onNodeWithTag("comments-header")
        header.performTouchInput { down(Offset(30f, 30f)); moveBy(Offset(0f, 180f), 300) }
        compose.mainClock.advanceTimeBy(16)
        assertTrue("The sheet must move before release", top() > initial + 140f)
        assertEquals(0, closes)
        header.performTouchInput { advanceEventTime(120); up() }
        compose.mainClock.advanceTimeBy(80)
        assertEquals("Dismissal waits for the exit animation", 0, closes)
        compose.mainClock.advanceTimeBy(300)
        compose.runOnIdle { assertEquals(1, closes) }
    }

    @Test fun aShortDragReturnsToTheSamePosition() {
        show()
        val initial = top()
        val header = compose.onNodeWithTag("comments-header")
        header.performTouchInput { down(Offset(30f, 30f)); moveBy(Offset(0f, 40f), 400) }
        compose.mainClock.advanceTimeBy(16)
        assertTrue(top() > initial)
        header.performTouchInput { advanceEventTime(200); up() }
        compose.mainClock.advanceTimeBy(1000)
        assertEquals(initial, top(), .5f)
        assertEquals(0, closes)
    }

    @Test fun listScrollingOnlyHandsOffAtItsStart() {
        show()
        compose.runOnIdle { runBlocking { list.scrollToItem(2) } }
        val initial = top()
        compose.onNodeWithTag("comments-list").performTouchInput {
            down(Offset(30f, 180f)); moveBy(Offset(0f, 60f), 400); advanceEventTime(120); up()
        }
        compose.mainClock.advanceTimeBy(500)
        assertEquals(initial, top(), .5f)
        assertEquals(0, closes)
        compose.runOnIdle { runBlocking { list.scrollToItem(0) } }
        compose.onNodeWithTag("comments-list").performTouchInput {
            down(Offset(30f, 80f)); moveBy(Offset(0f, 190f), 400); advanceEventTime(120); up()
        }
        compose.mainClock.advanceTimeBy(400)
        assertEquals(1, closes)
    }

    @Test fun modalMenusKeepTheParentSheetStationary() {
        show()
        compose.runOnIdle { overlay.value = CommentsOverlay.SortMenu }
        val initial = top()
        compose.onNodeWithTag("comments-header").performTouchInput {
            down(Offset(30f, 30f)); moveBy(Offset(0f, 200f), 300); up()
        }
        compose.mainClock.advanceTimeBy(500)
        assertEquals(initial, top(), .5f)
        assertEquals(0, closes)
    }
}
