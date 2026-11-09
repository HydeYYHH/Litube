@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.hhst.youtubelite.watch

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.core.DeviceEvidence
import com.hhst.youtubelite.player.PlayerViewModel
import com.hhst.youtubelite.player.engine.PlaybackApi
import com.hhst.youtubelite.watch.domain.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext

class WatchServiceAndroidTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun realMetadataCommentsAndPlaybackReachTheNativeHost() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("network") == "1")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val engine = GlobalContext.get().get<PlaybackApi>()
        val player = GlobalContext.get().get<PlayerViewModel>()
        try {
            ActivityScenario.launch<WatchActivity>(WatchActivity.intent(instrumentation.targetContext, "aqz-KE-bpKQ")).use { host ->
                var activity: WatchActivity? = null
                host.onActivity { activity = it }
                fun await(label: String, condition: () -> Boolean) {
                    try { compose.waitUntil(timeoutMillis = 90_000, condition = condition) }
                    catch (_: androidx.compose.ui.test.ComposeTimeoutException) { }
                    if (!condition()) {
                        DeviceEvidence.captureScene("watch-service-failure")
                        val watch = activity!!.watchViewModel.uiState.value
                        val comments = activity!!.commentsViewModel.uiState.value
                        val video = (watch.content as? WatchContent.Ready)?.page?.video
                        fail("$label: watch=${watch.content::class.simpleName}, id=${watch.videoId}, author=${video?.channel?.length}, preview=${video?.commentsStatus}, comments=${comments.content::class.simpleName}, target=${comments.videoId}, engine=${engine.snapshot.value.error}")
                    }
                }
                await("Watch data and comment preview must load") {
                    val video = (activity!!.watchViewModel.uiState.value.content as? WatchContent.Ready)?.page?.video
                    video?.channel?.isNotBlank() == true && video.commentsStatus == WatchCommentsStatus.Ready && video.topComment.text.isNotBlank()
                }
                compose.onNodeWithTag("watch-channel-name", useUnmergedTree = true).assertIsDisplayed()
                compose.onNodeWithTag("watch-comment-preview", useUnmergedTree = true).assertIsDisplayed()
                DeviceEvidence.captureScene("watch-service-data")
                await("Playback must prepare or report an actionable failure") { engine.snapshot.value.prepared || engine.snapshot.value.error != null }
                assertNull("Playback failure: " + engine.snapshot.value.error, engine.snapshot.value.error)
                await("Playback must advance with video dimensions") {
                    val snapshot = engine.snapshot.value
                    snapshot.isPlaying && snapshot.positionMs > 1000 && snapshot.videoWidth > 0 && snapshot.videoHeight > 0
                }
                DeviceEvidence.captureScene("watch-service-playing")
                compose.onNodeWithTag("watch-comments").performClick()
                compose.onNodeWithTag("comments-panel-host").assertIsDisplayed()
                assertTrue(engine.snapshot.value.isPlaying)
            }
        } finally { instrumentation.runOnMainSync { player.onMiniClose() } }
    }
}
