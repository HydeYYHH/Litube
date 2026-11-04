package com.hhst.youtubelite.channel

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.channel.data.ChannelScenario
import com.hhst.youtubelite.core.DeviceEvidence
import com.hhst.youtubelite.channel.domain.ChannelNavigationRequest
import com.hhst.youtubelite.channel.domain.ChannelShortMenuAction
import com.hhst.youtubelite.channel.domain.ChannelTab
import com.hhst.youtubelite.post.domain.PostIdentity
import com.hhst.youtubelite.post.domain.PostMenuAction
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Host behavior of channel Shorts/Live/Posts: local tabs, identity-carrying typed exits. */
class ChannelCommunityHostAndroidTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var channel: ActivityScenario<ChannelActivity>? = null

    @After fun close() {
        channel?.close()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun nodeCount(tag: String) = runCatching {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size
    }.getOrDefault(0)

    private fun awaitNode(tag: String) = compose.waitUntil(15_000) { nodeCount(tag) > 0 }

    private fun launchChannel(scenario: ChannelScenario? = null) =
        ActivityScenario.launch<ChannelActivity>(
            ChannelActivity.intent(ApplicationProvider.getApplicationContext(), "blender", scenario)
        ).also {
            channel = it
            awaitNode("channel-page")
        }

    private fun revealTabs() {
        var attempts = 0
        while (nodeCount("channel-tabs-row") == 0 && attempts < 6) {
            val list = listOf(
                "channel-home-list", "channel-videos-list", "channel-shorts-list",
                "channel-live-list", "channel-playlists-list", "channel-posts-list",
            ).firstOrNull { nodeCount(it) > 0 } ?: return
            compose.onNodeWithTag(list).performTouchInput { swipeUp() }
            compose.waitForIdle()
            attempts++
        }
    }

    private fun clickTab(tab: ChannelTab) {
        revealTabs()
        val node = compose.onNodeWithTag("channel-tab-${tab.name}")
        runCatching { node.performScrollTo() }
        runCatching { node.performClick() }.onFailure {
            compose.onNodeWithTag("channel-tabs-row").performTouchInput { swipeLeft() }
            node.performScrollTo()
            node.performClick()
        }
        awaitNode(
            when (tab) {
                ChannelTab.Shorts -> "channel-shorts-list"
                ChannelTab.Live -> "channel-live-list"
                ChannelTab.Posts -> "channel-posts-list"
                else -> "channel-home-list"
            }
        )
    }

    @Test fun communityTabsSwitchLocallyKeepScrollsAndRestoreAfterRecreation() {
        val host = launchChannel()
        awaitNode("channel-home-list")
        clickTab(ChannelTab.Shorts)
        awaitNode("channel-short-shorts-1")
        compose.onNodeWithTag("channel-shorts-list").performScrollToNode(hasTestTag("channel-short-shorts-11"))
        compose.onNodeWithTag("channel-short-shorts-11").assertIsDisplayed()

        clickTab(ChannelTab.Live)
        awaitNode("channel-live-live-now")
        clickTab(ChannelTab.Posts)
        awaitNode("channel-post-community-1")
        compose.onNodeWithTag("channel-tab-Posts").assertIsSelected()

        clickTab(ChannelTab.Shorts)
        awaitNode("channel-shorts-list")
        compose.onNodeWithTag("channel-short-shorts-11").assertIsDisplayed()

        clickTab(ChannelTab.Posts)
        awaitNode("channel-post-community-1")
        val expanded = nodeCount("channel-post-read-more-community-1") > 0
        if (expanded) {
            compose.onNodeWithTag("channel-posts-list")
                .performScrollToNode(hasTestTag("channel-post-read-more-community-1"))
            compose.onNodeWithTag("channel-post-read-more-community-1").performClick()
            compose.onNodeWithTag("channel-post-show-less-community-1").assertIsDisplayed()
        }

        host.recreate()
        awaitNode("channel-page")
        awaitNode("channel-posts-list")
        compose.onNodeWithTag("channel-tab-Posts").assertIsSelected()
        if (expanded) {
            awaitNode("channel-post-show-less-community-1")
            compose.onNodeWithTag("channel-post-show-less-community-1").assertIsDisplayed()
        } else {
            compose.onNodeWithTag("channel-post-community-1").assertIsDisplayed()
        }
    }

    @Test fun shortMenuAndShortOpenCarryTheChannelAndShortIdentity() {
        val host = launchChannel()
        awaitNode("channel-home-list")
        clickTab(ChannelTab.Shorts)
        awaitNode("channel-short-shorts-1")
        compose.onNodeWithTag("channel-short-menu-shorts-1").performClick()
        awaitNode("channel-short-menu")
        compose.onNodeWithTag("channel-short-menu-sendfeedback").performClick()
        host.onActivity {
            assertEquals(
                ChannelNavigationRequest.ShortMenu("blender", "shorts-1", ChannelShortMenuAction.SendFeedback),
                it.lastNavigationRequest,
            )
        }
        compose.onNodeWithTag("channel-short-shorts-1").performClick()
        host.onActivity {
            assertEquals(ChannelNavigationRequest.Short("blender", "shorts-1"), it.lastNavigationRequest)
        }
        awaitNode("short-page-shorts-1")
        compose.onNodeWithTag("short-page-shorts-1").assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(1)
        awaitNode("channel-shorts-list")
        compose.onNodeWithTag("watch-screen").assertDoesNotExist()
    }

    @Test fun postImageSelectionSurvivesTabSwitchAndRecreation() {
        val host = launchChannel()
        awaitNode("channel-home-list")
        clickTab(ChannelTab.Posts)
        awaitNode("channel-post-community-1")
        compose.onNodeWithTag("channel-posts-list").performScrollToIndex(2)
        compose.onNodeWithText("1/3").assertIsDisplayed()
        compose.onNodeWithTag("channel-post-images-community-1").performTouchInput { swipeLeft(durationMillis = 600) }
        compose.waitForIdle()
        DeviceEvidence.captureScene("community-image-selection")
        val selected = listOf("2/3", "3/3").first { text ->
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(selected).assertIsDisplayed()
        clickTab(ChannelTab.Shorts)
        clickTab(ChannelTab.Posts)
        compose.onNodeWithTag("channel-posts-list").performScrollToIndex(2)
        compose.onNodeWithText(selected).assertIsDisplayed()
        host.recreate()
        awaitNode("channel-posts-list")
        awaitNode("channel-post-images-community-1")
        compose.onNodeWithTag("channel-posts-list").performScrollToIndex(2)
        compose.onNodeWithText(selected).assertIsDisplayed()
    }

    @Test fun postMenuFeedbackCommentsAndDetailNavigationCarryPostIdentity() {
        val host = launchChannel()
        awaitNode("channel-home-list")
        clickTab(ChannelTab.Posts)
        awaitNode("channel-post-community-1")
        val identity = PostIdentity("blender", "community-1")

        compose.onNodeWithTag("channel-post-menu-community-1").performClick()
        awaitNode("channel-post-menu-sheet")
        compose.onNodeWithTag("channel-post-menu-report").performClick()
        host.onActivity {
            assertEquals(ChannelNavigationRequest.PostMenu(identity, PostMenuAction.Report), it.lastNavigationRequest)
        }

        compose.onNodeWithTag("channel-posts-list").performScrollToNode(hasTestTag("channel-post-like-community-1"))
        compose.onNodeWithTag("channel-post-like-community-1")
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(5_000) {
            var request: ChannelNavigationRequest? = null
            host.onActivity { request = it.lastNavigationRequest }
            request == ChannelNavigationRequest.PostFeedback(identity, true)
        }
        compose.onNodeWithTag("channel-post-comments-community-1")
            .performSemanticsAction(SemanticsActions.OnClick)
        host.onActivity {
            assertEquals(ChannelNavigationRequest.PostComments(identity), it.lastNavigationRequest)
        }
        awaitNode("comments-panel")
        composerBack()
        composerBack()
        awaitNode("channel-posts-list")

        val expanded = nodeCount("channel-post-read-more-community-1") > 0
        if (expanded) {
            compose.onNodeWithTag("channel-posts-list")
                .performScrollToNode(hasTestTag("channel-post-read-more-community-1"))
            compose.onNodeWithTag("channel-post-read-more-community-1").performClick()
            compose.onNodeWithTag("channel-post-show-less-community-1").assertIsDisplayed()
        }
        compose.onNodeWithTag("channel-posts-list").performScrollToNode(hasTestTag("channel-post-body-community-1"))
        compose.onNodeWithTag("channel-post-body-community-1").performTouchInput { click() }
        awaitNode("post-detail-page")
        compose.onNodeWithTag("post-detail-body").assertIsDisplayed()
        composerBack()
        awaitNode("channel-posts-list")
        if (expanded) {
            compose.onNodeWithTag("channel-post-show-less-community-1").assertIsDisplayed()
        }
    }

    @Test fun currentLiveStaysTypedAndPastBroadcastUsesTheExistingWatchHost() {
        val host = launchChannel()
        awaitNode("channel-home-list")
        clickTab(ChannelTab.Live)
        awaitNode("channel-live-live-now")
        compose.onNodeWithTag("channel-live-live-now").performClick()
        host.onActivity {
            assertEquals(ChannelNavigationRequest.LiveStream("blender", "live-now"), it.lastNavigationRequest)
        }
        awaitNode("watch-screen")
        composerBack()
        awaitNode("channel-live-list")

        compose.onNodeWithTag("channel-live-live-1").performClick()
        host.onActivity {
            assertEquals(ChannelNavigationRequest.Watch("live-1"), it.lastNavigationRequest)
        }
        awaitNode("watch-screen")
        compose.onNodeWithText("SINGULARITY Premiere Party").assertIsDisplayed()
        composerBack()
        awaitNode("channel-live-list")
        compose.onNodeWithTag("channel-tab-Live").assertIsSelected()
    }

    @Test fun nativeResolutionEvidenceForCommunityTabsAndPostDetail() {
        launchChannel()
        awaitNode("channel-home-list")
        clickTab(ChannelTab.Shorts)
        awaitNode("channel-shorts-list")
        DeviceEvidence.captureScene("channel-community-final-shorts")
        compose.onNodeWithTag("channel-shorts-list")
            .performScrollToNode(hasTestTag("channel-short-shorts-1"))
        DeviceEvidence.captureScene("channel-community-final-shorts-content")
        clickTab(ChannelTab.Live)
        awaitNode("channel-live-list")
        DeviceEvidence.captureScene("channel-community-final-live")
        compose.onNodeWithTag("channel-live-list")
            .performScrollToNode(hasTestTag("channel-live-live-now"))
        DeviceEvidence.captureScene("channel-community-final-live-content")
        clickTab(ChannelTab.Posts)
        awaitNode("channel-posts-list")
        DeviceEvidence.captureScene("channel-community-final-posts")
        compose.onNodeWithTag("channel-posts-list")
            .performScrollToNode(hasTestTag("channel-post-community-1"))
        DeviceEvidence.captureScene("channel-community-final-posts-content")
        if (nodeCount("channel-post-read-more-community-1") > 0) {
            compose.onNodeWithTag("channel-posts-list")
                .performScrollToNode(hasTestTag("channel-post-read-more-community-1"))
            compose.onNodeWithTag("channel-post-read-more-community-1").performClick()
            compose.waitForIdle()
            awaitNode("channel-post-show-less-community-1")
            compose.onNodeWithTag("channel-post-show-less-community-1").assertIsDisplayed()
            DeviceEvidence.captureScene("channel-community-final-posts-expanded")
        }
        compose.onNodeWithTag("channel-posts-list")
            .performScrollToNode(hasTestTag("channel-post-body-community-1"))
        compose.onNodeWithTag("channel-post-body-community-1").performTouchInput { click() }
        awaitNode("post-detail-page")
        awaitNode("post-detail-body")
        compose.waitForIdle()
        DeviceEvidence.captureScene("post-detail-final")
        compose.onNodeWithTag("post-detail-list")
            .performScrollToNode(hasTestTag("post-detail-feedback"))
        compose.onNodeWithTag("post-detail-feedback").assertIsDisplayed()
        DeviceEvidence.captureScene("post-detail-final-bottom")
        composerBack()
        awaitNode("channel-posts-list")
    }

    private fun composerBack() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(1)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }
}
