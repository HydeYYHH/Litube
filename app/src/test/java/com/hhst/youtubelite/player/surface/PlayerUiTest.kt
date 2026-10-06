package com.hhst.youtubelite.player.surface

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerUiTest {

    @Test
    fun offsetHint_signedSeconds() {
        assertEquals("+10s", PlayerUi.offsetHint(10_000))
        assertEquals("-10s", PlayerUi.offsetHint(-10_000))
        assertEquals("+0s", PlayerUi.offsetHint(0))
        // Sub-second magnitudes round half-up on magnitude, keeping the sign.
        assertEquals("+1s", PlayerUi.offsetHint(900))
        assertEquals("-1s", PlayerUi.offsetHint(-900))
    }

    @Test
    fun speedLabel_oneDecimalForWholeSpeeds() {
        assertEquals("1.0x", PlayerUi.speedLabel(1f))
        assertEquals("2.0x", PlayerUi.speedLabel(2f))
        assertEquals("0.5x", PlayerUi.speedLabel(0.5f))
        assertEquals("1.25x", PlayerUi.speedLabel(1.25f))
    }

    @Test
    fun progressFraction_clampsAndZeroDuration() {
        assertEquals(0f, PlayerUi.progressFraction(50, 0), 0f)
        assertEquals(0.5f, PlayerUi.progressFraction(50, 100), 0f)
        assertEquals(1f, PlayerUi.progressFraction(200, 100), 0f)
        assertEquals(0f, PlayerUi.progressFraction(-10, 100), 0f)
        assertEquals(0.5f, PlayerUi.bufferedFraction(50, 100), 0f)
    }

    @Test
    fun segmentRange_skipsEmptyAndClamps() {
        assertNull(PlayerUi.segmentRange(0, 10, 0))
        assertNull(PlayerUi.segmentRange(10, 10, 100))
        assertEquals(0.1f to 0.4f, PlayerUi.segmentRange(10, 40, 100))
    }

    @Test
    fun nextBackStep_lockThenFullscreenThenBrowser() {
        assertEquals(PlayerUi.BackStep.Unlock, PlayerUi.nextBackStep(locked = true, fullscreen = true))
        assertEquals(PlayerUi.BackStep.Unlock, PlayerUi.nextBackStep(locked = true, fullscreen = false))
        assertEquals(PlayerUi.BackStep.ExitFullscreen, PlayerUi.nextBackStep(locked = false, fullscreen = true))
        // Mini-player is transparent to Back: it drives the browser underneath.
        assertEquals(PlayerUi.BackStep.BrowserBack, PlayerUi.nextBackStep(locked = false, fullscreen = false))
    }

    @Test
    fun playerTopOffset_windowSpaceIsInsetPlusReportedPageTop() {
        // The overlay lives one level above the inset-padded WebView: window
        // space = system-bar inset + the page player's viewport-relative top.
        assertEquals(80, PlayerUi.playerTopOffsetDp(fullscreen = false, reportedTopDp = 48, insetTopDp = 32))
        assertEquals(32, PlayerUi.playerTopOffsetDp(fullscreen = false, reportedTopDp = 0, insetTopDp = 32))
        // No report yet: fall back to the masthead height below the inset.
        assertEquals(80, PlayerUi.playerTopOffsetDp(fullscreen = false, reportedTopDp = null, insetTopDp = 32))
        // Scrolled past the window top clamps at the edge.
        assertEquals(0, PlayerUi.playerTopOffsetDp(fullscreen = false, reportedTopDp = -30, insetTopDp = 24))
        assertEquals(0, PlayerUi.playerTopOffsetDp(fullscreen = true, reportedTopDp = 48, insetTopDp = 32))
    }

    @Test
    fun gestureSideInset_scalesWithPlayerWidth() {
        assertEquals(24, PlayerUi.gestureSideInsetDp(0))
        assertEquals(43, PlayerUi.gestureSideInsetDp(360))
        assertEquals(96, PlayerUi.gestureSideInsetDp(800))
        assertEquals(48, PlayerUi.gestureSideInsetDp(360, insetDp = 48))
        assertEquals(96, PlayerUi.gestureSideInsetDp(800, insetDp = 40))
    }

    @Test
    fun hudSidePad_subtractsChromeInset() {
        assertEquals(43, PlayerUi.hudSidePadDp(360))
        assertEquals(80, PlayerUi.hudSidePadDp(800, chromePadDp = 16))
        assertEquals(8, PlayerUi.hudSidePadDp(360, chromePadDp = 40))
        assertEquals(32, PlayerUi.hudSidePadDp(360, chromePadDp = 16, insetDp = 48))
    }

    @Test
    fun embeddedHeight_usesReportedOr16by9() {
        assertEquals(180, PlayerUi.embeddedHeightDp(180, 320))
        assertEquals(180, PlayerUi.embeddedHeightDp(null, 320))
        assertEquals(180, PlayerUi.embeddedHeightDp(0, 320))
    }

    @Test
    fun landscapeMini_compactPhoneFallsBackButRoomyTabletStaysEmbedded() {
        assertTrue(PlayerUi.useLandscapeMiniPlayer(880, 360, 495))
        assertTrue(PlayerUi.useLandscapeMiniPlayer(880, 360, null))
        assertFalse(PlayerUi.useLandscapeMiniPlayer(1024, 840, 576))
        // A wide device still needs mini when the actual page player is tall.
        assertTrue(PlayerUi.useLandscapeMiniPlayer(1280, 800, 720))
        // A shorter slot fits even in a phone-sized landscape window.
        assertFalse(PlayerUi.useLandscapeMiniPlayer(880, 480, 220))
    }

    @Test
    fun landscapeMini_boundaryAndPortraitRestoration() {
        assertTrue(PlayerUi.useLandscapeMiniPlayer(900, 539, 300))
        assertFalse(PlayerUi.useLandscapeMiniPlayer(900, 540, 300))
        assertFalse(PlayerUi.useLandscapeMiniPlayer(360, 880, 495))
        assertFalse(PlayerUi.useLandscapeMiniPlayer(800, 800, 720))
        assertFalse(PlayerUi.useLandscapeMiniPlayer(0, 0, null))
    }

    @Test
    fun qualityButtonLabel_autoIncludesActiveTrack() {
        // Pinned label wins.
        assertEquals(
            "1080p",
            PlayerUi.qualityButtonLabel(pinned = "1080p", active = null, autoPrefix = "Auto", videoHeight = 720),
        )
        // Playlist track and decoded frame disagree: auto shows the frame.
        assertEquals(
            "Auto 1080p",
            PlayerUi.qualityButtonLabel(pinned = null, active = "720p", autoPrefix = "Auto", videoHeight = 1080),
        )
        // They agree: keep the track label, including a frame-rate suffix.
        assertEquals(
            "Auto 1080p60",
            PlayerUi.qualityButtonLabel(pinned = null, active = "1080p60", autoPrefix = "Auto", videoHeight = 1080),
        )
        // Track unknown yet: fall back to the reported video height.
        assertEquals(
            "Auto 720p",
            PlayerUi.qualityButtonLabel(pinned = null, active = null, autoPrefix = "Auto", videoHeight = 720),
        )
        assertEquals(
            "Auto",
            PlayerUi.qualityButtonLabel(pinned = null, active = null, autoPrefix = "Auto", videoHeight = 0),
        )
    }

    @Test
    fun lockVisible_fullscreenOnlyNeverInMini() {
        assertTrue(PlayerUi.lockVisible(fullscreen = true, mini = false, locked = false, controlsVisible = true))
        assertTrue(PlayerUi.lockVisible(fullscreen = true, mini = false, locked = true, controlsVisible = false))
        assertFalse(PlayerUi.lockVisible(fullscreen = false, mini = false, locked = false, controlsVisible = true))
        assertFalse(PlayerUi.lockVisible(fullscreen = true, mini = true, locked = false, controlsVisible = true))
    }

    @Test
    fun fullscreenSideDp_atLeastSixteenAndGrowsWithCutout() {
        assertEquals(16, PlayerUi.fullscreenSideDp(0))
        assertEquals(16, PlayerUi.fullscreenSideDp(8))
        assertEquals(24, PlayerUi.fullscreenSideDp(24))
        assertEquals(16, PlayerUi.fullscreenSideDp(-4))
    }

    @Test
    fun sheetMaxHeight_capsLandscapeAndPortrait() {
        assertEquals(270, PlayerUi.sheetMaxHeightDp(360))
        assertEquals(520, PlayerUi.sheetMaxHeightDp(800))
        assertEquals(200, PlayerUi.sheetMaxHeightDp(0))
    }

    @Test
    fun pipAspect_unknownFallsBackTo16by9() {
        assertEquals(16 to 9, PlayerUi.pipAspect(0, 0))
        assertEquals(16 to 9, PlayerUi.pipAspect(-1, 1080))
    }

    @Test
    fun pipAspect_clampsToAndroidPipRange() {
        val landscape = PlayerUi.pipAspect(1920, 1080)
        assertEquals(16 to 9, landscape)
        val portrait = PlayerUi.pipAspect(1080, 1920)
        val portraitRatio = portrait.first.toDouble() / portrait.second
        assertTrue(portraitRatio in (1.0 / 2.39)..2.39)
        val ultraWide = PlayerUi.pipAspect(3000, 1000)
        assertEquals(2390 to 1000, ultraWide)
        val ultraTall = PlayerUi.pipAspect(1000, 3000)
        assertEquals(1000 to 2390, ultraTall)
        val atMin = PlayerUi.pipAspect(1000, 2390)
        assertEquals(100 to 239, atMin)
        val minRatio = atMin.first.toDouble() / atMin.second
        assertTrue(minRatio >= 1.0 / 2.39 - 1e-9)
    }

    @Test
    fun languageLabel_fallsBackWhenBlank() {
        assertEquals("", PlayerUi.languageLabel(""))
        assertTrue(PlayerUi.languageLabel("en").isNotBlank())
    }
}
