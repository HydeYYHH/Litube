package com.hhst.youtubelite.player

import com.hhst.youtubelite.player.surface.AutoFullscreen

/**
 * Sensor and manual-entry history for the player presentation; the caller supplies the
 * current window per decision and applies the result. Main-thread confined: stores no
 * UI state and never changes a window itself.
 */
internal class FullscreenState {
    private var autoEntered = false
    private var suppressAutoEnter = false
    private var orientationBand = AutoFullscreen.Band.OTHER
    private var systemAutoRotate = true
    private var inPip = false

    /** A tap or committed swipe owns fullscreen until the user leaves it. */
    fun onManualChange(fullscreen: Boolean) {
        autoEntered = false
        if (!fullscreen) suppressAutoEnter = true
    }

    /** Leaving watch/fullscreen drops auto-entry, but preserves sensor history. */
    fun clearAutoEntry() {
        autoEntered = false
    }

    fun updateOrientation(degrees: Int, systemAutoRotate: Boolean, inPip: Boolean): Boolean {
        val band = AutoFullscreen.band(degrees)
        val changed = band != orientationBand ||
            this.systemAutoRotate != systemAutoRotate || this.inPip != inPip
        this.systemAutoRotate = systemAutoRotate
        this.inPip = inPip
        orientationBand = band
        return changed
    }

    /**
     * Null leaves the current window alone. Reevaluate when watch becomes
     * visible, even if the device was already in the same orientation band.
     */
    fun evaluate(window: Window): Boolean? {
        val portrait = orientationBand == AutoFullscreen.Band.PORTRAIT
        if (portrait) suppressAutoEnter = false
        return when {
            AutoFullscreen.shouldEnter(
                watchVisible = window.visible && !window.mini,
                systemAutoRotate = systemAutoRotate,
                fullscreen = window.fullscreen,
                pip = inPip,
                mini = window.mini,
                casting = window.casting,
                locked = window.locked,
                physicalLandscape = orientationBand == AutoFullscreen.Band.LANDSCAPE,
                suppressed = suppressAutoEnter,
            ) -> {
                autoEntered = true
                true
            }
            AutoFullscreen.shouldExit(
                fullscreen = window.fullscreen,
                autoEntered = autoEntered,
                locked = window.locked,
                physicalPortrait = portrait,
            ) -> {
                autoEntered = false
                false
            }
            else -> null
        }
    }

    /** A decision input, never retained as a second copy of the current UI. */
    data class Window(
        val visible: Boolean,
        val fullscreen: Boolean,
        val mini: Boolean,
        val casting: Boolean,
        val locked: Boolean,
    )
}
