package com.hhst.youtubelite.watch

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.hhst.youtubelite.watch.data.FixtureWatchRepository
import com.hhst.youtubelite.watch.domain.WatchContent
import com.hhst.youtubelite.watch.domain.WatchUiState
import com.hhst.youtubelite.watch.presentation.WatchScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class WatchContentScrollAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val layouts = listOf(390 to 844, 840 to 390, 1000 to 700, 1280 to 800)

    private fun ready(id: String): WatchUiState {
        val page = FixtureWatchRepository.samplePage("bunny")
        return WatchUiState(videoId = id, descriptionExpanded = true, content = WatchContent.Ready(page.copy(
            video = page.video.copy(id = id, description = List(100) { "A long description line." }.joinToString("\n")),
            related = List(24) { page.related.first().copy(id = "$id-related-$it") },
        )))
    }

    private fun main() = compose.onNodeWithTag("watch-scroll")
    private fun sidebar() = compose.onNode(hasScrollToIndexAction() and
        hasAnyAncestor(hasTestTag("watch-related")), useUnmergedTree = true)
    private fun value(node: SemanticsNodeInteraction) = node.fetchSemanticsNode().config[
        SemanticsProperties.VerticalScrollAxisRange].value()

    @Test fun aDifferentVideoStartsAtTheTopInEveryLayout() {
        var width by mutableIntStateOf(390)
        var height by mutableIntStateOf(844)
        var state by mutableStateOf(ready("old"))
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                WatchScreen(state, {}, {}, modifier = Modifier.requiredSize(width.dp, height.dp),
                    playerSlot = { Box(it.background(Color.Black)) })
            }
        }
        for ((index, layout) in layouts.withIndex()) {
            compose.runOnIdle { width = layout.first; height = layout.second; state = ready("old-$index") }
            main().performScrollToIndex(1)
            assertTrue(value(main()) > 0f)
            if (width >= 931) {
                sidebar().performScrollToIndex(4)
                assertTrue(value(sidebar()) > 0f)
            }
            // A cached response can replace Loading within the same frame.
            compose.runOnIdle { state = ready("new-$index") }
            assertEquals("Main position at $layout", 0f, value(main()), 0f)
            if (width >= 931) assertEquals("Related position at $layout", 0f, value(sidebar()), 0f)
            // A slower first read must also begin at the top before it completes.
            compose.runOnIdle { state = WatchUiState(videoId = "slow-$index", content = WatchContent.Loading) }
            assertEquals(0f, value(main()), 0f)
            compose.runOnIdle { state = ready("slow-$index") }
            assertEquals(0f, value(main()), 0f)
            if (width >= 931) assertEquals(0f, value(sidebar()), 0f)
        }
    }

    @Test fun theSameVideoKeepsScrollThroughPresentationAndMetadataUpdates() {
        var width by mutableIntStateOf(390)
        var height by mutableIntStateOf(844)
        var fullscreen by mutableStateOf(false)
        var state by mutableStateOf(ready("same"))
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                WatchScreen(state, {}, {}, fullscreen = fullscreen,
                    modifier = Modifier.requiredSize(width.dp, height.dp),
                    playerSlot = { Box(it.background(Color.Black)) })
            }
        }
        for ((index, layout) in layouts.withIndex()) {
            compose.runOnIdle { width = layout.first; height = layout.second; state = ready("same-$index") }
            main().performScrollToIndex(1)
            val mainBefore = value(main())
            assertTrue(mainBefore > 0f)
            val relatedBefore = if (width >= 931) {
                sidebar().performScrollToIndex(4)
                value(sidebar())
            } else null
            compose.runOnIdle { fullscreen = true }
            compose.runOnIdle {
                fullscreen = false
                val ready = state.content as WatchContent.Ready
                state = state.copy(content = ready.copy(page = ready.page.copy(
                    video = ready.page.video.copy(views = "Updated views"))))
            }
            assertEquals("Same-video main position at $layout", mainBefore, value(main()), 1f)
            relatedBefore?.let { assertEquals("Same-video related position at $layout", it, value(sidebar()), 1f) }
        }
    }
}
