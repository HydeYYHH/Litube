package com.hhst.youtubelite.watch

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.hhst.youtubelite.player.surface.FullscreenSwipeHandle
import com.hhst.youtubelite.watch.presentation.watchFullscreenPull
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class WatchFullscreenPullAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun returningToZeroStillReleasesThePullAndTheNextGestureStartsCleanly() {
        val drags = mutableListOf<Float>()
        val releases = mutableListOf<Float>()
        val handle = FullscreenSwipeHandle().apply {
            dragContentPull = { drags += it }
            releaseContentPull = { distance, _ -> releases += distance; true }
        }
        compose.setContent {
            Box(Modifier.size(320.dp, 600.dp).then(watchFullscreenPull(handle, true) { 1 })) {
                LazyColumn(Modifier.fillMaxSize().testTag("pull-list")) {
                    item { Box(Modifier.fillMaxWidth().height(1600.dp)) }
                }
            }
        }
        val list = compose.onNodeWithTag("pull-list")
        list.performTouchInput {
            val start = Offset(center.x, height * .25f)
            down(start)
            moveTo(start + Offset(0f, height * .3f), 200)
            moveTo(start, 200)
            up()
        }
        compose.runOnIdle {
            assertTrue("The container must have received a pull", drags.any { it > 0f })
            assertEquals("Reversing to zero still ends the host gesture", 1, releases.size)
            assertEquals(0f, releases.single(), .01f)
        }
        list.performTouchInput {
            val start = Offset(center.x, height * .25f)
            down(start)
            moveTo(start + Offset(0f, height * .3f), 200)
            up()
        }
        compose.runOnIdle {
            assertEquals(2, releases.size)
            assertTrue("The next pull has independent positive travel", releases.last() < 0f)
        }
    }
}
