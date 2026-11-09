package com.hhst.youtubelite.batchd

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.feedback.presentation.FeedbackReason
import com.hhst.youtubelite.feedback.presentation.FeedbackSheet
import com.hhst.youtubelite.post.presentation.PostImageViewer
import com.hhst.youtubelite.saves.domain.NewPlaylistDraft
import com.hhst.youtubelite.saves.domain.SavePlaylist
import com.hhst.youtubelite.saves.presentation.NewPlaylistSheet
import com.hhst.youtubelite.saves.presentation.SaveToPlaylistSheet
import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Batch D sheets, reason chooser and image viewer frames at density 1. */
@SdkSuppress(minSdkVersion = 35, maxSdkVersion = 35)
class BatchDScreenshotAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun playlistFormKeepsItsActionsReachableInAShortLargeFontWindow() {
        var draft by mutableStateOf(NewPlaylistDraft("Movies", "A long description ".repeat(100)))
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                Box(Modifier.consumeWindowInsets(WindowInsets.safeDrawing).requiredSize(320.dp, 240.dp)) {
                    NewPlaylistSheet(draft, {}, { draft = it }, {})
                }
            }
        }
        compose.onNodeWithTag("new-playlist-create").performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("new-playlist-cancel").assertIsDisplayed()
    }

    @Test fun saveAndPlaylistSheetsMatchGoldens() {
        var width by mutableIntStateOf(390)
        var height by mutableIntStateOf(844)
        var form by mutableStateOf(false)
        var draft by mutableStateOf(NewPlaylistDraft())
        val playlists = listOf(SavePlaylist("LL", "Liked videos"))
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                Box(Modifier.consumeWindowInsets(WindowInsets.safeDrawing)
                    .requiredSize(width.dp, height.dp).background(Color.White).testTag("batchd-scene")) {
                    if (form) NewPlaylistSheet(
                        draft = draft,
                        onDismiss = {},
                        onDraftChange = { draft = it },
                        onSubmit = {},
                    ) else SaveToPlaylistSheet(
                        playlists = playlists,
                        onDismiss = {},
                        onSelect = {},
                        onNewPlaylist = { form = true },
                    )
                }
            }
        }
        var sheet = compose.onNodeWithTag("save-to-playlist").fetchSemanticsNode().boundsInRoot
        assertEquals(390f, sheet.width, 0.5f)
        assertEquals(0f, sheet.left, 0.5f)
        assertEquals(820f, sheet.bottom, 0.5f)
        val row = compose.onNodeWithTag("save-menu-watch-later").fetchSemanticsNode().boundsInRoot
        assertEquals(48f, row.height, 0.5f)
        capture("save-to-playlist-390", "saves/goldens/save-to-playlist-390.png")
        compose.runOnIdle { width = 1280; height = 800 }
        sheet = compose.onNodeWithTag("save-to-playlist").fetchSemanticsNode().boundsInRoot
        assertEquals(400f, sheet.width, 0.5f)
        assertEquals(440f, sheet.left, 0.5f)
        assertEquals(776f, sheet.bottom, 0.5f)
        capture("save-to-playlist-1280", "saves/goldens/save-to-playlist-1280.png")

        compose.runOnIdle {
            width = 390; height = 844; form = true
            draft = NewPlaylistDraft("Night walks", "")
        }
        val formSheet = compose.onNodeWithTag("new-playlist-sheet").fetchSemanticsNode().boundsInRoot
        assertEquals(390f, formSheet.width, 0.5f)
        assertEquals(820f, formSheet.bottom, 0.5f)
        assertTrue("Form sheet height ${formSheet.height}", formSheet.height >= 120f)
        compose.onNodeWithTag("new-playlist-title").assertIsDisplayed()
        capture("new-playlist-390", "saves/goldens/new-playlist-390.png")
    }

    @Test fun feedbackReasonChooserMatchesGolden() {
        var width by mutableIntStateOf(390)
        var height by mutableIntStateOf(844)
        val reasons = listOf("I'm not interested in this video", "It's repetitive", "I've already seen it")
            .mapIndexed { index, label -> FeedbackReason(label, "feedback-reason-$index") }
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                Box(Modifier.consumeWindowInsets(WindowInsets.safeDrawing)
                    .requiredSize(width.dp, height.dp).background(Color.White).testTag("batchd-scene")) {
                    FeedbackSheet("Tell us why", reasons, onDismiss = {}, onSelect = {})
                }
            }
        }
        val sheet = compose.onNodeWithTag("feedback-sheet").fetchSemanticsNode().boundsInRoot
        assertEquals(390f, sheet.width, 0.5f)
        assertEquals(820f, sheet.bottom, 0.5f)
        assertEquals(3, compose.onAllNodesWithTag("feedback-reason-0").fetchSemanticsNodes().size +
            compose.onAllNodesWithTag("feedback-reason-1").fetchSemanticsNodes().size +
            compose.onAllNodesWithTag("feedback-reason-2").fetchSemanticsNodes().size)
        capture("feedback-reason-390", "feedback/goldens/feedback-reason-390.png")
        compose.runOnIdle { width = 1280; height = 800 }
        capture("feedback-reason-1280", "feedback/goldens/feedback-reason-1280.png")
    }

    @Test fun postImageViewerMatchesGoldens() {
        var width by mutableIntStateOf(390)
        var height by mutableIntStateOf(844)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                Box(Modifier.consumeWindowInsets(WindowInsets.safeDrawing)
                    .requiredSize(width.dp, height.dp).testTag("batchd-scene")) {
                    PostImageViewer(
                        images = listOf("bunny", "dream", "sintel"),
                        initialIndex = 0,
                        onClose = {},
                        onPageSelected = {},
                    )
                }
            }
        }
        assertEquals(48f, compose.onNodeWithTag("post-image-viewer-close").fetchSemanticsNode().boundsInRoot.height, 0.5f)
        capture("image-viewer-390", "post/goldens/image-viewer-390.png")
        compose.runOnIdle { width = 1280; height = 800 }
        capture("image-viewer-1280", "post/goldens/image-viewer-1280.png")
    }

    private fun capture(name: String, asset: String) {
        compose.waitForIdle()
        val actual = compose.onNodeWithTag("batchd-scene").captureToImage().asAndroidBitmap()
        val output = File(compose.activity.getExternalFilesDir(null), "batch-d-acceptance").apply { mkdirs() }
        File(output, "$name.png").outputStream().use { actual.compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (InstrumentationRegistry.getArguments().getString("recordBatchDGoldens") == "true") return
        val expected = InstrumentationRegistry.getInstrumentation().context.assets.open(asset)
            .use(BitmapFactory::decodeStream)
        assertEquals("$name width", expected.width, actual.width)
        assertEquals("$name height", expected.height, actual.height)
        val expectedPixels = IntArray(expected.width * expected.height)
        val actualPixels = IntArray(expectedPixels.size)
        expected.getPixels(expectedPixels, 0, expected.width, 0, 0, expected.width, expected.height)
        actual.getPixels(actualPixels, 0, actual.width, 0, 0, actual.width, actual.height)
        val changed = expectedPixels.indices.count { i ->
            listOf(0, 8, 16).any { shift ->
                abs((expectedPixels[i] shr shift and 255) - (actualPixels[i] shr shift and 255)) > 2
            }
        }
        assertTrue("$name changed $changed pixels; inspect the saved screenshot", changed.toFloat() / expectedPixels.size <= 0.001f)
        expected.recycle()
    }
}
