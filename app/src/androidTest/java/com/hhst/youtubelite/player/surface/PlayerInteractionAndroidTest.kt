package com.hhst.youtubelite.player.surface

import android.view.SurfaceView
import com.hhst.youtubelite.downloader.android.DeviceEvidence
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.background
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.hhst.youtubelite.R
import com.hhst.youtubelite.player.PlayerUiState
import com.hhst.youtubelite.player.GestureUi
import com.hhst.youtubelite.player.queue.QueueItem
import com.hhst.youtubelite.ui.theme.AppTheme
import com.tencent.mmkv.MMKV
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.lang.reflect.Proxy
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.extractor.Extractor
import com.hhst.youtubelite.player.datasource.MediaSourceResolver
import kotlinx.coroutines.runBlocking
import org.koin.core.context.GlobalContext
import java.util.concurrent.atomic.AtomicLong

class PlayerInteractionAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun actions(block: (String, Array<out Any?>?) -> Unit = { _, _ -> }) =
        Proxy.newProxyInstance(PlayerSurfaceCallbacks::class.java.classLoader,
            arrayOf(PlayerSurfaceCallbacks::class.java)) { proxy, method, args ->
                when (method.name) {
                    "equals" -> proxy === args?.get(0)
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "TestPlayerActions"
                    else -> { block(method.name, args); null }
                }
            } as PlayerSurfaceCallbacks

    @Test fun seekArrowsFollowDirectionAndKeepAccumulatedSeconds() {
        compose.mainClock.autoAdvance = false
        val gesture = mutableStateOf<GestureUi?>(GestureUi.DoubleTapSeek(true, 10_000))
        compose.setContent { AppTheme {
            Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)) {
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).align(Alignment.Center)
                    .background(androidx.compose.ui.graphics.Color(0xFF242424)).testTag("gesture-frame")) {
                    GestureOverlays(gesture, Modifier.fillMaxSize())
                }
            }
        } }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithContentDescription(">>").assertIsDisplayed()
        compose.onNodeWithText("+10s").assertIsDisplayed()
        val before = compose.onNodeWithContentDescription(">>").captureToImage().toPixelMap()
        compose.mainClock.advanceTimeBy(200)
        val after = compose.onNodeWithContentDescription(">>").captureToImage().toPixelMap()
        var changedPixels = 0
        for (y in 0 until minOf(before.height, after.height)) for (x in 0 until minOf(before.width, after.width)) {
            if (before[x, y] != after[x, y]) changedPixels++
        }
        val animations = android.provider.Settings.Global.getFloat(compose.activity.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
        if (animations) assertTrue("Animated arrows did not move or fade", changedPixels > 5)
        else assertEquals("Disabled animations must keep static arrows", 0, changedPixels)
        compose.runOnIdle { gesture.value = GestureUi.DoubleTapSeek(true, 30_000) }
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithContentDescription(">>").assertIsDisplayed()
        compose.onNodeWithText("+30s").assertIsDisplayed()
        DeviceEvidence.captureScene("youtube-forward")
        compose.runOnIdle { gesture.value = GestureUi.DoubleTapSeek(false, -10_000) }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithContentDescription("<<").assertIsDisplayed()
        compose.onNodeWithContentDescription(">>").assertDoesNotExist()
        compose.onNodeWithText("-10s").assertIsDisplayed()
        DeviceEvidence.captureScene("youtube-rewind")
        compose.runOnIdle { gesture.value = null }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithContentDescription("<<").assertDoesNotExist()
        compose.runOnIdle { gesture.value = GestureUi.SpeedHold }
        compose.mainClock.advanceTimeBy(100)
        val speed = compose.onNodeWithText(compose.activity.getString(R.string.speed_hold_2x))
        speed.assertIsDisplayed()
        val frame = compose.onNodeWithTag("gesture-frame").fetchSemanticsNode().boundsInRoot
        assertTrue(speed.fetchSemanticsNode().boundsInRoot.top < frame.top + frame.height * .25f)
        val speedBefore = compose.onNodeWithContentDescription(">>").captureToImage().toPixelMap()
        compose.mainClock.advanceTimeBy(200)
        val speedAfter = compose.onNodeWithContentDescription(">>").captureToImage().toPixelMap()
        var speedChanges = 0
        for (y in 0 until speedBefore.height) for (x in 0 until speedBefore.width) {
            if (speedBefore[x,y] != speedAfter[x,y]) speedChanges++
        }
        if (animations) assertTrue(speedChanges > 5) else assertEquals(0, speedChanges)
        DeviceEvidence.captureScene("youtube-speed-hold")
        compose.runOnIdle { gesture.value = null }
        compose.mainClock.advanceTimeByFrame()
        speed.assertDoesNotExist()
    }

    @Test fun landscapeMiniResizesAndRemembersBothDocks() {
        compose.activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        compose.waitForIdle()
        val kv = MMKV.mmkvWithID("followup-landscape-mini")!!
        kv.clearAll()
        val store = MiniPlayerStore(kv)
        store.save(280, 0f, -50f)
        var handle: MiniPlayerHandle? = null
        compose.setContent {
            AppTheme {
                val screenWidth = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp
                MiniPlayerWindow(screenWidth, 24, store, true, {}, onDismiss = {}, topInsetDp = 24) {
                    handle = LocalMiniPlayerHandle.current
                    Box(Modifier.fillMaxSize().testTag("mini-viewport"))
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { handle!!.begin(); handle!!.pinchScale(1.2f); handle!!.end(true) }
        compose.waitForIdle()
        assertEquals(336, store.load().widthDp)
        compose.runOnIdle { handle!!.begin(); handle!!.dragFromStart(-10_000f, -10_000f); handle!!.end(true) }
        compose.waitForIdle()
        assertTrue(store.load().translationXDp < 0)
        compose.onNodeWithTag("mini-viewport").assertIsDisplayed()
        val left = store.load()
        assertEquals(left, MiniPlayerStore(kv).load())
        compose.runOnIdle { handle!!.begin(); handle!!.dragFromStart(10_000f, 10_000f); handle!!.end(true) }
        compose.waitForIdle()
        assertTrue(store.load().translationXDp >= 0)
        compose.onNodeWithTag("mini-viewport").assertIsDisplayed()
        assertEquals(store.load(), MiniPlayerStore(kv).load())
        kv.clearAll()
        compose.activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    @Test fun miniPositionSurvivesProcessRestart() {
        compose.activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assertRestart = InstrumentationRegistry.getArguments().getString("miniRestartStage") == "assert"
        val kv = MMKV.mmkvWithID("followup-mini-process")!!
        val store = MiniPlayerStore(kv)
        val record = java.io.File(instrumentation.targetContext.filesDir, "followup-mini-position.json")
        val expected = if (assertRestart) com.google.gson.Gson().fromJson(record.readText(), MiniPlayerStore.State::class.java)
            else null
        if (expected != null) assertEquals(expected, store.load())
        else { kv.clearAll(); store.save(250, -100f, -75f) }
        compose.setContent {
            AppTheme {
                val screenWidth = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp
                MiniPlayerWindow(screenWidth, 24, store, true, {}, topInsetDp = 24) { Box(Modifier.fillMaxSize()) }
            }
        }
        compose.waitForIdle()
        if (expected != null) assertEquals(expected, store.load())
        else record.writeText(com.google.gson.Gson().toJson(store.load()))
    }

    @Test fun miniRestoreKeepsSurfaceAndSettledPositionAndCloseAnimates() {
        val kv = MMKV.mmkvWithID("followup-mini-test")!!
        kv.clearAll()
        val store = MiniPlayerStore(kv)
        store.save(220, -110f, -125f)
        var mini by mutableStateOf(false)
        var visible by mutableStateOf(true)
        var attached = 0
        var detached = 0
        var handle: MiniPlayerHandle? = null
        var firstSurface: SurfaceView? = null
        val callbacks = actions { method, _ ->
            when (method) { "onMiniRestore" -> mini = false; "onMiniClose" -> visible = false }
        }
        compose.setContent {
            AppTheme(darkTheme = true, dynamicColor = false) {
                Box(Modifier.fillMaxSize()) {
                    if (visible) MiniPlayerWindow(411, 24, store, true, {}, onDismiss = { visible = false },
                        mini = mini, embeddedTopDp = 24, embeddedHeightDp = 231) {
                        handle = LocalMiniPlayerHandle.current
                        PlayerSurface(PlayerUiState(visible = true, mini = mini, title = "Mini test"),
                            remember { mutableStateOf(0L) }, remember { mutableStateOf(0L) },
                            remember { mutableStateOf(null) }, remember { mutableStateOf(null) },
                            remember { mutableStateOf(emptyList()) }, callbacks,
                            { attached++; if (firstSurface == null) firstSurface = it else assertNotSame(firstSurface, it) },
                            { detached++ }, managedByHost = true)
                    }
                }
            }
        }
        compose.waitForIdle()
        assertEquals(1, attached)
        compose.runOnIdle { mini = true }
        compose.waitForIdle()
        val loaded = store.load()
        assertEquals(220, loaded.widthDp)
        assertEquals(-125f, loaded.translationYDp, 1f)
        compose.runOnIdle { handle!!.begin(); handle!!.dragFromStart(-120f, -50f); handle!!.end(true) }
        compose.waitForIdle()
        val dragged = store.load()
        compose.runOnIdle { mini = false }
        compose.waitForIdle()
        assertEquals(1, attached)
        assertEquals(0, detached)
        compose.runOnIdle { mini = true }
        compose.waitForIdle()
        assertEquals(dragged, store.load())
        compose.runOnIdle { handle!!.dismiss() }
        compose.waitForIdle()
        assertFalse(visible)
        assertEquals(dragged, store.load())
        assertEquals(1, detached)
        compose.runOnIdle { visible = true }
        compose.waitForIdle()
        assertEquals(dragged, store.load())
        assertEquals(2, attached)
        kv.clearAll()
    }

    @Test fun moreQueuesCurrentVideoAndClosesWithFeedback() {
        var sheet by mutableStateOf<PlayerSheet?>(PlayerSheet.More)
        var item: QueueItem? = null
        var hint: String? = null
        val callbacks = actions { method, args ->
            if (method == "onQueueAdd") item = args!![0] as QueueItem
            if (method == "onHint") hint = args!![0] as String
        }
        compose.setContent {
            AppTheme(darkTheme = true, dynamicColor = false) {
                PlayerSheetHost(sheet, PlayerUiState(videoId = "abcdefghijk", title = "Queue clip", author = "Channel"),
                    callbacks, { sheet = null }, {})
            }
        }
        compose.onNodeWithText(compose.activity.getString(R.string.add_to_queue)).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals("abcdefghijk", item!!.videoId)
        assertEquals("Queue clip", item!!.title)
        assertEquals(compose.activity.getString(R.string.queue_item_added), hint)
        assertNull(sheet)
    }

    @androidx.media3.common.util.UnstableApi
    @Test fun decodedPlaybackContinuesThroughMiniAndRestoreWithoutSurfaceReplacement() {
        val extraction = GlobalContext.get().get<Extractor>().extract("jNQXAC9IVRw")
        val source = runBlocking {
            GlobalContext.get().get<MediaSourceResolver>().resolve(extraction.stream.await(), extraction.metadata.await())
        }
        lateinit var player: ExoPlayer
        compose.runOnUiThread {
            player = ExoPlayer.Builder(compose.activity).build().apply {
                volume = 0f
                setMediaSource(source.mediaSource)
                prepare()
                play()
            }
        }
        val store = MiniPlayerStore(MMKV.mmkvWithID("followup-live-mini")!!)
        var mini by mutableStateOf(false)
        var attached = 0
        var detached = 0
        val callbacks = actions()
        compose.setContent {
            AppTheme(darkTheme = true, dynamicColor = false) {
                MiniPlayerWindow(411, 24, store, false, {}, mini = mini, embeddedTopDp = 24, embeddedHeightDp = 231) {
                    PlayerSurface(PlayerUiState(visible = true, mini = mini),
                        remember { mutableStateOf(0L) }, remember { mutableStateOf(0L) },
                        remember { mutableStateOf(null) }, remember { mutableStateOf(null) },
                        remember { mutableStateOf(emptyList()) }, callbacks,
                        { attached++; player.setVideoSurfaceView(it) },
                        { detached++; player.clearVideoSurfaceView(it) }, managedByHost = true)
                }
            }
        }
        fun sample(): Pair<Long, Int> {
            val position = AtomicLong()
            var frames = 0
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                player.videoDecoderCounters?.ensureUpdated()
                position.set(player.currentPosition)
                frames = player.videoDecoderCounters?.renderedOutputBufferCount ?: 0
                assertNull(player.playerError)
            }
            return position.get() to frames
        }
        try {
            compose.waitUntil(30_000) { sample().second > 5 }
            val before = sample()
            repeat(4) {
                compose.runOnIdle { mini = !mini }
                compose.waitForIdle()
                Thread.sleep(350)
            }
            val after = sample()
            assertEquals(1, attached)
            assertEquals(0, detached)
            assertTrue(after.second > before.second)
            assertTrue("Progress jumped or stopped", after.first - before.first in 500..5_000)
        } finally { compose.runOnUiThread { player.release() } }
    }
}
