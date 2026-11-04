package com.hhst.youtubelite.ui.design

import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.sqrt

/** Finger distances stay unmodified for recognition; only presentation is resisted. */
internal object GestureResistance {
    fun distance(distance: Float, density: Float): Float {
        val travel = (distance - 12f * density).coerceAtLeast(0f)
        return if (travel == 0f) 0f else .85f * travel * travel / (travel + 24f * density)
    }

    fun fingerDistance(offset: Float, density: Float): Float {
        if (offset <= 0f) return 0f
        val scaled = offset / .85f
        return 12f * density + (scaled + sqrt(scaled * scaled + 96f * density * scaled)) / 2f
    }

    fun fullscreenThreshold(height: Float, density: Float): Float =
        (height * .18f).coerceIn(80f * density, 144f * density)

    /** Speed alone never turns a small accidental flick into a mode change. */
    fun commits(distance: Float, velocity: Float, threshold: Float, density: Float): Boolean {
        if (velocity < -900f * density) return false
        return distance >= threshold ||
            (distance >= maxOf(48f * density, threshold * .55f) && velocity >= 900f * density)
    }

    /** Free travel inside the screen, progressively heavier travel beyond either wall. */
    fun beyondBounds(value: Float, min: Float, max: Float, extent: Float): Float {
        val edge = value.coerceIn(min, max)
        val excess = value - edge
        return edge + sign(excess) * extent * abs(excess) / (extent + abs(excess))
    }
}

/** One threshold pulse per touch, even when the finger jitters around the boundary. */
internal class GestureThreshold {
    private var signalled = false
    fun reset() { signalled = false }
    fun crossed(distance: Float, threshold: Float): Boolean {
        if (signalled || distance < threshold) return false
        signalled = true
        return true
    }
}
