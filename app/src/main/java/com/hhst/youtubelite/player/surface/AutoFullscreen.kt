package com.hhst.youtubelite.player.surface

/**
 * Auto-fullscreen rules, kept separate from the sensor listener.
 *
 * Enter: watch is visible, system auto-rotate is on, the phone is physically
 * landscape, and nothing (mini / PiP / cast / lock / a fresh manual exit)
 * is holding us in the embedded player.
 *
 * Exit: only the auto-entered session leaves on physical portrait — a tap or
 * swipe into fullscreen stays until the user leaves it.
 */
object AutoFullscreen {

    enum class Band { PORTRAIT, LANDSCAPE, OTHER }

    fun band(degrees: Int): Band = when {
        degrees < 0 -> Band.OTHER
        physicalLandscape(degrees) -> Band.LANDSCAPE
        physicalPortrait(degrees) -> Band.PORTRAIT
        else -> Band.OTHER
    }

    fun physicalLandscape(degrees: Int): Boolean {
        if (degrees < 0) return false
        val d = degrees % 360
        return d in 60..120 || d in 240..300
    }

    fun physicalPortrait(degrees: Int): Boolean {
        if (degrees < 0) return false
        val d = degrees % 360
        return d <= 30 || d >= 330 || d in 150..210
    }

    fun shouldEnter(
        watchVisible: Boolean,
        systemAutoRotate: Boolean,
        fullscreen: Boolean,
        pip: Boolean,
        mini: Boolean,
        casting: Boolean,
        locked: Boolean,
        physicalLandscape: Boolean,
        suppressed: Boolean,
    ): Boolean = watchVisible &&
        systemAutoRotate &&
        !fullscreen &&
        !pip &&
        !mini &&
        !casting &&
        !locked &&
        physicalLandscape &&
        !suppressed

    fun shouldExit(
        fullscreen: Boolean,
        autoEntered: Boolean,
        locked: Boolean,
        physicalPortrait: Boolean,
    ): Boolean = fullscreen && autoEntered && !locked && physicalPortrait
}
