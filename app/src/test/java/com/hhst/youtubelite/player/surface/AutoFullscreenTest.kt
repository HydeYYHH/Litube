package com.hhst.youtubelite.player.surface

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoFullscreenTest {

    @Test
    fun band_classifiesPortraitLandscapeAndDeadZone() {
        assertEquals(AutoFullscreen.Band.PORTRAIT, AutoFullscreen.band(0))
        assertEquals(AutoFullscreen.Band.PORTRAIT, AutoFullscreen.band(15))
        assertEquals(AutoFullscreen.Band.PORTRAIT, AutoFullscreen.band(180))
        assertEquals(AutoFullscreen.Band.LANDSCAPE, AutoFullscreen.band(90))
        assertEquals(AutoFullscreen.Band.LANDSCAPE, AutoFullscreen.band(270))
        assertEquals(AutoFullscreen.Band.OTHER, AutoFullscreen.band(45))
        assertEquals(AutoFullscreen.Band.OTHER, AutoFullscreen.band(-1))
    }

    @Test
    fun shouldEnter_requiresWatchLandscapeAndAutoRotate() {
        assertTrue(enter())
        assertFalse(enter(watchVisible = false))
        assertFalse(enter(systemAutoRotate = false))
        assertFalse(enter(fullscreen = true))
        assertFalse(enter(pip = true))
        assertFalse(enter(mini = true))
        assertFalse(enter(casting = true))
        assertFalse(enter(locked = true))
        assertFalse(enter(physicalLandscape = false))
        assertFalse(enter(suppressed = true))
    }

    @Test
    fun shouldExit_onlyAutoEnteredUnlockedPortrait() {
        assertTrue(AutoFullscreen.shouldExit(true, autoEntered = true, locked = false, physicalPortrait = true))
        assertFalse(AutoFullscreen.shouldExit(true, autoEntered = false, locked = false, physicalPortrait = true))
        assertFalse(AutoFullscreen.shouldExit(true, autoEntered = true, locked = true, physicalPortrait = true))
        assertFalse(AutoFullscreen.shouldExit(false, autoEntered = true, locked = false, physicalPortrait = true))
        assertFalse(AutoFullscreen.shouldExit(true, autoEntered = true, locked = false, physicalPortrait = false))
    }

    private fun enter(
        watchVisible: Boolean = true,
        systemAutoRotate: Boolean = true,
        fullscreen: Boolean = false,
        pip: Boolean = false,
        mini: Boolean = false,
        casting: Boolean = false,
        locked: Boolean = false,
        physicalLandscape: Boolean = true,
        suppressed: Boolean = false,
    ) = AutoFullscreen.shouldEnter(
        watchVisible, systemAutoRotate, fullscreen, pip, mini, casting, locked,
        physicalLandscape, suppressed,
    )
}
