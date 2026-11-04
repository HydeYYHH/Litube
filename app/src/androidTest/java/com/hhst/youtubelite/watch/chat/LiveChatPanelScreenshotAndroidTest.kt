package com.hhst.youtubelite.watch.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** API 35/SwiftShader; live chat panel states at density 1. */
@SdkSuppress(minSdkVersion = 35, maxSdkVersion = 35)
class LiveChatPanelScreenshotAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val messages = listOf(
        LiveChatMessage("m1", "Aria", "hello from the stream", ""),
        LiveChatMessage("m2", "Ben", "that transition was clean", ""),
        LiveChatMessage("m3", "Cyan", "+1", ""),
    )

    @Test fun fullMessageWindowFollowsNewMessagesWithoutInterruptingReading() {
        var rows by androidx.compose.runtime.mutableStateOf((0..199).map {
            LiveChatMessage("m$it", "Reader", "message $it", "")
        })
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                Box(Modifier.consumeWindowInsets(WindowInsets.safeDrawing).requiredSize(390.dp, 844.dp)) {
                    LiveChatPanel(LiveChatUiState(LiveChatContent.Ready(rows), signedIn = true, canSend = false),
                        onAction = {}, onClose = {})
                }
            }
        }
        compose.onNodeWithText("message 199").assertIsDisplayed()
        compose.onNodeWithTag("live-chat-input").assertDoesNotExist()
        compose.onNodeWithTag("live-chat-readonly").assertIsDisplayed()
        compose.runOnIdle { rows = rows.drop(1) + LiveChatMessage("m200", "Reader", "message 200", "") }
        compose.onNodeWithText("message 200").assertIsDisplayed()
        compose.onNodeWithTag("live-chat-list").performScrollToIndex(20)
        compose.onNodeWithText("message 21").assertIsDisplayed()
        compose.runOnIdle { rows = rows.drop(1) + LiveChatMessage("m201", "Reader", "message 201", "") }
        compose.onNodeWithText("message 21").assertIsDisplayed()
        compose.onNodeWithText("message 201").assertDoesNotExist()
    }

    @Test fun panelStatesMatchGoldens() {
        var width by mutableIntStateOf(390)
        var height by mutableIntStateOf(844)
        var signedIn by androidx.compose.runtime.mutableStateOf(true)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                Box(Modifier.consumeWindowInsets(WindowInsets.safeDrawing)
                    .requiredSize(width.dp, height.dp).testTag("live-chat-scene")) {
                    LiveChatPanel(LiveChatUiState(LiveChatContent.Ready(messages), signedIn = signedIn),
                        onAction = {}, onClose = {})
                }
            }
        }
        capture("live-chat-390", output, record, goldens)
        compose.runOnIdle { width = 1280; height = 800 }
        capture("live-chat-1280", output, record, goldens)
        compose.runOnIdle { width = 390; height = 844; signedIn = false }
        capture("live-chat-signed-out-390", output, record, goldens)
    }

    private val output: File
        get() = File(compose.activity.getExternalFilesDir(null), "livechat-acceptance").apply { mkdirs() }
    private val goldens: Set<String>
        get() = setOf("live-chat-390", "live-chat-1280", "live-chat-signed-out-390")
    private val record: Boolean
        get() = InstrumentationRegistry.getArguments().getString("recordLiveChatGoldens") == "true"

    private fun capture(name: String, output: File, record: Boolean, goldens: Set<String>) {
        compose.waitForIdle()
        val actual = compose.onNodeWithTag("live-chat-scene").captureToImage().asAndroidBitmap()
        File(output, "$name.png").outputStream().use { actual.compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (record || name !in goldens) return
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val expected = assets.open("livechat/goldens/$name.png").use(BitmapFactory::decodeStream)
        assertEquals("$name width", expected.width, actual.width)
        assertEquals("$name height", expected.height, actual.height)
        val expectedPixels = IntArray(expected.width * expected.height)
        val actualPixels = IntArray(expectedPixels.size)
        expected.getPixels(expectedPixels, 0, expected.width, 0, 0, expected.width, expected.height)
        actual.getPixels(actualPixels, 0, actual.width, 0, 0, actual.width, actual.height)
        val changed = expectedPixels.indices.filter { i -> listOf(0, 8, 16).any { shift ->
            abs((expectedPixels[i] shr shift and 255) - (actualPixels[i] shr shift and 255)) > 2
        } }
        // Report the diff region so an intentional geometry change is distinguishable from unrelated drift.
        val changedArea = changed.fold(null as IntArray?) { area, i ->
            val x = i % expected.width; val y = i / expected.width
            if (area == null) intArrayOf(x, y, x, y)
            else intArrayOf(minOf(area[0], x), minOf(area[1], y), maxOf(area[2], x), maxOf(area[3], y))
        }
        assertTrue("$name changed ${changed.size} pixels in ${changedArea?.contentToString()}; inspect the saved screenshot",
            changed.size.toFloat() / expectedPixels.size <= 0.001f)
        expected.recycle()
    }
}
