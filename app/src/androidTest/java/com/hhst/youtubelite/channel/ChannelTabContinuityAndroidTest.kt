package com.hhst.youtubelite.channel

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.hhst.youtubelite.channel.data.FixtureChannelRepository
import com.hhst.youtubelite.channel.domain.ChannelContent
import com.hhst.youtubelite.channel.domain.ChannelTab
import com.hhst.youtubelite.channel.domain.ChannelUiState
import com.hhst.youtubelite.channel.presentation.ChannelScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ChannelTabContinuityAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun rapidTabChangesKeepTheSharedHeaderInPlaceOnEveryFrame() {
        var state by mutableStateOf(ChannelUiState(content = ChannelContent.Ready(FixtureChannelRepository.samplePage())))
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                Box(Modifier.requiredSize(390.dp, 844.dp)) { ChannelScreen(state, {}, {}) }
            }
        }
        compose.mainClock.advanceTimeBy(32)
        val anchor = compose.onNodeWithTag("channel-header").fetchSemanticsNode().boundsInRoot
        for (tab in listOf(ChannelTab.Videos, ChannelTab.Shorts, ChannelTab.Home, ChannelTab.Playlists, ChannelTab.Videos)) {
            compose.runOnIdle { state = state.copy(selectedTab = tab) }
            repeat(3) {
                compose.mainClock.advanceTimeByFrame()
                val frame = compose.onNodeWithTag("channel-header").fetchSemanticsNode().boundsInRoot
                assertEquals("$tab header must not slide sideways", anchor.left, frame.left, .5f)
                assertEquals("$tab header must not jump vertically", anchor.top, frame.top, .5f)
            }
        }
        compose.mainClock.autoAdvance = true
    }
}
