package com.hhst.youtubelite.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FullscreenStateTest {
    @Test fun orientationDeduplicationIncludesSystemRotationAndPipChanges() {
        val state = FullscreenState()
        assertFalse(state.updateOrientation(-1, systemAutoRotate = true, inPip = false))
        assertTrue(state.updateOrientation(90, systemAutoRotate = true, inPip = false))
        assertFalse(state.updateOrientation(95, systemAutoRotate = true, inPip = false))
        assertTrue(state.updateOrientation(95, systemAutoRotate = false, inPip = false))
        assertNull(state.evaluate(window()))
        assertTrue(state.updateOrientation(95, systemAutoRotate = true, inPip = true))
        assertNull(state.evaluate(window()))
        assertTrue(state.updateOrientation(95, systemAutoRotate = true, inPip = false))
        assertEquals(true, state.evaluate(window()))
    }

    @Test fun portraitExitsAutoEnteredFullscreenButKeepsManualFullscreen() {
        val state = FullscreenState()
        state.updateOrientation(90, systemAutoRotate = true, inPip = false)
        assertEquals(true, state.evaluate(window()))
        state.updateOrientation(0, systemAutoRotate = true, inPip = false)
        assertEquals(false, state.evaluate(window(fullscreen = true)))

        state.onManualChange(fullscreen = true)
        assertNull(state.evaluate(window(fullscreen = true)))
    }

    @Test fun manualExitSuppressesLandscapeUntilPortraitHasBeenObserved() {
        val state = FullscreenState()
        state.updateOrientation(90, systemAutoRotate = true, inPip = false)
        assertEquals(true, state.evaluate(window()))
        state.onManualChange(fullscreen = false)
        assertNull(state.evaluate(window()))
        state.updateOrientation(45, systemAutoRotate = true, inPip = false)
        assertNull(state.evaluate(window()))
        state.updateOrientation(270, systemAutoRotate = true, inPip = false)
        assertNull(state.evaluate(window()))
        state.updateOrientation(0, systemAutoRotate = true, inPip = false)
        assertNull(state.evaluate(window()))
        state.updateOrientation(90, systemAutoRotate = true, inPip = false)
        assertEquals(true, state.evaluate(window()))
    }

    @Test fun becomingVisibleReusesTheLastSensorBandWithoutAnotherSensorChange() {
        val state = FullscreenState()
        state.updateOrientation(90, systemAutoRotate = true, inPip = false)
        assertNull(state.evaluate(window(visible = false)))
        assertFalse(state.updateOrientation(95, systemAutoRotate = true, inPip = false))
        assertEquals(true, state.evaluate(window()))
    }

    @Test fun leavingPresentationClearsAutoEntryWithoutResettingSensorHistory() {
        val state = FullscreenState()
        state.updateOrientation(90, systemAutoRotate = true, inPip = false)
        assertEquals(true, state.evaluate(window()))
        state.clearAutoEntry()
        assertNull(state.evaluate(window(mini = true)))
        // Returning from mini in the same landscape band can enter again.
        assertEquals(true, state.evaluate(window()))
        state.clearAutoEntry()
        state.updateOrientation(0, systemAutoRotate = true, inPip = false)
        assertNull(state.evaluate(window(fullscreen = true)))
    }

    @Test fun leavingPresentationDoesNotUndoTheManualExitSuppression() {
        val state = FullscreenState()
        state.updateOrientation(90, systemAutoRotate = true, inPip = false)
        state.onManualChange(fullscreen = false)
        state.clearAutoEntry()
        assertNull(state.evaluate(window()))
    }

    @Test fun portraitWaitsForTheLockToReleaseBeforeLeavingAutoFullscreen() {
        val state = FullscreenState()
        state.updateOrientation(90, systemAutoRotate = true, inPip = false)
        assertEquals(true, state.evaluate(window()))
        state.updateOrientation(0, systemAutoRotate = true, inPip = false)
        assertNull(state.evaluate(window(fullscreen = true, locked = true)))
        assertEquals(false, state.evaluate(window(fullscreen = true)))
    }

    private fun window(
        visible: Boolean = true,
        fullscreen: Boolean = false,
        mini: Boolean = false,
        locked: Boolean = false,
    ) = FullscreenState.Window(visible, fullscreen, mini, casting = false, locked = locked)
}
