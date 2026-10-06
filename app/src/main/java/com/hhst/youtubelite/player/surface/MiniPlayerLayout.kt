package com.hhst.youtubelite.player.surface

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Mini-player dimensions and snap math.
 */
object MiniPlayerLayout {

    const val NO_WIDTH_OVERRIDE_DP = -1
    const val CORNER_DP = 16
    const val STROKE_COLOR = 0x12FFFFFF
    const val SCRIM_COLOR = 0x66000000
    private const val COMPACT_BREAKPOINT_DP = 600
    private const val COMPACT_MIN_WIDTH_DP = 190
    private const val COMPACT_MAX_WIDTH_DP = 320
    private const val LARGE_MIN_WIDTH_DP = 240
    private const val LARGE_MAX_WIDTH_DP = 420
    const val OUTER_MARGIN_DP = 12
    private const val MIN_BOTTOM_DOCK_DP = 56
    /** Gap between mini-player transport controls. */
    const val CONTROL_GAP_DP = 18
    const val CONTROL_SIDE_DP = 30
    const val CONTROL_PLAY_DP = 34
    const val TRANSITION_MS = 260
    /** Duration of the close-target exit animation. */
    const val DISMISS_MS = 240
    const val POPUP_MAX_WIDTH_RATIO = 0.8f

    data class Spec(
        val widthDp: Int,
        val heightDp: Int,
        val rightMarginDp: Int,
        val bottomMarginDp: Int,
    )

    fun minWidthDpForScreen(screenWidthDp: Int): Int =
        if (isCompactScreen(screenWidthDp)) COMPACT_MIN_WIDTH_DP else LARGE_MIN_WIDTH_DP

    fun clampWidthDp(screenWidthDp: Int, widthDp: Int): Int =
        if (isCompactScreen(screenWidthDp)) {
            widthDp.coerceIn(COMPACT_MIN_WIDTH_DP, COMPACT_MAX_WIDTH_DP)
        } else {
            widthDp.coerceIn(LARGE_MIN_WIDTH_DP, LARGE_MAX_WIDTH_DP)
        }

    fun computeHeightDp(widthDp: Int): Int = widthDp * 9 / 16

    /** First-enter width. */
    fun defaultWidthDp(screenWidthDp: Int): Int = minWidthDpForScreen(screenWidthDp)

    /**
     * Scales the gap between prev/play (or play/next) so control centers stay
     * proportional when the mini-player is resized.
     */
    fun computeGapByRatio(
        widthDp: Int,
        referenceWidthDp: Int,
        referenceGapDp: Int = CONTROL_GAP_DP,
        leftControlWidthDp: Int = CONTROL_SIDE_DP,
        rightControlWidthDp: Int = CONTROL_PLAY_DP,
    ): Int {
        if (referenceWidthDp <= 0) return referenceGapDp.coerceAtLeast(0)
        val referenceCenterDistanceDp =
            leftControlWidthDp / 2f + referenceGapDp + rightControlWidthDp / 2f
        val targetCenterDistanceDp = referenceCenterDistanceDp * widthDp / referenceWidthDp
        val computedGapDp = targetCenterDistanceDp -
            leftControlWidthDp / 2f -
            rightControlWidthDp / 2f
        return computedGapDp.roundToInt().coerceAtLeast(0)
    }

    /** Scaled control gap; both sides share one value because the math is symmetric. */
    fun controlGap(widthDp: Int, screenWidthDp: Int): Int =
        computeGapByRatio(widthDp, minWidthDpForScreen(screenWidthDp))

    /** ListPopupWindow width: wrap content, cap at [POPUP_MAX_WIDTH_RATIO] of the screen. */
    fun popupWidthPx(contentPx: Int, screenPx: Int): Int {
        if (contentPx <= 0) return 0
        val cap = (screenPx * POPUP_MAX_WIDTH_RATIO).toInt()
        return minOf(contentPx, cap)
    }

    fun computeBottomMarginDp(outerMarginDp: Int, bottomInsetDp: Int): Int =
        outerMarginDp + maxOf(bottomInsetDp, MIN_BOTTOM_DOCK_DP)

    fun computeSpec(screenWidthDp: Int, bottomInsetDp: Int, widthOverrideDp: Int): Spec {
        val widthDp = if (widthOverrideDp == NO_WIDTH_OVERRIDE_DP) {
            defaultWidthDp(screenWidthDp)
        } else {
            clampWidthDp(screenWidthDp, widthOverrideDp)
        }
        return Spec(
            widthDp = widthDp,
            heightDp = computeHeightDp(widthDp),
            rightMarginDp = OUTER_MARGIN_DP,
            bottomMarginDp = computeBottomMarginDp(OUTER_MARGIN_DP, bottomInsetDp),
        )
    }

    fun clampTranslation(
        translationPx: Float,
        layoutStartPx: Int,
        viewSizePx: Int,
        parentSizePx: Int,
    ): Float {
        val minTranslation = -layoutStartPx.toFloat()
        val maxTranslation = maxOf(minTranslation, (parentSizePx - viewSizePx - layoutStartPx).toFloat())
        return translationPx.coerceIn(minTranslation, maxTranslation)
    }

    fun snapX(
        translationPx: Float,
        layoutStartPx: Int,
        viewSizePx: Int,
        parentSizePx: Int,
    ): Float {
        val minTranslation = -layoutStartPx.toFloat()
        val maxTranslation = maxOf(minTranslation, (parentSizePx - viewSizePx - layoutStartPx).toFloat())
        val clamped = clampTranslation(translationPx, layoutStartPx, viewSizePx, parentSizePx)
        return if (abs(clamped - minTranslation) <= abs(maxTranslation - clamped)) {
            minTranslation
        } else {
            maxTranslation
        }
    }

    fun hitsCloseTarget(centerX: Float, centerY: Float, left: Float, top: Float, right: Float, bottom: Float): Boolean =
        right > left && bottom > top && centerX >= left && centerX <= right && centerY >= top && centerY <= bottom


    private fun isCompactScreen(screenWidthDp: Int): Boolean = screenWidthDp < COMPACT_BREAKPOINT_DP
}
