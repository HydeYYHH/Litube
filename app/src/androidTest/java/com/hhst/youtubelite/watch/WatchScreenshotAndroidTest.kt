package com.hhst.youtubelite.watch

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.watch.data.FixtureWatchRepository
import com.hhst.youtubelite.watch.domain.*
import com.hhst.youtubelite.watch.presentation.WatchScreen
import java.io.File
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Geometry and screenshot regression checks at density 1. */
@SdkSuppress(minSdkVersion = 35, maxSdkVersion = 35)
class WatchScreenshotAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun contentOf(state: String): WatchContent = when (state) {
        "loading" -> WatchContent.Loading
        "error" -> WatchContent.Failed
        else -> WatchContent.Ready(FixtureWatchRepository.samplePage("bunny"))
    }

    @Composable
    private fun RenderScreen(content: WatchContent, onAction: (WatchAction) -> Unit = {}) = WatchScreen(
        state = WatchUiState(videoId = "bunny", content = content),
        onAction = onAction,
        onBack = {},
        // The real player needs a live PlayerViewModel; the page geometry only
        // depends on the slot's bounds, so tests substitute a black box.
        playerSlot = { slot -> Box(slot.background(Color.Black)) },
    )

    /** Oversized required-size boxes are otherwise centred inside the host window. */
    @Composable
    private fun Viewport(width: Int, height: Int, fontScale: Float = 1f, content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
                Box(Modifier.requiredSize(width.dp, height.dp)) { content() }
            }
        }
    }

    private fun bounds(tag: String) =
        compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun viewportShift() =
        compose.onNodeWithTag("watch-screen").fetchSemanticsNode().boundsInRoot.left

    /** Phone chrome at 411: pinned player, 118px metadata, natural action row, 113px comments. */
    @Test fun phoneChromeMatchesMeasuredAnchors() {
        compose.setContent { Viewport(411, 844) { RenderScreen(contentOf("content")) } }
        val bar = bounds("watch-topbar")
        assertEquals(0f, bar.left, 0.5f)
        assertEquals(0f, bar.top, 0.5f)
        assertEquals(48f, bar.height, 0.5f)
        assertEquals(411f, bar.width, 0.5f)
        val back = compose.onNodeWithContentDescription("Back").fetchSemanticsNode().boundsInRoot
        assertEquals(0f, back.left, 0.5f)
        assertEquals(48f, back.width, 0.5f)
        val more = compose.onNodeWithContentDescription("More options").fetchSemanticsNode().boundsInRoot
        assertEquals(403f, more.right, 0.5f)
        assertEquals(48f, more.width, 0.5f)

        val player = bounds("watch-player-slot")
        assertEquals(0f, player.left, 0.5f)
        assertEquals(48f, player.top, 0.5f)
        assertEquals(411f, player.width, 0.5f)
        assertEquals(411f * 9f / 16f, player.height, 0.6f)

        val meta = bounds("watch-metadata")
        assertEquals(48f + 411f * 9f / 16f, meta.top, 0.6f)
        assertEquals(118f, meta.height, 0.6f)

        val title = bounds("watch-title")
        assertEquals(16f, title.left, 0.5f)
        assertEquals(379f, title.width, 0.5f)
        val titleStyle = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("watch-title", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(titleStyle) }
        assertEquals(18.sp, titleStyle.single().layoutInput.style.fontSize)
        assertEquals(FontWeight.Black, titleStyle.single().layoutInput.style.fontWeight)
        assertEquals(1, titleStyle.single().lineCount)
        compose.onNodeWithTag("watch-title", useUnmergedTree = true)
            .assertTextEquals("Big Buck Bunny | A short film by the Blender Foundation")
        assertEquals(18f, bounds("watch-meta").height, 0.5f)

        val actions = bounds("watch-actions")
        assertEquals(48f, actions.height, 0.5f)
        assertEquals(meta.top + 70f, actions.top, 0.6f)
        val avatar = bounds("watch-action-avatar")
        assertEquals(8f, avatar.left, 0.5f)
        assertEquals(48f, avatar.width, 0.5f)
        assertEquals(48f, avatar.height, 0.5f)
        val subscribe = bounds("watch-action-subscribe")
        assertEquals(56f, subscribe.left, 0.5f)
        assertEquals(78f, subscribe.width, 0.5f)
        assertEquals(32f, subscribe.height, 0.5f)
        assertEquals(8f, subscribe.top - actions.top, 0.6f)

        fun rawBounds(tag: String): androidx.compose.ui.geometry.Rect {
            val node = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
            return androidx.compose.ui.geometry.Rect(node.positionInRoot, androidx.compose.ui.geometry.Size(node.size.width.toFloat(), node.size.height.toFloat()))
        }
        val like = bounds("watch-action-like")
        assertEquals(195f, like.left, 0.6f)
        assertEquals(48f, like.height, 0.5f)
        val dislike = bounds("watch-action-dislike")
        val share = rawBounds("watch-action-share")
        val save = rawBounds("watch-action-save")
        val report = rawBounds("watch-action-report")
        assertEquals(like.right - 7f, dislike.left, 0.6f)
        assertEquals(share.right - 7f, save.left, 0.6f)
        assertEquals(save.right - 7f, report.left, 0.6f)
        assertEquals(551f, report.right, 0.6f)
        fun slot(tag: String): Triple<Float, Float, Float> {
            val node = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
            return Triple(node.positionInRoot.x, node.size.width.toFloat(), node.size.height.toFloat())
        }
        val download = slot("watch-action-download")
        val queue = slot("watch-action-queue")
        val openWith = slot("watch-action-open-with")
        assertEquals(dislike.right - 7f, download.first, 0.6f)
        assertEquals(48f, download.second, 0.5f)
        assertEquals(48f, download.third, 0.5f)
        assertEquals(48f, queue.first - download.first, 0.6f)
        assertEquals(48f, openWith.first - queue.first, 0.6f)
        assertEquals(openWith.first + 48f, slot("watch-action-share").first, 0.6f)
        val surface = slot("watch-action-download-surface")
        assertEquals(40f, surface.second, 0.5f)
        assertEquals(36f, surface.third, 0.5f)
        compose.onNodeWithContentDescription("Download").assertExists()
        compose.onNodeWithContentDescription("Add to queue").assertExists()
        compose.onNodeWithContentDescription("Open with").assertExists()

        val comments = bounds("watch-comments")
        assertEquals(actions.bottom, comments.top, 0.5f)
        assertEquals(113f, comments.height, 0.5f)
        val card = bounds("watch-comments-card")
        assertEquals(16f, card.left, 0.5f)
        assertEquals(379f, card.width, 0.5f)
        assertEquals(92f, card.height, 0.5f)
        assertEquals(comments.top + 8f, card.top, 0.5f)
    }

    @Test fun actionGrowthTracksTheColumnWidth() {
        var width by mutableIntStateOf(411)
        var height by mutableIntStateOf(844)
        compose.setContent { Viewport(width, height) { RenderScreen(contentOf("content")) } }
        assertEquals(195f, bounds("watch-action-like").left, 0.6f)
        for ((w, h, likeLeft) in listOf(
            Triple(780, 844, 564f),
            // Portrait 1100 keeps the full column (landscape ≥931 would open the sidebar).
            Triple(1100, 1200, 884f),
        )) {
            compose.runOnIdle { width = w; height = h }
            assertEquals("$w like left", likeLeft + viewportShift(), bounds("watch-action-like").left, 0.6f)
        }
        compose.runOnIdle { width = 550; height = 844 }
        assertEquals("550 grows", 334f, bounds("watch-action-like").left, 0.6f)
        compose.runOnIdle { width = 320 }
        assertEquals("320 stays natural", 146f, bounds("watch-action-like").left, 0.6f)
    }

    /** Phone related lockup: full-bleed cover with +13, details at x12, kebab flush right. */
    @Test fun phoneRelatedLockupMatchesMeasuredAnchors() {
        compose.setContent { Viewport(390, 844) { RenderScreen(contentOf("content")) } }
        val cell = bounds("watch-related-harbour")
        assertEquals(0f, cell.left, 0.5f)
        assertEquals(390f, cell.width, 0.5f)
        val comments = bounds("watch-comments")
        val thumb = bounds("watch-thumb-harbour")
        assertEquals(0f, thumb.left, 0.5f)
        assertEquals(390f, thumb.width, 0.5f)
        assertEquals(232.375f, thumb.height, 0.6f)
        // Feed inset: comments bottom + 12; the phone cover has no extra top pad.
        assertEquals(comments.bottom + 12f, thumb.top, 0.6f)
        val avatar = bounds("watch-avatar-harbour")
        assertEquals(12f, avatar.left, 0.5f)
        assertEquals(40f, avatar.width, 0.5f)
        assertEquals(40f, avatar.height, 0.5f)
        assertEquals(thumb.bottom + 4f, avatar.top, 0.6f)
        val title = bounds("watch-related-title-harbour")
        assertEquals(64f, title.left, 0.5f)
        assertEquals(278f, title.width, 0.5f)
        assertEquals(thumb.bottom + 12f, title.top, 0.6f)
        val titleStyle = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("watch-related-title-harbour", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(titleStyle) }
        assertEquals(14.sp, titleStyle.single().layoutInput.style.fontSize)
        assertEquals(17.5.sp, titleStyle.single().layoutInput.style.lineHeight)
        assertEquals(FontWeight.Normal, titleStyle.single().layoutInput.style.fontWeight)
        val badge = bounds("watch-badge-harbour")
        assertEquals(thumb.right - 8f, badge.right, 0.6f)
        assertEquals(thumb.bottom - 8f, badge.bottom, 0.6f)
        val kebab = compose.onNodeWithContentDescription(
            "More actions for City light trails — a night walk through the old harbour",
        ).fetchSemanticsNode().boundsInRoot
        assertEquals(390f, kebab.right, 0.5f)
        assertEquals(48f, kebab.width, 0.5f)
    }

    /** Portrait grid at 600: 2 columns of 292, thumbs inset 8, row gap 0. */
    @Test fun gridRelatedRowsStayFlushAtSixHundred() {
        compose.setContent { Viewport(600, 960) { RenderScreen(contentOf("content")) } }
        val cell = bounds("watch-related-harbour")
        assertEquals(8f, cell.left, 0.5f)
        assertEquals(292f, cell.width, 0.5f)
        val thumb = bounds("watch-thumb-harbour")
        assertEquals(16f, thumb.left, 0.5f)
        assertEquals(276f, thumb.width, 0.5f)
        assertEquals(155.25f, thumb.height, 0.6f)
        val second = bounds("watch-related-studio")
        assertEquals(300f, second.left, 0.5f)
        assertEquals(292f, second.width, 0.5f)
        // Row gap 0: the next chunk starts at the previous chunk's bottom edge.
        val third = bounds("watch-related-meadow")
        val firstRowBottom = maxOf(cell.bottom, second.bottom)
        assertEquals(firstRowBottom, third.top, 0.6f)
    }

    /** Wide-short landscape list: 200×112 covers at x12, pitch 124, no avatar column. */
    @Test fun listRelatedRowsUseTheWideShortCover() {
        compose.setContent { Viewport(844, 342) { RenderScreen(contentOf("content")) } }
        val player = bounds("watch-player-slot")
        assertEquals(0f, player.left, 0.5f)
        assertEquals(48f, player.top, 0.5f)
        assertEquals(844f, player.width, 0.5f)
        // The wide-short player scrolls with the content, so the window clips its tall box.
        assertEquals(342f, player.bottom, 0.5f)
        compose.onNodeWithTag("watch-thumb-harbour", useUnmergedTree = true).performScrollTo()
        compose.onNodeWithTag("watch-thumb-studio", useUnmergedTree = true).performScrollTo()
        compose.onNodeWithTag("watch-thumb-harbour", useUnmergedTree = true).performScrollTo()
        val thumb = bounds("watch-thumb-harbour")
        assertEquals(12f, thumb.left, 0.5f)
        assertEquals(200f, thumb.width, 0.5f)
        assertEquals(112f, thumb.height, 0.5f)
        assertEquals(224f, bounds("watch-related-title-harbour").left, 0.5f)
        val second = bounds("watch-thumb-studio")
        assertEquals(thumb.bottom + 12f, second.top, 0.6f)
    }

    /** Stacked landscape: cover inset 12 with an exact 16:9 box, avatar back at x12. */
    @Test fun stackedRelatedUsesTheInsetCover() {
        compose.setContent { Viewport(840, 725) { RenderScreen(contentOf("content")) } }
        val player = bounds("watch-player-slot")
        assertEquals(48f, player.top, 0.5f)
        assertEquals(840f, player.width, 0.5f)
        assertEquals(840f * 9f / 16f, player.height, 0.6f)
        compose.onNodeWithTag("watch-thumb-harbour", useUnmergedTree = true).performScrollTo()
        val thumb = bounds("watch-thumb-harbour")
        assertEquals(12f, thumb.left, 0.5f)
        assertEquals(816f, thumb.width, 0.5f)
        assertEquals(459f, thumb.height, 0.6f)
        compose.onNodeWithTag("watch-related-title-harbour", useUnmergedTree = true).performScrollTo()
        // Title and avatar share the details row, so revealing the title reveals both.
        assertEquals(64f, bounds("watch-related-title-harbour").left, 0.5f)
        assertEquals(12f, bounds("watch-avatar-harbour").left, 0.5f)
    }

    /** Sidebar panes: compact 256 at 931, wide 500 at ≥1280; the player spans the main column. */
    @Test fun sidebarPanesMatchBothBreakpointWidths() {
        var width by mutableIntStateOf(931)
        var height by mutableIntStateOf(744)
        compose.setContent { Viewport(width, height) { RenderScreen(contentOf("content")) } }
        val pane = bounds("watch-related")
        assertEquals(675f, pane.left, 0.5f)
        assertEquals(256f, pane.width, 0.5f)
        val player = bounds("watch-player-slot")
        assertEquals(0f, player.left, 0.5f)
        assertEquals(48f, player.top, 0.5f)
        assertEquals(675f, player.width, 0.5f)
        assertEquals(675f * 9f / 16f, player.height, 0.6f)
        assertEquals(459f, bounds("watch-action-like").left, 0.6f)
        val cell = bounds("watch-related-harbour")
        assertEquals(683f, cell.left, 0.5f)
        assertEquals(240f, cell.width, 0.5f)
        assertEquals(52f, cell.top, 1f)
        val thumb = bounds("watch-thumb-harbour")
        assertEquals(683f, thumb.left, 0.5f)
        assertEquals(240f, thumb.width, 0.5f)
        assertEquals(135f, thumb.height, 0.6f)

        compose.runOnIdle { width = 1280; height = 725 }
        val wideShift = viewportShift()
        val widePane = bounds("watch-related")
        assertEquals(780f + wideShift, widePane.left, 0.5f)
        assertEquals(500f, widePane.width, 0.5f)
        assertEquals(780f, bounds("watch-player-slot").width, 0.5f)
        assertEquals(564f + wideShift, bounds("watch-action-like").left, 0.6f)
        val wideCell = bounds("watch-related-harbour")
        assertEquals(788f + wideShift, wideCell.left, 0.5f)
        assertEquals(246f, wideCell.width, 0.5f)
        assertEquals(48f, wideCell.top, 1f)
        val wideThumb = bounds("watch-thumb-harbour")
        assertEquals(238f, wideThumb.width, 0.5f)
        assertEquals(133.875f, wideThumb.height, 0.6f)
        assertEquals(1034f + wideShift, bounds("watch-related-studio").left, 0.5f)

        compose.runOnIdle { width = 1600; height = 915 }
        val tallShift = viewportShift()
        assertEquals(1100f + tallShift, bounds("watch-related").left, 0.5f)
        assertEquals(500f, bounds("watch-related").width, 0.5f)
        assertEquals(1100f, bounds("watch-player-slot").width, 0.5f)
        assertEquals(884f + tallShift, bounds("watch-action-like").left, 0.6f)
    }

    /** Content / loading / error are distinct surfaces; Retry emits exactly one typed action. */
    @Test fun stateMatrixSwitchesSurfaces() {
        var state by mutableStateOf(contentOf("content"))
        val actions = mutableListOf<WatchAction>()
        compose.setContent {
            Viewport(390, 844) { RenderScreen(state, onAction = { actions += it }) }
        }
        compose.onNodeWithTag("watch-metadata").assertIsDisplayed()
        compose.onNodeWithTag("watch-skeleton").assertDoesNotExist()
        compose.onNodeWithTag("watch-error").assertDoesNotExist()
        compose.runOnIdle { state = WatchContent.Loading }
        compose.onNodeWithTag("watch-skeleton").assertIsDisplayed()
        compose.onNodeWithTag("watch-metadata").assertDoesNotExist()
        compose.runOnIdle { state = WatchContent.Failed }
        compose.onNodeWithTag("watch-error").assertIsDisplayed()
        compose.onNodeWithText("Something went wrong").assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()
        assertEquals(listOf(WatchAction.Retry), actions)
        compose.onNodeWithTag("watch-error").assertIsDisplayed()
        compose.runOnIdle { state = contentOf("content") }
        compose.onNodeWithTag("watch-metadata").assertIsDisplayed()
        compose.onNodeWithTag("watch-error").assertDoesNotExist()
    }

    /** fontScale 2 at 320: the chrome stays inside the column and keeps its controls. */
    @Test fun enlargedTextKeepsTheTitleInsideTheColumn() {
        compose.setContent { Viewport(320, 844, fontScale = 2f) { RenderScreen(contentOf("content")) } }
        compose.onNodeWithTag("watch-title").assertIsDisplayed()
        val title = bounds("watch-title")
        assertTrue("Title must stay inside the phone column", title.right <= 320.5f)
        assertTrue("Like pill must stay measurable", bounds("watch-action-like").width > 0f)
        compose.onNodeWithContentDescription("Show more").assertExists()
        compose.onNodeWithTag("watch-related-harbour").assertExists()
        val output = File(compose.activity.getExternalFilesDir(null), "watch-acceptance").apply { mkdirs() }
        capture("content-320-font2", output, true, emptySet())
    }

    /** Split-style windows keep the top bar, player, and action bar inside the window. */
    @Test fun splitWindowsKeepTheChromeInsideTheWindow() {
        var width by mutableIntStateOf(390)
        var height by mutableIntStateOf(400)
        compose.setContent { Viewport(width, height) { key(width, height) { RenderScreen(contentOf("content")) } } }
        val output = File(compose.activity.getExternalFilesDir(null), "watch-acceptance").apply { mkdirs() }
        for ((w, h) in listOf(390 to 400, 411 to 500, 600 to 400, 320 to 320)) {
            compose.runOnIdle { width = w; height = h }
            val root = compose.onNodeWithTag("watch-screen").fetchSemanticsNode().boundsInRoot
            assertEquals("$w × $h root width", w.toFloat(), root.width, 0.5f)
            assertEquals("$w × $h root height", h.toFloat(), root.height, 0.5f)
            assertEquals(0f, root.left, 0.5f)
            val bar = bounds("watch-topbar")
            assertEquals(48f, bar.height, 0.5f)
            assertEquals(0f, compose.onNodeWithContentDescription("Back").fetchSemanticsNode().boundsInRoot.left, 0.5f)
            val player = bounds("watch-player-slot")
            assertEquals(48f, player.top, 0.5f)
            assertEquals(w.toFloat(), player.width, 0.5f)
            // Short windows push the action bar below the fold; scroll it into view.
            compose.onNodeWithTag("watch-actions").performScrollTo()
            assertEquals("$w × $h actions height", 48f, bounds("watch-actions").height, 0.5f)
            capture("split-$w-$h", output, true, emptySet())
        }
    }

    /** The related-card action sheet uses the measured 400 / inset geometry. */
    @Test fun relatedCardMenuSurfaceMatchesTheSheetGeometry() {
        var width by mutableIntStateOf(390)
        var height by mutableIntStateOf(844)
        var overlay by mutableStateOf<WatchOverlay>(WatchOverlay.ItemMenu("harbour"))
        compose.setContent {
            Viewport(width, height) {
                WatchScreen(
                    state = WatchUiState(
                        videoId = "bunny",
                        content = contentOf("content"),
                        overlay = overlay,
                    ),
                    onAction = {},
                    onBack = {},
                    playerSlot = { slot -> Box(slot.background(Color.Black)) },
                )
            }
        }
        val output = File(compose.activity.getExternalFilesDir(null), "watch-acceptance").apply { mkdirs() }
        val sheet = compose.onNodeWithTag("watch-video-menu").fetchSemanticsNode().boundsInRoot
        assertEquals(374f, sheet.width, 0.5f)
        assertEquals(8f, sheet.left, 0.5f)
        assertEquals(844f - 24f, sheet.bottom, 0.5f)
        assertEquals(48f, compose.onNodeWithTag("watch-menu-Share").fetchSemanticsNode().boundsInRoot.height, 0.5f)
        capture("video-menu-390", output, true, emptySet())

        compose.runOnIdle { width = 1280; height = 800 }
        val wideSheet = compose.onNodeWithTag("watch-video-menu").fetchSemanticsNode().boundsInRoot
        assertEquals(400f, wideSheet.width, 0.5f)
        assertEquals(440f, wideSheet.left, 0.5f)
        assertEquals(800f - 24f, wideSheet.bottom, 0.5f)
        capture("video-menu-1280", output, true, emptySet())

        compose.runOnIdle { overlay = WatchOverlay.None }
        compose.onNodeWithTag("watch-video-menu").assertDoesNotExist()
    }

    @Test fun viewportAndStateMatrixMatchesGoldens() {
        var width by mutableIntStateOf(390)
        var height by mutableIntStateOf(844)
        var fontScale by mutableFloatStateOf(1f)
        var state by mutableStateOf(contentOf("content"))
        compose.setContent {
            Viewport(width, height, fontScale) { RenderScreen(state) }
        }
        val output = File(compose.activity.getExternalFilesDir(null), "watch-acceptance").apply { mkdirs() }
        val record = InstrumentationRegistry.getArguments().getString("recordWatchGoldens") == "true"
        val goldens = listOf(390, 1280)
            .flatMap { w -> listOf("content", "loading", "error").map { "$it-$w" } }
            .toSet()
        for ((w, h) in listOf(320 to 844, 390 to 844, 411 to 844, 600 to 960, 840 to 1000, 1280 to 800, 1600 to 900)) {
            for (scene in listOf("content", "loading", "error")) {
                compose.runOnIdle {
                    width = w; height = h; fontScale = 1f
                    state = contentOf(scene)
                }
                capture("$scene-$w", output, record, goldens)
            }
        }
        for ((w, h) in listOf(840 to 390, 320 to 240)) {
            compose.runOnIdle {
                width = w; height = h; fontScale = if (w < 400) 2f else 1f
                state = contentOf("content")
            }
            capture("content-$w-landscape${if (w < 400) "-font2" else ""}", output, true, emptySet())
        }
    }

    @Test fun verifiedStatesMatchGoldens() {
        var width by mutableIntStateOf(390)
        var height by mutableIntStateOf(844)
        var state by mutableStateOf(contentOf("content"))
        compose.setContent { Viewport(width, height) { RenderScreen(state) } }
        val output = File(compose.activity.getExternalFilesDir(null), "watch-acceptance").apply { mkdirs() }
        val record = InstrumentationRegistry.getArguments().getString("recordWatchGoldens") == "true"
        val names = listOf(390, 1280)
            .flatMap { w -> listOf("content", "loading", "error").map { "$it-$w" } }
            .toSet()
        for ((w, h) in listOf(390 to 844, 1280 to 800)) {
            for (scene in listOf("content", "loading", "error")) {
                compose.runOnIdle { width = w; height = h; state = contentOf(scene) }
                capture("$scene-$w", output, record, names)
            }
        }
    }


    @Test fun shortWindowCanScrollThePlayerAwayAndReachRetry() {
        compose.setContent { Viewport(320, 240, 2f) { RenderScreen(contentOf("error")) } }
        compose.onNodeWithText("Retry").performScrollTo().assertIsDisplayed()
        assertTrue("Player must scroll away in a short window", bounds("watch-player-slot").bottom <= 48f)
        assertTrue("Retry must stay below the page toolbar",
            compose.onNodeWithText("Retry").fetchSemanticsNode().boundsInRoot.top >= 48f)
    }

    @Test fun portraitScrollSurvivesTheSidebarLayoutAndReturn() {
        var width by mutableIntStateOf(390)
        var height by mutableIntStateOf(844)
        compose.setContent { Viewport(width, height) { RenderScreen(contentOf("content")) } }
        compose.onNodeWithTag("watch-thumb-studio", useUnmergedTree = true).performScrollTo()
        fun scrollValue() = compose.onNodeWithTag("watch-scroll").fetchSemanticsNode().config[
            androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value()
        val before = scrollValue()
        assertTrue(before > 0f)
        compose.runOnIdle { width = 1280; height = 800 }
        compose.onNodeWithTag("watch-related").assertIsDisplayed()
        compose.runOnIdle { width = 390; height = 844 }
        assertEquals(before, scrollValue(), 1f)
    }

    private fun capture(name: String, output: File, record: Boolean, goldens: Set<String>) {
        compose.waitForIdle()
        val root = bounds("watch-screen")
        assertEquals("Capture must start at the window origin", 0f, root.left, 0.5f)
        val actual = compose.onNodeWithTag("watch-screen").captureToImage().asAndroidBitmap()
        assertEquals("Capture must contain the whole viewport", root.width.toInt(), actual.width)
        assertEquals("Capture must contain the whole viewport", root.height.toInt(), actual.height)
        File(output, "$name.png").outputStream().use { actual.compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (record || name !in goldens) return
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val expected = assets.open("watch/goldens/$name.png").use(BitmapFactory::decodeStream)
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
