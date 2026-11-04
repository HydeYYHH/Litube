package com.hhst.youtubelite.subscriptions

import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import com.hhst.youtubelite.data.media.MediaSubscriptionsRepository
import com.hhst.youtubelite.data.media.MediaClient
import com.hhst.youtubelite.data.media.MediaSession
import com.hhst.youtubelite.subscriptions.domain.SubscriptionsChannels
import com.hhst.youtubelite.subscriptions.domain.SubscriptionsContent
import com.hhst.youtubelite.subscriptions.domain.SubscriptionsAction
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext

/** Opt-in, read-only census of subscription tab update signals. Never records credentials or cursors. */
class SubscriptionsSignalsAndroidTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun selectingLiveChannelStaysInSubscriptionsAndConsumesItsUnreadBadge() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("network") == "1")
        assertTrue(MediaSession.signedIn())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch<SubscriptionsActivity>(SubscriptionsActivity.intent(context, true)).use { host ->
            var channels: SubscriptionsChannels.Ready? = null
            compose.waitUntil(30_000) {
                host.onActivity { channels = it.subscriptionsViewModel.uiState.value.channels as? SubscriptionsChannels.Ready }
                channels != null
            }
            val channel = requireNotNull(channels).channels.firstOrNull { it.hasNewContent }
                ?: requireNotNull(channels).channels.first()
            compose.onNodeWithTag("subs-strip-${channel.channelId}").performClick()
            compose.onNodeWithTag("subs-strip-${channel.channelId}").assertIsSelected()
            compose.onNodeWithTag("subs-new-${channel.channelId}", useUnmergedTree = true).assertDoesNotExist()
            compose.onNodeWithTag("subscriptions-visit-channel").assertIsDisplayed()
            compose.onNodeWithTag("subscriptions-chips").assertDoesNotExist()
            var feed: SubscriptionsContent.Feed? = null
            compose.waitUntil(30_000) {
                host.onActivity {
                    assertEquals(channel.channelId, it.subscriptionsViewModel.uiState.value.selectedChannel?.channelId)
                    assertNull("Selecting an avatar must not navigate", it.lastNavigationRequest)
                    feed = it.subscriptionsViewModel.uiState.value.content as? SubscriptionsContent.Feed
                }
                feed != null
            }
            assertTrue("The selected channel's feed is empty", requireNotNull(feed).page.items.isNotEmpty())
            assertTrue(requireNotNull(feed).page.items.all { it.channelId == channel.channelId })
            val firstPageCount = requireNotNull(feed).page.items.size
            if (requireNotNull(feed).page.nextKey != null) {
                host.onActivity { it.subscriptionsViewModel.onAction(SubscriptionsAction.LoadNext) }
                compose.waitUntil(30_000) {
                    host.onActivity { feed = it.subscriptionsViewModel.uiState.value.content as? SubscriptionsContent.Feed }
                    (feed?.page?.items?.size ?: 0) > firstPageCount
                }
                assertTrue(requireNotNull(feed).page.items.all { it.channelId == channel.channelId })
            }
            File(context.filesDir, "subscription-selected-channel.png").outputStream().use {
                compose.onNodeWithTag("subscriptions-screen").captureToImage().asAndroidBitmap()
                    .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            compose.onNodeWithTag("subscriptions-back-to-all").performClick()
            compose.onNodeWithTag("subscriptions-chips").assertIsDisplayed()
            host.onActivity {
                assertNull(it.subscriptionsViewModel.uiState.value.selectedChannel)
                assertFalse((it.subscriptionsViewModel.uiState.value.channels as SubscriptionsChannels.Ready)
                    .channels.first { row -> row.channelId == channel.channelId }.hasNewContent)
            }
            File(context.filesDir, "subscription-selected-channel.json").writeText(JSONObject()
                .put("channelId", channel.channelId).put("firstPageVideos", firstPageCount).put("videos", requireNotNull(feed).page.items.size)
                .put("hasNext", requireNotNull(feed).page.nextKey != null).put("badgeCleared", true)
                .put("stayedInSubscriptions", true).toString(2))
        }
    }

    @Test fun subscriptionTabsExposeTheirUpdateSignals() = runBlocking(Dispatchers.IO) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("network") == "1")
        assertTrue("Sign in before running the subscription census", MediaSession.signedIn())
        val tabs = JSONArray()
        val serverChannels = linkedMapOf<String, Boolean>()
        val http = GlobalContext.get().get<OkHttpClient>().newBuilder().addInterceptor { chain ->
            check(chain.request().url.encodedPath.substringAfterLast('/') in setOf("browse", "guide", "token"))
            chain.proceed(chain.request()).also { response ->
                if (chain.request().url.encodedPath.endsWith("/browse") && response.isSuccessful) {
                    val root = JSONObject(response.peekBody(8L * 1024 * 1024).string())
                    fun visit(node: Any?) {
                        when (node) {
                            is JSONObject -> node.keys().forEach { key ->
                                val value = node.opt(key)
                                if (key in setOf("tabRenderer", "expandableTabRenderer") && value is JSONObject) {
                                    val endpoint = value.optJSONObject("endpoint")?.optJSONObject("browseEndpoint")
                                    val params = endpoint?.optString("params").orEmpty()
                                    if (value.has("thumbnail") && params.isNotBlank()) {
                                        // Some channel tabs are repeated by the server.
                                        val identity = endpoint?.optString("browseId") + ":" + params
                                        serverChannels[identity] = serverChannels[identity] == true ||
                                            value.optJSONObject("presentationStyle")?.optString("style") == "NEW_CONTENT"
                                    }
                                    tabs.put(JSONObject().put("renderer", key).put("title", value.optString("title"))
                                        .put("browseId", endpoint?.optString("browseId"))
                                        .put("style", value.optJSONObject("presentationStyle")?.optString("style"))
                                        .put("hasThumbnail", value.has("thumbnail"))
                                        .put("fields", JSONArray(value.keys().asSequence().toList())))
                                } else visit(value)
                            }
                            is JSONArray -> (0 until node.length()).forEach { visit(node.opt(it)) }
                        }
                    }
                    visit(root)
                }
            }
        }.build()
        val page = MediaSubscriptionsRepository(MediaClient(http, MediaSession::freshContext)).subscribedChannelsPage()
        val updates = serverChannels.values.count { it }
        val report = JSONObject().put("channels", page.channels.size)
            .put("unread", page.channels.count { it.hasNewContent }).put("serverChannels", serverChannels.size)
            .put("serverUnread", updates).put("tabs", tabs)
        File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
            "subscription-tab-signals.json").writeText(report.toString(2))
        assertTrue("The subscription browse returned no tabs", tabs.length() > 0)
        assertEquals("The directory lost subscribed channel tabs", serverChannels.size, page.channels.size)
        assertEquals("The directory lost the server's update flags", updates, page.channels.count { it.hasNewContent })
    }

    @Test fun liveScreenShowsUpdatedChannelsFirstWithBadges() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("network") == "1")
        assertTrue(MediaSession.signedIn())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch<SubscriptionsActivity>(SubscriptionsActivity.intent(context, true)).use { host ->
            var ready: SubscriptionsChannels.Ready? = null
            compose.waitUntil(30_000) {
                host.onActivity { ready = it.subscriptionsViewModel.uiState.value.channels as? SubscriptionsChannels.Ready }
                ready != null
            }
            val channels = requireNotNull(ready).channels
            assertTrue(channels.isNotEmpty())
            assertEquals(channels.size, channels.map { it.channelId }.distinct().size)
            val updates = channels.takeWhile { it.hasNewContent }
            assertTrue("An updated channel follows a read channel", channels.drop(updates.size).none { it.hasNewContent })
            if (InstrumentationRegistry.getArguments().getString("expectUpdates") == "1") {
                assertTrue("Expected this account to have updated channels", updates.isNotEmpty())
            }
            if (updates.isNotEmpty()) {
                val first = updates.first()
                compose.onNodeWithTag("subs-strip-${first.channelId}").assertIsDisplayed()
                compose.onNodeWithTag("subs-new-${first.channelId}", useUnmergedTree = true).assertIsDisplayed()
                File(context.filesDir, "subscription-updates.png").outputStream().use {
                    compose.onNodeWithTag("subscriptions-screen").captureToImage().asAndroidBitmap()
                        .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                compose.onNodeWithTag("subscriptions-manage").performClick()
                compose.onNodeWithTag("channels-new-${first.channelId}", useUnmergedTree = true).assertIsDisplayed()
            }
            File(context.filesDir, "subscription-ui-signals.json").writeText(JSONObject()
                .put("channels", channels.size).put("unread", updates.size).put("updatedFirst", true)
                .put("firstChannelId", channels.first().channelId).toString(2))
        }
    }
}
