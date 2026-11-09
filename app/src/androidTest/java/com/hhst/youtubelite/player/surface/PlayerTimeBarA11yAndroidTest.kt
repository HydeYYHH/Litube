package com.hhst.youtubelite.player.surface

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import com.hhst.youtubelite.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The time bar must be adjustable without a pointer: SetProgress drives the same seek. */
class PlayerTimeBarA11yAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val hasSetProgress = SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)

    @Test fun setProgressSeeksToTheRequestedFraction() {
        val seeks = mutableListOf<Long>()
        compose.setContent {
            PlayerTimeBar(
                positionState = androidx.compose.runtime.remember { mutableLongStateOf(900L) },
                bufferedPositionState = androidx.compose.runtime.remember { mutableLongStateOf(1500L) },
                durationMs = 3000L,
                segments = emptyList(),
                onSeek = { seeks += it },
            )
        }
        val description = compose.activity.getString(R.string.player_time_bar)
        val node = compose.onNodeWithContentDescription(description)
        node.assertExists()
        compose.onNode(hasSetProgress).assertExists()
        // The 48dp hit area stays the pointer target; the semantics node spans it.
        val density = compose.activity.resources.displayMetrics.density
        val heightDp = node.fetchSemanticsNode().boundsInRoot.height / density
        assertEquals(PlayerUi.TIME_BAR_HIT_DP.toFloat(), heightDp, 0.5f)
        node.performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
        assertEquals(listOf(1500L), seeks)
        // Out-of-range requests clamp into the video instead of seeking past it.
        node.performSemanticsAction(SemanticsActions.SetProgress) { it(1.4f) }
        assertEquals(listOf(1500L, 3000L), seeks)
    }

    @Test fun aZeroDurationBarOffersNoAdjustment() {
        compose.setContent {
            PlayerTimeBar(
                positionState = androidx.compose.runtime.remember { mutableLongStateOf(0L) },
                bufferedPositionState = androidx.compose.runtime.remember { mutableLongStateOf(0L) },
                durationMs = 0L,
                segments = emptyList(),
                onSeek = {},
            )
        }
        compose.onNode(hasSetProgress).assertDoesNotExist()
    }
}
