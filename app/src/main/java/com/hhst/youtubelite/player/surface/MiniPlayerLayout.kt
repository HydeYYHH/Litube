package com.hhst.youtubelite.player.surface

import kotlin.math.abs
import kotlin.math.roundToInt

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
    private const val MIN_OUTER_MARGIN_DP = 12
    private const val MAX_OUTER_MARGIN_DP = 24
    private const val MIN_BOTTOM_DOCK_DP = 56
    private const val CONTROL_GAP_DP = 18
    const val CONTROL_SIDE_DP = 30
    const val CONTROL_PLAY_DP = 34
    const val TRANSITION_MS = 260
    const val DISMISS_MS = 240
    const val COLLAPSED_PEEK_DP = 24
    const val COLLAPSE_THRESHOLD_DP = 48

    enum class CollapsedSide { LEFT, RIGHT }

    /** Only a deliberate horizontal drag beyond the wall folds the window. */
    fun collapseSide(leftPx: Float, widthPx: Float, parentWidthPx: Int,
        dx: Float, dy: Float, thresholdPx: Float): CollapsedSide? = when {
        parentWidthPx <= 0 || abs(dx) < thresholdPx || abs(dx) <= abs(dy) * 1.3f -> null
        leftPx < -thresholdPx -> CollapsedSide.LEFT
        leftPx + widthPx > parentWidthPx + thresholdPx -> CollapsedSide.RIGHT
        else -> null
    }

    fun collapsedTranslationX(side: CollapsedSide, layoutStartPx: Float,
        widthPx: Float, parentWidthPx: Int, peekPx: Float): Float =
        (if (side == CollapsedSide.LEFT) peekPx.coerceAtMost(widthPx) - widthPx
        else parentWidthPx - peekPx.coerceAtMost(widthPx)) - layoutStartPx
    private const val POPUP_MAX_WIDTH_RATIO = 0.8f

    data class Spec(
        val widthDp: Int,
        val heightDp: Int,
        val rightMarginDp: Int,
        val bottomMarginDp: Int,
    )

    private fun minWidthDpForScreen(screenWidthDp: Int): Int =
        if (isCompactScreen(screenWidthDp)) COMPACT_MIN_WIDTH_DP else LARGE_MIN_WIDTH_DP

    fun clampWidthDp(screenWidthDp: Int, widthDp: Int): Int {
        val screenMax = (screenWidthDp - 2 * outerMarginDp(screenWidthDp)).coerceAtLeast(1)
        val maxWidth = minOf(screenMax,
            if (isCompactScreen(screenWidthDp)) COMPACT_MAX_WIDTH_DP else LARGE_MAX_WIDTH_DP)
        return widthDp.coerceIn(minOf(minWidthDpForScreen(screenWidthDp), maxWidth), maxWidth)
    }

    private fun computeHeightDp(widthDp: Int): Int = widthDp * 9 / 16

    fun defaultWidthDp(screenWidthDp: Int): Int = clampWidthDp(screenWidthDp, minWidthDpForScreen(screenWidthDp))

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

    fun popupWidthPx(contentPx: Int, screenPx: Int): Int {
        if (contentPx <= 0) return 0
        val cap = (screenPx * POPUP_MAX_WIDTH_RATIO).toInt()
        return minOf(contentPx, cap)
    }

    private fun computeBottomMarginDp(outerMarginDp: Int, bottomInsetDp: Int): Int =
        outerMarginDp + maxOf(bottomInsetDp, MIN_BOTTOM_DOCK_DP)

    /** Proportional breathing room, bounded for small phones and wide windows. */
    fun outerMarginDp(screenWidthDp: Int): Int =
        (screenWidthDp / 30f).roundToInt().coerceIn(MIN_OUTER_MARGIN_DP, MAX_OUTER_MARGIN_DP)

    fun computeSpec(screenWidthDp: Int, bottomInsetDp: Int, widthOverrideDp: Int): Spec {
        val widthDp = if (widthOverrideDp == NO_WIDTH_OVERRIDE_DP) {
            defaultWidthDp(screenWidthDp)
        } else {
            clampWidthDp(screenWidthDp, widthOverrideDp)
        }
        val outerMarginDp = outerMarginDp(screenWidthDp)
        return Spec(
            widthDp = widthDp,
            heightDp = computeHeightDp(widthDp),
            rightMarginDp = outerMarginDp,
            bottomMarginDp = computeBottomMarginDp(outerMarginDp, bottomInsetDp),
        )
    }

    fun clampTranslation(
        translationPx: Float,
        layoutStartPx: Int,
        viewSizePx: Int,
        parentSizePx: Int,
        startInsetPx: Float = 0f,
        endInsetPx: Float = startInsetPx,
    ): Float {
        // Reduce the insets together when a resized window leaves too little room.
        val available = (parentSizePx - viewSizePx).coerceAtLeast(0).toFloat()
        val totalInset = startInsetPx + endInsetPx
        val insetScale = if (totalInset > available) available / totalInset else 1f
        val minTranslation = startInsetPx * insetScale - layoutStartPx
        val maxTranslation = maxOf(minTranslation, available - endInsetPx * insetScale - layoutStartPx)
        return translationPx.coerceIn(minTranslation, maxTranslation)
    }

    fun snapX(
        translationPx: Float,
        layoutStartPx: Int,
        viewSizePx: Int,
        parentSizePx: Int,
        edgeMarginPx: Float = 0f,
    ): Float {
        val minTranslation = clampTranslation(Float.NEGATIVE_INFINITY, layoutStartPx, viewSizePx, parentSizePx, edgeMarginPx)
        val maxTranslation = clampTranslation(Float.POSITIVE_INFINITY, layoutStartPx, viewSizePx, parentSizePx, edgeMarginPx)
        val clamped = translationPx.coerceIn(minTranslation, maxTranslation)
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
