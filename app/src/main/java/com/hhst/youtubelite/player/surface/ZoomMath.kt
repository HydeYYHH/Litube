package com.hhst.youtubelite.player.surface

import kotlin.math.abs

/**
 * Fullscreen pinch-zoom math (no Android deps).
 *
 * Pinch-zoom math: scale 1×–5×, pan only while zoomed,
 * clamp so the video cannot leave the crop.
 */
object ZoomMath {
    const val MIN_SCALE = 1f
    const val MAX_SCALE = 5f
    const val ZOOMED_SCALE = 1.01f
    const val TRANSLATION_EPS = 5f

    fun clampScale(scale: Float): Float = scale.coerceIn(MIN_SCALE, MAX_SCALE)

    /** Pan limit so scaled content still covers the view. */
    fun clampTranslation(value: Float, viewSize: Float, scale: Float): Float {
        val limit = (viewSize * scale - viewSize) / 2f
        if (limit <= 0f) return 0f
        return value.coerceIn(-limit, limit)
    }

    fun isZoomed(scale: Float, translationX: Float, translationY: Float): Boolean =
        scale > ZOOMED_SCALE ||
            abs(translationX) > TRANSLATION_EPS ||
            abs(translationY) > TRANSLATION_EPS
}
