@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hhst.youtubelite.player.surface

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import com.hhst.youtubelite.R
import com.hhst.youtubelite.extractor.Chapter
import com.hhst.youtubelite.player.PlayerUiState
import com.hhst.youtubelite.player.sponsor.SponsorBlockManager
import com.hhst.youtubelite.player.sponsor.SponsorSkipController
import com.hhst.youtubelite.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The chapter pill, countdown and subtitles anchor to the measured track
 * line, not to a hardcoded bar height; these checks read real coordinates.
 */
class ChapterEntryAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<PlayerTestActivity>()

    private val chapters = listOf(
        Chapter("Intro", 0),
        Chapter("Welcome", 3600),
        Chapter("Outro", 7200),
    )

    private fun surface(
        state: PlayerUiState,
        cues: List<String> = emptyList(),
        onSeek: (Long) -> Unit = {},
    ) {
        val callbacks = proxyActions("ChapterEntryActions") { name, args ->
            if (name == "onSeek") onSeek(args!![0] as Long)
        }
        compose.setContent {
            AppTheme(darkTheme = true, dynamicColor = false) {
                Box(Modifier.fillMaxSize().background(Color.DarkGray), contentAlignment = Alignment.Center) {
                    PlayerSurface(
                        state, remember { mutableStateOf(4_272_000L) },
                        remember { mutableStateOf(4_272_000L) },
                        remember { mutableStateOf(null) }, remember { mutableStateOf(null) },
                        remember { mutableStateOf(cues) },
                        callbacks, {}, {}, managedByHost = true,
                        modifier = Modifier.size(411.dp, 231.dp).testTag("control-frame"),
                    )
                }
            }
        }
    }

    private fun trackLineY(): Float {
        val bar = compose.onNodeWithContentDescription(compose.activity.getString(R.string.player_time_bar))
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        // The semantics node covers the full 48 dp hit canvas; the visible
        // track is drawn at the canvas middle.
        return (bar.top + bar.bottom) / 2f
    }

    @Test fun subtitlesKeepASmallGapAboveTheVisibleTrack() {
        surface(PlayerUiState(visible = true, prepared = true, firstFrameRendered = true,
            controlsVisible = true, durationMs = 8_544_000L), cues = listOf("Readable subtitle"))
        val density = compose.activity.resources.displayMetrics.density
        val subtitle = compose.onNodeWithText("Readable subtitle").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val gap = (trackLineY() - subtitle.bottom) / density
        assertTrue("Caption clearance must remain small: $gap dp", gap in 4f..9f)
    }

    @Test fun chapterChipSitsInTheInfoBandAndSkipCardAnchorsAboveTheTrack() {
        surface(
            PlayerUiState(
                visible = true, durationMs = 8_544_000L, chapters = chapters,
                sponsorCountdownSec = 3, controlsVisible = true,
            ),
            cues = listOf("Readable subtitle"),
        )
        compose.waitForIdle()
        val density = compose.activity.resources.displayMetrics.density
        val track = trackLineY()
        val chip = compose.onNodeWithTag("player-chapter-entry").assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        // Current chapter at 4_272 s is "Welcome" (starts 3_600 s).
        compose.onNodeWithText("Welcome").assertIsDisplayed()
        assertTrue("Chapter chip sits below the track", chip.top >= track - 1)
        val skipTouch = compose.onNodeWithTag("player-sponsor-countdown")
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals("Countdown bottom sits 8 dp above the track", track - 8 * density, skipTouch.bottom, 1.5f)
        val frame = compose.onNodeWithTag("control-frame").fetchSemanticsNode().boundsInRoot
        assertEquals("Countdown stays near the right edge", frame.right - 12 * density, skipTouch.right, 1.5f)
        assertTrue("Skip card must not cover the info band", skipTouch.bottom <= chip.top + 1)
        val skipVisual = compose.onNodeWithTag("player-sponsor-visual", useUnmergedTree = true)
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val subtitle = compose.onNodeWithText("Readable subtitle").assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        // Captions clear the drawn card, not its invisible 48 dp touch box.
        assertTrue("Subtitles must clear the skip card's visual bounds",
            subtitle.bottom <= skipVisual.top + 1)
        assertTrue("Subtitles must clear the info band", subtitle.bottom <= chip.top + 1)
    }

    @Test fun pillOpensSheetAndRowSeeksAndCloses() {
        var sought = -1L
        surface(
            PlayerUiState(visible = true, durationMs = 8_544_000L, chapters = chapters),
            onSeek = { sought = it },
        )
        compose.waitForIdle()
        compose.onNodeWithTag("player-chapter-entry").performTouchInput { click() }
        compose.waitForIdle()
        compose.onNodeWithText(compose.activity.getString(R.string.chapters)).assertIsDisplayed()
        compose.onNodeWithTag("chapters-sheet-list").assertIsDisplayed()
        compose.onNodeWithTag("chapter-row-7200").assertIsDisplayed().performClick()
        compose.waitForIdle()
        assertEquals(7_200_000L, sought)
        compose.onNodeWithTag("chapters-sheet-list").assertDoesNotExist()
    }

    @Test fun segmentsAreListedSeparatelyFromChapters() {
        var sought = -1L
        surface(
            PlayerUiState(
                visible = true, durationMs = 8_544_000L, chapters = chapters,
                sponsorSegments = listOf(SponsorBlockManager.Segment(5_000L, 9_000L, "sponsor")),
            ),
            onSeek = { sought = it },
        )
        compose.waitForIdle()
        compose.onNodeWithTag("player-chapter-entry").performTouchInput { click() }
        compose.waitForIdle()
        compose.onNodeWithText(compose.activity.getString(R.string.skippable_segments)).assertIsDisplayed()
        compose.onNodeWithTag("segment-row-5000").assertIsDisplayed()
        compose.onNodeWithText("0:05 – 0:09").assertIsDisplayed()
        compose.onNodeWithTag("segment-row-5000").performClick()
        assertEquals(9_000L, sought)
    }

    @Test fun chaptersShowArtworkAndTheirFullRangeAndSeekToTheStart() {
        var sought = -1L
        compose.setContent { AppTheme {
            Column {
                ChaptersSheet(listOf(Chapter("Intro", 0, "asset://sample_bunny.jpg"), Chapter("Main", 10)),
                    emptyList(), remember { mutableStateOf(0L) }, null,
                    onSeek = { time, _ -> sought = time }, onDismiss = {}, durationMs = 30_000)
            }
        } }
        compose.onNodeWithTag("chapter-artwork-0", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("0:00 – 0:10").assertIsDisplayed()
        compose.onNodeWithText("0:10 – 0:30").assertIsDisplayed()
        compose.onNodeWithTag("chapter-row-10").performClick()
        assertEquals(10_000L, sought)
    }

    @Test fun entryHiddenWithoutDataWhenLockedOrControlsHidden() {
        var state by mutableStateOf(
            PlayerUiState(visible = true, durationMs = 8_544_000L, chapters = chapters, sponsorCountdownSec = 3)
        )
        compose.setContent {
            AppTheme(darkTheme = true, dynamicColor = false) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    PlayerSurface(
                        state, remember { mutableStateOf(4_272_000L) },
                        remember { mutableStateOf(4_272_000L) },
                        remember { mutableStateOf(null) }, remember { mutableStateOf(null) },
                        remember { mutableStateOf(emptyList()) },
                        proxyActions("ChapterHiddenActions"), {}, {}, managedByHost = true,
                        modifier = Modifier.size(411.dp, 231.dp).testTag("control-frame"),
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("player-chapter-entry").assertIsDisplayed()
        compose.runOnIdle { state = state.copy(locked = true) }
        compose.waitForIdle()
        compose.onNodeWithTag("player-chapter-entry").assertDoesNotExist()
        compose.onNodeWithTag("player-sponsor-countdown").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(locked = false, controlsVisible = false) }
        compose.waitForIdle()
        val frame = compose.onNodeWithTag("control-frame").fetchSemanticsNode().boundsInRoot
        val chip = compose.onNodeWithTag("player-sponsor-countdown")
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val density = compose.activity.resources.displayMetrics.density
        assertEquals("Hidden chrome: chip keeps a 12 dp edge margin", frame.bottom - 12 * density, chip.bottom, 1.5f)
        compose.onNodeWithTag("player-chapter-entry").assertDoesNotExist()
        // No chapters and no segments: the entry disappears instead of dead-ending.
        compose.runOnIdle {
            state = state.copy(controlsVisible = true, chapters = emptyList(), sponsorCountdownSec = null)
        }
        compose.waitForIdle()
        compose.onNodeWithTag("player-chapter-entry").assertDoesNotExist()
    }

    @Test fun countdownTapCancelsAndManualJumpChipStaysHidden() {
        var cancelled = false
        var skipped = false
        val segments = listOf(SponsorBlockManager.Segment(10_000L, 20_000L, "sponsor"))
        val controller = SponsorSkipController()
        controller.tick(7_000L, segments, countdownEnabled = true)
        var state by mutableStateOf(PlayerUiState(
            visible = true, durationMs = 30_000L, sponsorCountdownSec = controller.countdownSec,
            sponsorChip = true, sponsorChipHighlight = true,
        ))
        compose.setContent {
            AppTheme(darkTheme = true, dynamicColor = false) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    PlayerSurface(
                        state, remember { mutableStateOf(7_000L) },
                        remember { mutableStateOf(7_000L) },
                        remember { mutableStateOf(null) }, remember { mutableStateOf(null) },
                        remember { mutableStateOf(emptyList()) },
                        proxyActions("SponsorCountdownActions") { name, _ ->
                            when (name) {
                                "onSponsorSkipCancel" -> {
                                    cancelled = true
                                    controller.cancelPending()
                                    state = state.copy(sponsorCountdownSec = controller.countdownSec)
                                }
                                "onSponsorSkipNow", "onSponsorChipSkip" -> skipped = true
                            }
                        }, {}, {}, managedByHost = true,
                        modifier = Modifier.size(411.dp, 231.dp),
                    )
                }
            }
        }
        compose.onNodeWithTag("player-sponsor-chip").assertDoesNotExist()
        for (seconds in 3 downTo 1) {
            compose.runOnIdle {
                assertEquals(null, controller.tick((10 - seconds) * 1_000L, segments, countdownEnabled = true))
                state = state.copy(sponsorCountdownSec = controller.countdownSec)
            }
            compose.onNodeWithText(compose.activity.getString(R.string.sponsor_skip_in, seconds)).assertIsDisplayed()
        }
        compose.onNodeWithText(compose.activity.getString(R.string.cancel), useUnmergedTree = true)
            .assertIsDisplayed().performTouchInput { click() }
        compose.runOnIdle {
            assertTrue("Tapping the countdown cancels the jump", cancelled)
            assertTrue("Tapping the countdown must not seek", !skipped)
            assertEquals(null, controller.tick(10_000L, segments, countdownEnabled = true))
            assertEquals(null, controller.tick(19_000L, segments, countdownEnabled = true))
            assertEquals(null, controller.countdownSec)
        }
        compose.onNodeWithTag("player-sponsor-countdown").assertDoesNotExist()
        compose.onNodeWithTag("player-sponsor-chip").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(sponsorChipHighlight = false) }
        compose.onNodeWithTag("player-sponsor-chip").assertDoesNotExist()
    }

    @Test fun chapterRowsStayCompactAndExpandForLargerText() {
        val fontScale = mutableStateOf(1f)
        val title = "A longer chapter title for a compact player sheet"
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale.value)) {
                AppTheme(darkTheme = true, dynamicColor = false) {
                    Column(Modifier.size(390.dp, 600.dp)) {
                        ChaptersSheet(
                            chapters = listOf(Chapter(title, 0)),
                            segments = emptyList(),
                            positionState = remember { mutableStateOf(0L) },
                            fallbackThumb = null,
                            onSeek = { _, _ -> },
                            onDismiss = {},
                        )
                    }
                }
            }
        }
        val density = compose.activity.resources.displayMetrics.density
        val row = compose.onNodeWithTag("chapter-row-0").assertIsDisplayed()
        val compact = row.fetchSemanticsNode().boundsInRoot
        assertTrue("A standard row fits within 90 dp", compact.height <= 90 * density)
        compose.runOnIdle { fontScale.value = 2f }
        val enlarged = row.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val text = compose.onNodeWithText(title).fetchSemanticsNode().boundsInRoot
        assertTrue("Large text remains inside the row", text.top >= enlarged.top && text.bottom <= enlarged.bottom)
        assertTrue("The row grows with text", enlarged.height > compact.height)
    }
}
