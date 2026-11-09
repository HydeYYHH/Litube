package com.hhst.youtubelite.library

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.library.data.FixtureLibraryRepository
import com.hhst.youtubelite.library.data.SignedOutPrompts
import com.hhst.youtubelite.library.domain.*
import com.hhst.youtubelite.library.presentation.CollectionScreen
import com.hhst.youtubelite.library.presentation.HistoryScreen
import com.hhst.youtubelite.library.presentation.YouScreen
import com.hhst.youtubelite.subscriptions.data.FixtureSubscriptionsRepository
import com.hhst.youtubelite.subscriptions.data.subscriptionsSignedOutPrompt
import com.hhst.youtubelite.subscriptions.domain.*
import com.hhst.youtubelite.subscriptions.presentation.SubscriptionsScreen
import java.io.File
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** API 35/SwiftShader; golden matrix 390x844 and 1280x800 at density 1. */
@SdkSuppress(minSdkVersion = 35, maxSdkVersion = 35)
class LibraryScreenshotAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun shortErrorWindowsKeepRetryReachableAtLargeFont() {
        var scene by mutableIntStateOf(0)
        var retries = 0
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                key(scene) {
                    Box(Modifier.requiredSize(320.dp, 240.dp)) {
                        when (scene) {
                            0 -> YouScreen(LibraryUiState(content = LibraryContent.Failed), { if (it == LibraryAction.Retry) retries++ })
                            else -> SubscriptionsScreen(SubscriptionsUiState(content = SubscriptionsContent.Failed), { if (it == SubscriptionsAction.Retry) retries++ })
                        }
                    }
                }
            }
        }
        repeat(2) { index ->
            compose.runOnIdle { scene = index }
            compose.onNodeWithText("Retry").performScrollTo().assertIsDisplayed().performClick()
            compose.runOnIdle { assertEquals(index + 1, retries) }
        }
    }

    @Test fun libraryStatesMatchGoldens() {
        var width by mutableIntStateOf(390)
        var height by mutableIntStateOf(844)
        var you by mutableStateOf(LibraryUiState())
        var history by mutableStateOf(HistoryUiState())
        var playlists by mutableStateOf(CollectionUiState())
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                key(width, if (playlists != CollectionUiState()) "playlists" else if (history != HistoryUiState()) "history" else "you") {
                Box(Modifier.requiredSize(width.dp, height.dp)) {
                    if (history == HistoryUiState() && playlists == CollectionUiState()) YouScreen(you, {})
                    if (history != HistoryUiState()) HistoryScreen(history, {}, {})
                    if (playlists != CollectionUiState()) {
                        CollectionScreen(playlists, {}, {})
                    }
                }
                }
            }
        }
        val ready = LibraryContent.Ready(YouPage(FixtureLibraryRepository.profile(),
            FixtureLibraryRepository.historyEntries(), FixtureLibraryRepository.collections()))
        for ((w, h) in listOf(390 to 844, 1280 to 800)) {
            compose.runOnIdle { width = w; height = h; you = LibraryUiState(signedIn = true, content = ready); history = HistoryUiState(); playlists = CollectionUiState() }
            capture("you-$w", "you-screen", output, record, goldens)
            compose.runOnIdle { you = LibraryUiState(); history = HistoryUiState(content = HistoryContent.Ready(HistoryPage(FixtureLibraryRepository.historyEntries()))) }
            compose.waitForIdle()
            capture("history-$w", "history-screen", output, record, goldens)
            compose.runOnIdle { history = HistoryUiState(); playlists = CollectionUiState(content = CollectionContent.Ready(FixtureLibraryRepository.collections())) }
            compose.waitForIdle()
            capture("playlists-$w", "playlists-screen", output, record, goldens)
        }
        compose.runOnIdle { width = 390; height = 844; playlists = CollectionUiState(); you = LibraryUiState(content = LibraryContent.SignedOut(SignedOutPrompts.forContent("you"))) }
        compose.waitForIdle()
        capture("you-signed-out-390", "you-screen", output, record, goldens)
        compose.runOnIdle { you = LibraryUiState(); history = HistoryUiState(content = HistoryContent.Empty()) }
        compose.waitForIdle()
        capture("history-empty-390", "history-screen", output, record, goldens)
        compose.runOnIdle { history = HistoryUiState(content = HistoryContent.Failed) }
        compose.waitForIdle()
        capture("history-error-390", "history-screen", output, record, goldens)
    }

    @Test fun subscriptionsMatchGoldens() {
        var width by mutableIntStateOf(390)
        var height by mutableIntStateOf(844)
        var subscriptions by mutableStateOf(SubscriptionsUiState())
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                Box(Modifier.requiredSize(width.dp, height.dp)) {
                    key(width) {
                        SubscriptionsScreen(subscriptions, {})
                    }
                }
            }
        }
        // nextKey stays null so the append row never enters the golden frames.
        val channels = SubscriptionsChannels.Ready(FixtureSubscriptionsRepository.channels())
        val feed = SubscriptionsContent.Feed(
            SubscriptionsPage(FixtureSubscriptionsRepository.sampleVideos(), emptyList()))
        for ((w, h) in listOf(390 to 844, 1280 to 800)) {
            compose.runOnIdle { width = w; height = h; subscriptions = SubscriptionsUiState(signedIn = true, content = feed, channels = channels) }
            compose.waitForIdle()
            capture("subscriptions-$w", "subscriptions-screen", output, record, goldens)
            compose.runOnIdle { subscriptions = subscriptions.copy(overlay = SubscriptionsOverlay.Channels) }
            compose.waitForIdle()
            if (w == 390) capture("channels-$w", "channels-overlay", output, record, goldens)
        }
        compose.runOnIdle { width = 390; height = 844; subscriptions = SubscriptionsUiState(content = SubscriptionsContent.SignedOut(subscriptionsSignedOutPrompt())) }
        compose.waitForIdle()
        capture("subscriptions-signed-out-390", "subscriptions-screen", output, record, goldens)
    }

    private val output: File
        get() = File(compose.activity.getExternalFilesDir(null), "library-acceptance").apply { mkdirs() }
    private val goldens: Set<String>
        get() = setOf("you-390", "you-1280", "history-390", "history-1280", "playlists-390", "playlists-1280",
            "you-signed-out-390", "history-empty-390", "history-error-390",
            "subscriptions-390", "subscriptions-1280", "channels-390",
            "subscriptions-signed-out-390")
    private val record: Boolean
        get() = InstrumentationRegistry.getArguments().getString("recordLibraryGoldens") == "true"

    private fun capture(name: String, tag: String, output: File, record: Boolean, goldens: Set<String>) {
        compose.waitForIdle()
        val nodes = compose.onAllNodesWithTag(tag).fetchSemanticsNodes()
        assertTrue("no $tag node for $name", nodes.isNotEmpty())
        val actual = compose.onAllNodesWithTag(tag)[0].captureToImage().asAndroidBitmap()
        if (name != "channels-390") {
            assertEquals("viewport width for $name", name.substringAfterLast('-').toInt(), actual.width)
        }
        File(output, "$name.png").outputStream().use { actual.compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (record || name !in goldens) return
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val expected = assets.open("library/goldens/$name.png").use(BitmapFactory::decodeStream)
        assertEquals("$name width", expected.width, actual.width)
        assertEquals("$name height", expected.height, actual.height)
        val expectedPixels = IntArray(expected.width * expected.height)
        val actualPixels = IntArray(expectedPixels.size)
        expected.getPixels(expectedPixels, 0, expected.width, 0, 0, expected.width, expected.height)
        actual.getPixels(actualPixels, 0, actual.width, 0, 0, actual.width, actual.height)
        val changed = expectedPixels.indices.count { i -> listOf(0, 8, 16).any { shift ->
            abs((expectedPixels[i] shr shift and 255) - (actualPixels[i] shr shift and 255)) > 2
        } }
        assertTrue("$name changed $changed pixels; inspect the saved screenshot", changed.toFloat() / expectedPixels.size <= 0.001f)
        expected.recycle()
    }
}
