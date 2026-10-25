package com.hhst.youtubelite.channel

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.abs
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.filters.SdkSuppress
import com.hhst.youtubelite.channel.data.FixtureChannelRepository
import com.hhst.youtubelite.channel.domain.*
import com.hhst.youtubelite.channel.presentation.ChannelScreen
import com.hhst.youtubelite.core.DeviceEvidence
import com.hhst.youtubelite.post.data.FixturePostRepository
import com.hhst.youtubelite.post.domain.*
import com.hhst.youtubelite.post.presentation.PostDetailScreen
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@SdkSuppress(minSdkVersion = 35, maxSdkVersion = 35)
class ChannelCommunityScreenshotAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun readyPage() = ChannelContent.Ready(
        ChannelPage(
            FixtureChannelRepository.profile("studio"),
            FixtureChannelRepository.sampleVideos(),
            com.hhst.youtubelite.playlist.data.FixturePlaylistRepository.summaries(),
        )
    )

    private fun state(tab: ChannelTab, width: Int) = ChannelUiState(
        channelId = "studio",
        content = readyPage(),
        selectedTab = tab,
        shorts = ChannelSection.Ready(FixtureChannelRepository.sampleShorts()),
        live = ChannelSection.Ready(FixtureChannelRepository.sampleLive()),
        posts = ChannelSection.Ready(FixturePostRepository.samplePosts("studio")),
    )

    @Test fun postAuthorAndBodyFollowTheSampledAnchors() {
        val width = mutableIntStateOf(390)
        val detailMode = mutableStateOf(true)
        val detail = FixturePostRepository.sampleDetail(PostIdentity("studio", "post"))!!
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                key(width.intValue, detailMode.value) {
                    Box(Modifier.requiredSize(width.intValue.dp, 844.dp)) {
                        if (detailMode.value) PostDetailScreen(PostDetailUiState(detail.identity, PostDetailContent.Ready(detail)), {}, {})
                        else ChannelScreen(state(ChannelTab.Posts, width.intValue), {}, {})
                    }
                }
            }
        }
        for (w in listOf(390, 1280)) {
            compose.runOnIdle { width.intValue = w; detailMode.value = true }
            val name = boundsOf("post-detail-author-name")
            assertEquals(60f, name.left, .5f)
            assertEquals(61f, name.top, .5f)
            assertEquals(116f, boundsOf("post-detail-body").top, .5f)
            compose.onNodeWithTag("post-detail-navigation").assertIsDisplayed()
            compose.runOnIdle { detailMode.value = false }
            val first = FixturePostRepository.samplePosts("studio").first().identity.postId
            val card = boundsOf("channel-post-$first")
            val author = compose.onNodeWithTag("channel-post-author-$first", true).fetchSemanticsNode().boundsInRoot
            val avatar = compose.onNodeWithTag("channel-post-avatar-$first", true).fetchSemanticsNode().boundsInRoot
            assertEquals(60f, author.left, .5f)
            assertEquals(card.top + 12f, author.top, .5f)
            assertEquals(12f, avatar.left, .5f)
            assertEquals(card.top + 12f, avatar.top, .5f)
            assertEquals(40f, avatar.width, .5f)
        }
    }

    @Test fun capturesChannelCommunityAtPhoneAndWide() {
        val width = mutableIntStateOf(390)
        val height = mutableIntStateOf(844)
        val state = mutableStateOf(state(ChannelTab.Shorts, 390))
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                key(width.intValue, state.value.selectedTab) {
                    Box(Modifier.requiredSize(width.intValue.dp, height.intValue.dp)) {
                        ChannelScreen(state.value, {}, {})
                    }
                }
            }
        }
        for (w in listOf(390, 1280)) {
            val h = if (w == 390) 844 else 800
            compose.runOnIdle { width.intValue = w; height.intValue = h; state.value = state(ChannelTab.Shorts, w) }
            compose.waitForIdle()
            compose.onNodeWithTag("channel-shorts-list").performScrollToNode(hasTestTag("channel-short-shorts-1"))
            compose.waitForIdle()
            snap("channel-page", "community-app-shorts-$w")

            compose.runOnIdle { state.value = state(ChannelTab.Live, w) }
            compose.waitForIdle()
            compose.onNodeWithTag("channel-live-list").performScrollToNode(hasTestTag("channel-live-live-now"))
            compose.waitForIdle()
            snap("channel-page", "community-app-live-$w")

            compose.runOnIdle { state.value = state(ChannelTab.Posts, w) }
            compose.waitForIdle()
            compose.onNodeWithTag("channel-posts-list").performScrollToNode(hasTestTag("channel-post-post"))
            compose.waitForIdle()
            snap("channel-page", "community-app-posts-$w")

            compose.onNodeWithTag("channel-posts-list")
                .performScrollToNode(hasTestTag("channel-post-images-post"))
            compose.waitForIdle()
            compose.onNodeWithTag("channel-post-images-post").assertIsDisplayed()
            snap("channel-page", "community-app-post-images-$w")
            if (nodeCount("channel-post-read-more-post") > 0) {
                compose.runOnIdle {
                    state.value = state(ChannelTab.Posts, w).copy(expandedPosts = setOf("post"))
                }
                compose.waitForIdle()
                compose.onNodeWithTag("channel-post-show-less-post").assertIsDisplayed()
                snap("channel-page", "community-app-post-expanded-$w")
            }
        }
    }

    @Test fun capturesPostDetailStates() {
        val width = mutableIntStateOf(390)
        val height = mutableIntStateOf(844)
        val detail = FixturePostRepository.sampleDetail(PostIdentity("studio", "post"))!!
        val state = mutableStateOf(
            PostDetailUiState(identity = detail.identity, content = PostDetailContent.Ready(detail))
        )
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                key(width.intValue) {
                    Box(Modifier.requiredSize(width.intValue.dp, height.intValue.dp)) {
                        PostDetailScreen(state.value, {}, {})
                    }
                }
            }
        }
        for (w in listOf(390, 1280)) {
            compose.runOnIdle {
                width.intValue = w; height.intValue = if (w == 390) 844 else 800
                state.value = PostDetailUiState(identity = detail.identity, content = PostDetailContent.Ready(detail))
            }
            compose.waitForIdle()
            snap("post-detail-page", "community-app-post-detail-$w")
            compose.onNodeWithTag("post-detail-list").performScrollToNode(hasTestTag("post-detail-images"))
            compose.waitForIdle()
            compose.onNodeWithTag("post-detail-images").assertIsDisplayed()
            snap("post-detail-page", "community-app-post-detail-images-$w")
            compose.onNodeWithTag("post-images").performTouchInput { swipeLeft() }
            compose.waitForIdle()
            snap("post-detail-page", "community-app-post-detail-next-$w")
            compose.onNodeWithTag("post-detail-list").performScrollToNode(hasTestTag("post-detail-feedback"))
            compose.onNodeWithTag("post-detail-list").performTouchInput { swipeUp() }
            compose.waitForIdle()
            compose.onNodeWithTag("post-detail-feedback").assertIsDisplayed()
            assertTrue(
                "$w: feedback ${boundsOf("post-detail-feedback")} page ${boundsOf("post-detail-page")}",
                boundsOf("post-detail-feedback").bottom <= boundsOf("post-detail-page").bottom + 0.5f,
            )
            snap("post-detail-page", "community-app-post-detail-bottom-$w")
        }
    }

    @Test fun communityCardsFitEveryWidthAndRetryIsReachable() {
        val width = mutableIntStateOf(390)
        val height = mutableIntStateOf(900)
        val state = mutableStateOf(state(ChannelTab.Shorts, 390))
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                Box(Modifier.requiredSize(width.intValue.dp, height.intValue.dp)) {
                    ChannelScreen(state.value, {}, {})
                }
            }
        }
        for (w in listOf(320, 390, 411, 599, 600, 601, 840, 1280, 1600)) {
            val h = if (w >= 600) 1200 else 900
            compose.runOnIdle { width.intValue = w; height.intValue = h; state.value = state(ChannelTab.Shorts, w) }
            compose.waitForIdle()
            compose.onNodeWithTag("channel-shorts-list").performScrollToNode(hasTestTag("channel-short-shorts-1"))
            compose.onNodeWithTag("channel-short-shorts-1").assertIsDisplayed()
            if (w in listOf(320, 600, 840, 1600)) snap("channel-page", "community-app-shorts-$w")
            val short = boundsOf("channel-short-shorts-1")
            assertTrue("$w: short left ${short.left}", short.left >= 0f)
            assertTrue("$w: short right ${short.right}", short.right <= w + 0.5f)
            assertEquals(w.toFloat(), boundsOf("channel-page").width, 0.5f)

            compose.runOnIdle { state.value = state(ChannelTab.Live, w) }
            compose.waitForIdle()
            compose.onNodeWithTag("channel-live-list").performScrollToNode(hasTestTag("channel-live-live-now"))
            compose.onNodeWithTag("channel-live-live-now").assertIsDisplayed()
            if (w in listOf(320, 600, 840, 1600)) snap("channel-page", "community-app-live-$w")
            assertTrue("$w: live row overflows", boundsOf("channel-live-live-now").right <= w + 0.5f)

            compose.runOnIdle { state.value = state(ChannelTab.Posts, w) }
            compose.waitForIdle()
            compose.onNodeWithTag("channel-posts-list").performScrollToNode(hasTestTag("channel-post-images-post"))
            compose.onNodeWithTag("channel-post-images-post").assertIsDisplayed()
            if (w in listOf(320, 600, 840, 1600)) snap("channel-page", "community-app-posts-$w")
            assertTrue("$w: post image overflows", boundsOf("channel-post-images-post").right <= w + 0.5f)
            run {
                compose.onNodeWithTag("channel-posts-list")
                    .performScrollToNode(hasTestTag("channel-post-comments-post"))
                repeat(30) {
                    val visible = boundsOf("channel-post-comments-post")
                    val tabs = boundsOf("channel-tabbar")
                    val list = boundsOf("channel-posts-list")
                    if (visible.height < 24f || visible.top < tabs.bottom || visible.bottom > list.bottom) {
                        val delta = if (visible.top < tabs.bottom && visible.top > list.top) -64f else 64f
                        compose.onNodeWithTag("channel-posts-list")
                            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { it(0f, delta) }
                    }
                }
                val comments = boundsOf("channel-post-comments-post")
                val viewport = boundsOf("channel-posts-list")
                assertTrue(
                    "$w: comments ${comments.left},${comments.top},${comments.right},${comments.bottom} viewport $viewport",
                    comments.left >= 0f && comments.right <= w + 0.5f &&
                        comments.top >= boundsOf("channel-tabbar").bottom - 0.5f &&
                        comments.bottom <= viewport.bottom + 0.5f && comments.height >= 24f,
                )
            }
        }
    }

    @Test fun communitySectionsDistinguishLoadingEmptyAndFailedWithRetry() {
        val width = mutableIntStateOf(390)
        val height = mutableIntStateOf(900)
        var lastAction: ChannelAction? = null
        val base = state(ChannelTab.Shorts, 390)
        val state = mutableStateOf(base.copy(shorts = ChannelSection.Loading))
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                Box(Modifier.requiredSize(width.intValue.dp, height.intValue.dp)) {
                    ChannelScreen(state.value, { lastAction = it }, {})
                }
            }
        }
        for (tab in listOf(ChannelTab.Shorts, ChannelTab.Live, ChannelTab.Posts)) {
            val key = tab.name.lowercase()
            val variants = when (tab) {
                ChannelTab.Shorts -> listOf(
                    "loading" to base.copy(selectedTab = tab, shorts = ChannelSection.Loading),
                    "empty" to base.copy(selectedTab = tab, shorts = ChannelSection.Empty),
                    "failed" to base.copy(selectedTab = tab, shorts = ChannelSection.Failed),
                )
                ChannelTab.Live -> listOf(
                    "loading" to base.copy(selectedTab = tab, live = ChannelSection.Loading),
                    "empty" to base.copy(selectedTab = tab, live = ChannelSection.Empty),
                    "failed" to base.copy(selectedTab = tab, live = ChannelSection.Failed),
                )
                else -> listOf(
                    "loading" to base.copy(selectedTab = tab, posts = ChannelSection.Loading),
                    "empty" to base.copy(selectedTab = tab, posts = ChannelSection.Empty),
                    "failed" to base.copy(selectedTab = tab, posts = ChannelSection.Failed),
                )
            }
            for ((suffix, variant) in variants) {
                compose.runOnIdle { state.value = variant }
                compose.waitForIdle()
                compose.onNodeWithTag("channel-$key-$suffix").assertIsDisplayed()
                if (suffix == "failed") {
                    lastAction = null
                    compose.onNodeWithTag("channel-$key-retry").performClick()
                    assertEquals(ChannelAction.RetrySection(tab), lastAction)
                }
            }
        }
    }

    private fun boundsOf(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun nodeCount(tag: String) = runCatching {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size
    }.getOrDefault(0)

    private fun snap(tag: String, name: String) {
        compose.waitForIdle()
        val bitmap = compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        val output = File(compose.activity.getExternalFilesDir(null), "community-ui").apply { mkdirs() }
        File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val baselines = setOf("shorts", "live", "posts", "post-detail").flatMap { scene ->
            listOf(390, 1280).map { "community-app-$scene-$it" }
        }
        if (name in baselines && InstrumentationRegistry.getArguments().getString("recordCommunityGoldens") != "true") {
            val expected = InstrumentationRegistry.getInstrumentation().context.assets
                .open("channel/community/$name.png").use(BitmapFactory::decodeStream)
            assertEquals(name, expected.width, bitmap.width)
            assertEquals(name, expected.height, bitmap.height)
            val actualPixels = IntArray(bitmap.width * bitmap.height)
            val expectedPixels = IntArray(actualPixels.size)
            bitmap.getPixels(actualPixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            expected.getPixels(expectedPixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val changed = actualPixels.indices.count { i ->
                listOf(0, 8, 16).any { shift ->
                    abs((actualPixels[i] shr shift and 255) - (expectedPixels[i] shr shift and 255)) > 2
                }
            }
            assertTrue("$name: $changed changed pixels", changed.toFloat() / actualPixels.size <= .001f)
            expected.recycle()
        }
        bitmap.recycle()
    }
}
