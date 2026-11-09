package com.hhst.youtubelite.player.surface

import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.hhst.youtubelite.R
import com.hhst.youtubelite.core.PreferenceStore
import com.hhst.youtubelite.player.PlayerUiState
import com.hhst.youtubelite.ui.theme.AppTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MiniPlayerGestureAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val calls = mutableListOf<String>()
    private var handle: MiniPlayerHandle? = null
    private var attached = 0
    private var detached = 0
    private var surface: SurfaceView? = null
    private lateinit var store: MiniPlayerStore

    private fun showMini() {
        val kv = PreferenceStore.of(ApplicationProvider.getApplicationContext(), "mini-gesture-regression")
        kv.clearAll()
        store = MiniPlayerStore(kv)
        compose.setContent {
            AppTheme {
                Box(Modifier.fillMaxSize().testTag("mini-host")) {
                    MiniPlayerWindow(LocalConfiguration.current.screenWidthDp, 24, store, true,
                        { calls += "background" }, onDismiss = { calls += "dismiss" }) {
                        handle = LocalMiniPlayerHandle.current
                        PlayerSurface(PlayerUiState(visible = true, mini = true, controlsVisible = true, prepared = true,
                            hasNext = true, hasPrevious = true, title = "Mini gestures"),
                            remember { mutableStateOf(0L) }, remember { mutableStateOf(0L) },
                            remember { mutableStateOf(null) }, remember { mutableStateOf(null) },
                            remember { mutableStateOf(emptyList()) },
                            proxyActions("MiniGestureActions") { method, _ -> calls += method },
                            { attached++; surface = it }, { detached++ }, managedByHost = true)
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun dragStartingOnControlsOwnsMovementAndReleaseWhileTapsStillWork() {
        showMini()
        val descriptions = listOf(R.string.queue, R.string.close, R.string.action_restore_watch,
            R.string.action_previous, R.string.action_play, R.string.action_next)
        for (description in descriptions) {
            val mini = compose.onNodeWithTag("player-window").fetchSemanticsNode().boundsInRoot
            val button = compose.onNodeWithContentDescription(compose.activity.getString(description))
                .fetchSemanticsNode().boundsInRoot
            compose.onNodeWithTag("player-window").performTouchInput {
                val start = button.center - mini.topLeft
                down(start)
                moveTo(start + Offset(-45f, -90f), 200)
            }
            compose.runOnIdle { assertTrue(handle!!.interacting) }
            compose.onNodeWithTag("player-window").performTouchInput { up() }
            compose.waitForIdle()
            assertTrue("Drag clicked a control: $calls", calls.isEmpty())
            assertFalse(handle!!.interacting)
        }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.action_next)).performTouchInput { click() }
        compose.waitForIdle()
        assertEquals(listOf("onNext"), calls)
        assertEquals(1, attached)
        assertEquals(0, detached)
    }

    @Test fun bothWallsCollapseAndTapExpandsWithoutReplacingTheSurface() {
        showMini()
        val firstSurface = surface
        val density = compose.activity.resources.displayMetrics.density
        for (side in listOf(MiniPlayerLayout.CollapsedSide.RIGHT, MiniPlayerLayout.CollapsedSide.LEFT)) {
            // First dock at the requested wall without asking for a collapse.
            compose.runOnIdle {
                handle!!.begin()
                handle!!.dragFromStart(if (side == MiniPlayerLayout.CollapsedSide.LEFT) -10_000f else 10_000f, 0f)
                handle!!.end(true)
            }
            compose.waitForIdle()
            val placement = store.load()
            compose.onNodeWithTag("player-window").performTouchInput {
                swipe(center, center + Offset((if (side == MiniPlayerLayout.CollapsedSide.LEFT) -140 else 140) * density, 0f), 300)
            }
            compose.waitForIdle()
            assertEquals(side, handle!!.collapsedSide)
            val host = compose.onNodeWithTag("mini-host").fetchSemanticsNode().boundsInRoot
            val window = compose.onNodeWithTag("player-window").fetchSemanticsNode().boundsInRoot
            val visibleWidth = minOf(host.right, window.right) - maxOf(host.left, window.left)
            assertEquals(MiniPlayerLayout.COLLAPSED_PEEK_DP * density, visibleWidth, 2f)
            compose.onNodeWithTag("mini-expand").assertIsDisplayed().performTouchInput { click() }
            compose.waitForIdle()
            assertNull(handle!!.collapsedSide)
            assertEquals(placement, store.load())
            assertTrue("Fold/unfold fired a playback action: $calls", calls.isEmpty())
            assertSame(firstSurface, surface)
            assertEquals(1, attached)
            assertEquals(0, detached)
        }
    }

    @Test fun cancelledDragDoesNotCollapseOrClickAControl() {
        showMini()
        compose.onNodeWithTag("player-window").performTouchInput {
            down(center)
            moveTo(center + Offset(180f, 0f), 200)
            cancel()
        }
        compose.waitForIdle()
        assertFalse(handle!!.interacting)
        assertNull(handle!!.collapsedSide)
        assertTrue(calls.isEmpty())
    }

    @Test fun aSmallFastEdgeDragReturnsToItsDock() {
        showMini()
        val density = compose.activity.resources.displayMetrics.density
        compose.onNodeWithTag("player-window").performTouchInput {
            swipe(center, center + Offset(40f * density, 0f), 64)
        }
        compose.waitForIdle()
        assertNull(handle!!.collapsedSide)
        assertTrue(calls.isEmpty())
        assertEquals(1, attached)
        assertEquals(0, detached)
    }

    @Test fun pinchOverControlsResizesWithoutClickingOrReplacingSurface() {
        showMini()
        val widthBefore = store.load().widthDp
        val firstSurface = surface
        compose.onNodeWithTag("player-window").performTouchInput {
            down(0, center - Offset(30f, 0f))
            down(1, center + Offset(30f, 0f))
            moveTo(0, center - Offset(50f, 0f), 100)
            moveTo(1, center + Offset(50f, 0f), 100)
        }
        compose.runOnIdle { assertTrue(handle!!.interacting) }
        compose.onNodeWithTag("player-window").performTouchInput { up(0); up(1) }
        compose.waitForIdle()
        assertTrue(store.load().widthDp > widthBefore)
        assertFalse(handle!!.interacting)
        assertTrue(calls.isEmpty())
        assertSame(firstSurface, surface)
        assertEquals(1, attached)
        assertEquals(0, detached)
    }
}
