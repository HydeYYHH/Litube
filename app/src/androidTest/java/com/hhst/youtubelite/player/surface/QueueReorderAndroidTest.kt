package com.hhst.youtubelite.player.surface

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.hhst.youtubelite.player.QueueItem
import com.hhst.youtubelite.core.DeviceEvidence
import com.hhst.youtubelite.player.engine.LoopMode
import com.hhst.youtubelite.ui.theme.AppTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class QueueReorderAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val source = mutableStateOf((0 until 30).map {
        QueueItem(videoId = "q$it", title = "Queue entry $it", author = "Author")
    })
    private val moves = mutableListOf<Pair<Int, Int>>()
    private var plays = 0

    private fun show(currentId: String? = null) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            AppTheme {
                Box(Modifier.width(390.dp).height(550.dp)) {
                    QueueSheet(source.value, currentId, LoopMode.QUEUE_NEXT,
                        onItem = { plays++ }, onClear = {}, onRemove = {},
                        onLoop = {}, onClose = {}, onMove = { from, to, _ ->
                            moves += from to to
                            source.value = source.value.toMutableList().apply { add(to, removeAt(from)) }
                        })
                }
            }
        }
        compose.mainClock.advanceTimeBy(100)
        compose.waitForIdle()
    }

    private fun row(id: Int) = compose.onNodeWithTag("queue-row-q$id")
    private fun start() {
        val list = compose.onNodeWithTag("queue-list").fetchSemanticsNode().boundsInRoot
        val bounds = row(0).fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("queue-list").performTouchInput {
            down(Offset(25f, bounds.center.y - list.top))
            moveBy(Offset.Zero, 650)
        }
        compose.mainClock.advanceTimeBy(700)
    }

    private fun move(dy: Float) {
        compose.onNodeWithTag("queue-list").performTouchInput { moveBy(Offset(0f, dy), 16) }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    @Test fun openingTheQueueKeepsThePlayingRowFullyVisible() {
        show(currentId = "q15")
        val list = compose.onNodeWithTag("queue-list").fetchSemanticsNode().boundsInRoot
        val playing = row(15).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals(list.top, playing.top, 2f)
        assertTrue(playing.bottom <= list.bottom)
    }

    @Test fun rowFollowsSubRowMotionAndDropCommitsOnce() {
        show()
        val initial = row(0).fetchSemanticsNode().boundsInRoot
        start()
        move(initial.height * .25f)
        val following = row(0).fetchSemanticsNode().boundsInRoot
        assertEquals(initial.top + initial.height * .25f, following.top, 2f)
        DeviceEvidence.captureScene("queue-drag")
        assertTrue(moves.isEmpty())
        move(initial.height)
        assertTrue(moves.isEmpty())
        compose.onNodeWithTag("queue-list").performTouchInput { up() }
        compose.mainClock.advanceTimeBy(500)
        compose.runOnIdle {
            assertEquals(1, moves.size)
            assertEquals(0, plays)
            assertEquals("q0", source.value[1].videoId)
        }
        DeviceEvidence.captureScene("queue-drop")
    }

    @Test fun cancelledDragDoesNotCommitOrPlay() {
        show()
        val height = row(0).fetchSemanticsNode().boundsInRoot.height
        start()
        move(height * 1.5f)
        val beforeCancel = row(0).fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithTag("queue-list").performTouchInput { cancel() }
        compose.mainClock.advanceTimeByFrame()
        assertEquals("Cancelling snapped the dragged row", beforeCancel,
            row(0).fetchSemanticsNode().boundsInRoot.top, height * .2f)
        compose.mainClock.advanceTimeBy(500)
        compose.runOnIdle {
            assertTrue(moves.isEmpty())
            assertEquals("q0", source.value.first().videoId)
            assertEquals(0, plays)
        }
    }

    @Test fun neighboringRowsAnimateIntoTheVacatedSpace() {
        show()
        val first = row(0).fetchSemanticsNode().boundsInRoot
        val neighbor = row(1).fetchSemanticsNode().boundsInRoot
        start()
        move(first.height * 1.25f)
        compose.mainClock.advanceTimeBy(32)
        val during = row(1).fetchSemanticsNode().boundsInRoot.top
        assertTrue("The neighbor snapped instead of animating", during > first.top + 1f)
        assertTrue("The neighbor did not start moving", during < neighbor.top - 1f)
        compose.onNodeWithTag("queue-list").performTouchInput { up() }
        compose.mainClock.advanceTimeBy(500)
        assertEquals(first.top, row(1).fetchSemanticsNode().boundsInRoot.top, 2f)
    }

    @Test fun longPressWithoutMovingDoesNotPlayOrPersist() {
        show()
        start()
        compose.onNodeWithTag("queue-list").performTouchInput { up() }
        compose.mainClock.advanceTimeBy(500)
        compose.runOnIdle {
            assertTrue(moves.isEmpty())
            assertEquals(0, plays)
        }
    }

    @Test fun holdingAtTheEdgeScrollsBeyondTheInitialViewport() {
        show()
        start()
        val bounds = compose.onNodeWithTag("queue-list").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("queue-list").performTouchInput { moveTo(Offset(25f, bounds.height - 2f), 300) }
        compose.mainClock.advanceTimeBy(1_500)
        compose.onNodeWithTag("queue-list").performTouchInput { up() }
        compose.mainClock.advanceTimeBy(500)
        compose.runOnIdle {
            assertEquals(1, moves.size)
            assertTrue("Edge scrolling did not advance the dragged row", moves.single().second > 5)
            assertEquals(0, plays)
        }
    }
}
