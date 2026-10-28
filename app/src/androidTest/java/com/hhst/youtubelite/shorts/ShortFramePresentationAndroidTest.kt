package com.hhst.youtubelite.shorts

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.hhst.youtubelite.shorts.domain.ShortItem
import com.hhst.youtubelite.shorts.domain.ShortsContent
import com.hhst.youtubelite.shorts.domain.ShortsPage
import com.hhst.youtubelite.shorts.domain.ShortsUiState
import com.hhst.youtubelite.shorts.presentation.ShortsScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ShortFramePresentationAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

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
}
