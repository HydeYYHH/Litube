package com.hhst.youtubelite.comments

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.comments.data.FixtureCommentsRepository
import com.hhst.youtubelite.comments.domain.CommentSort
import com.hhst.youtubelite.comments.domain.CommentsContent
import com.hhst.youtubelite.comments.domain.CommentsUiState
import com.hhst.youtubelite.comments.presentation.CommentsPanel
import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Screenshot acceptance for the comments panel at the sampled widths. */
@SdkSuppress(minSdkVersion = 35, maxSdkVersion = 35)
class CommentsPanelScreenshotAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Composable
    private fun Viewport(width: Int, height: Int, fontScale: Float, content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
                Box(Modifier.requiredSize(width.dp, height.dp)) { content() }
            }
        }
    }

    @Test fun commentPanelStatesMatchGoldens() {
        var width by mutableStateOf(390)
        var height by mutableStateOf(844)
        var fontScale by mutableStateOf(1f)
        var content by mutableStateOf<CommentsContent>(
            CommentsContent.Ready(FixtureCommentsRepository.sampleThreads(CommentSort.Top), "4"),
        )
        var expanded by mutableStateOf<Set<String>>(emptySet())
        compose.setContent {
            Viewport(width, height, fontScale) {
                CommentsPanel(
                    state = CommentsUiState(videoId = "bunny", content = content, expanded = expanded),
                    commentCount = "5.2K",
                    onAction = {},
                    onClose = {},
                )
            }
        }
        val output = File(compose.activity.getExternalFilesDir(null), "comments-acceptance").apply { mkdirs() }
        val record = InstrumentationRegistry.getArguments().getString("recordCommentsGoldens") == "true"
        val goldens = setOf("comments-390", "comments-1280", "comments-replies-390")
        val window = compose.activity.windowManager.currentWindowMetrics.bounds

        assertTrue("Use a 1600x1200 density-160 host", window.width() >= 1600 && window.height() >= 1100)
        for ((w, h) in listOf(390 to 844, 1280 to 800)) {
            compose.runOnIdle { width = w; height = h; fontScale = 1f }
            capture("comments-$w", output, record, goldens)
        }
        if (window.height() >= 844) {
            compose.runOnIdle {
                width = 390; height = 844; expanded = setOf("c1")
            }
            capture("comments-replies-390", output, record, goldens)

            compose.runOnIdle { expanded = emptySet(); content = CommentsContent.Loading }
            capture("comments-loading-390", output, record, setOf())
            compose.runOnIdle { content = CommentsContent.Empty }
            capture("comments-empty-390", output, record, setOf())
            compose.runOnIdle { content = CommentsContent.Disabled }
            capture("comments-disabled-390", output, record, setOf())
            compose.runOnIdle { content = CommentsContent.Failed }
            capture("comments-error-390", output, record, setOf())
            compose.runOnIdle {
                content = CommentsContent.Ready(FixtureCommentsRepository.sampleThreads(CommentSort.Top), "4")
            }
        }

        for ((w, h) in listOf(320 to 844, 390 to 844, 411 to 844, 600 to 960, 840 to 1000, 930 to 800, 931 to 800, 1279 to 800, 1280 to 800, 1600 to 900)) {
            for ((scene, state) in listOf(
                "base" to CommentsContent.Ready(FixtureCommentsRepository.sampleThreads(CommentSort.Top)),
                "loading" to CommentsContent.Loading, "empty" to CommentsContent.Empty,
                "disabled" to CommentsContent.Disabled, "error" to CommentsContent.Failed,
            )) {
                compose.runOnIdle { width = w; height = h; fontScale = 1f; content = state }
                capture("comments-$scene-$w", output, record, setOf())
            }
        }
        for ((scene, state) in listOf(
            "pagination-loading" to CommentsContent.Ready(FixtureCommentsRepository.sampleThreads(CommentSort.Top).take(4), "4", loadingMore = true),
            "pagination-error" to CommentsContent.Ready(FixtureCommentsRepository.sampleThreads(CommentSort.Top).take(4), "4", loadMoreFailed = true),
        )) {
            compose.runOnIdle { width = 390; height = 844; content = state }
            compose.onNodeWithTag("comments-list").performScrollToIndex(5)
            compose.onNodeWithTag(if (scene.endsWith("error")) "comments-footer-retry" else "comments-footer-loading").assertIsDisplayed()
            capture("comments-$scene-390", output, record, setOf())
        }
        compose.runOnIdle { content = CommentsContent.Ready(FixtureCommentsRepository.sampleThreads(CommentSort.Top)) }
        compose.onNodeWithTag("comments-list").performScrollToIndex(0)
        for ((w, h) in listOf(840 to 390, 320 to 240)) {
            compose.runOnIdle { width = w; height = h }
            capture("comments-landscape-$w", output, record, setOf())
        }
        if (window.height() >= 844) {
            compose.runOnIdle { width = 320; height = 844; fontScale = 2f }
            capture("comments-font2-320", output, record, setOf())
        }
    }

    private fun capture(name: String, output: File, record: Boolean, goldens: Set<String>) {
        compose.waitForIdle()
        val root = compose.onNodeWithTag("comments-panel").fetchSemanticsNode().boundsInRoot
        val actual = compose.onNodeWithTag("comments-panel").captureToImage().asAndroidBitmap()
        assertEquals("Capture must contain the whole panel", root.width.toInt(), actual.width)
        assertEquals("Capture must contain the whole panel", root.height.toInt(), actual.height)
        File(output, "$name.png").outputStream().use { actual.compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (record || name !in goldens) return
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val expected = assets.open("comments/goldens/$name.png").use(BitmapFactory::decodeStream)
        assertEquals("$name width", expected.width, actual.width)
        assertEquals("$name height", expected.height, actual.height)
        val expectedPixels = IntArray(expected.width * expected.height)
        val actualPixels = IntArray(expectedPixels.size)
        expected.getPixels(expectedPixels, 0, expected.width, 0, 0, expected.width, expected.height)
        actual.getPixels(actualPixels, 0, actual.width, 0, 0, actual.width, actual.height)
        val changed = expectedPixels.indices.count { i ->
            listOf(0, 8, 16).any { shift -> abs((expectedPixels[i] shr shift and 255) - (actualPixels[i] shr shift and 255)) > 2 }
        }
        assertTrue("$name changed $changed pixels; inspect the saved screenshot", changed.toFloat() / expectedPixels.size <= 0.001f)
        expected.recycle()
    }
}
