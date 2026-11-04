package com.hhst.youtubelite.player.surface

import com.hhst.youtubelite.ui.design.GestureResistance
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Finger recognition stays raw; only the picture's response is damped. */
internal object FullscreenSwipeMotion {
    /** Entry must fit inside the embedded picture, even on a tall phone. */
    fun entryThreshold(playerHeight: Float, density: Float): Float =
        (playerHeight * .24f).coerceIn(32f * density, 56f * density)

    fun commitsEntry(distance: Float, velocity: Float, playerHeight: Float, density: Float): Boolean {
        if (velocity < -600f * density) return false
        return distance >= entryThreshold(playerHeight, density) ||
            (distance >= 28f * density && velocity >= 450f * density)
    }

    fun progress(distance: Float, travel: Float, density: Float): Float {
        val fraction = GestureResistance.distance(distance.coerceAtLeast(0f), density) / travel.coerceAtLeast(1f)
        // The initial soft take-up stays smooth, then each additional finger
        // movement produces less picture travel. Never hit a rigid edge.
        return fraction / (1f + fraction)
    }

    fun progressVelocity(distance: Float, velocity: Float, travel: Float, density: Float): Float {
        val step = density.coerceAtLeast(.01f)
        return (progress(distance + step, travel, density) - progress(distance - step, travel, density)) /
            (2f * step) * velocity
    }

    /** A player swipe magnifies only the picture, with feedback sized to its frame. */
    fun pictureZoom(distance: Float, playerHeight: Float, density: Float): Float =
        .5f * progress(distance, maxOf(playerHeight * .5f, entryThreshold(playerHeight, density) * 1.5f), density)

    fun pictureZoomVelocity(distance: Float, velocity: Float, playerHeight: Float, density: Float): Float =
        .5f * progressVelocity(distance, velocity, maxOf(playerHeight * .5f, entryThreshold(playerHeight, density) * 1.5f), density)

    /** A bounded rubber band moves the unscaled picture within the fixed player. */
    fun pictureTranslation(distance: Float, density: Float): Float {
        val extent = 120f * density
        return extent * progress(distance, extent, density)
    }

    fun pictureTranslationVelocity(distance: Float, velocity: Float, density: Float): Float {
        val step = density.coerceAtLeast(.01f)
        return (pictureTranslation(distance + step, density) - pictureTranslation(distance - step, density)) / (2f * step) * velocity
    }

    /** Keep the picture's origin through a window resize without scaling its chrome. */
    fun pictureWidth(width: Float, height: Float, aspectRatio: Float, mode: ResizeMode): Float = when (mode) {
        ResizeMode.Fit -> minOf(width, height * aspectRatio)
        ResizeMode.Zoom -> maxOf(width, height * aspectRatio)
        ResizeMode.FixedHeight -> height * aspectRatio
        ResizeMode.Fill, ResizeMode.FixedWidth -> width
    }

    fun settleDuration(remaining: Float): Int =
        (120f + 120f * sqrt(abs(remaining).coerceIn(0f, 1f))).roundToInt()

    fun settleSlope(velocity: Float, remaining: Float, durationMs: Int): Float =
        if (abs(remaining) < .0001f) 0f else (velocity / remaining * durationMs / 1000f).coerceIn(-1f, 3f)

    /** Hermite response: retain release speed, end at rest, never overshoot the destination. */
    fun settleProgress(fraction: Float, initialSlope: Float): Float {
        val t = fraction.coerceIn(0f, 1f)
        return initialSlope * t + (3f - 2f * initialSlope) * t * t + (initialSlope - 2f) * t * t * t
    }
}
