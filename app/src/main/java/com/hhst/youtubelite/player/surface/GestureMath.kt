package com.hhst.youtubelite.player.surface

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pure gesture math for the player surface (no Android deps, fully testable).
 *
 * Zone model:
 * - left 35% → brightness, right 35% → volume, center → fullscreen swipe
 * - horizontal drag anywhere → seek scrub (±120 s full-width)
 * - double-tap thirds → −10 s / play-pause / +10 s
 */
object GestureMath {

    enum class DoubleTapZone { LEFT, CENTER, RIGHT }
    enum class DragMode { NONE, SEEK, BRIGHTNESS, VOLUME }

    /** Logical gesture zones for extension-pref gating. */
    enum class GestureZone { TAP, DOUBLE_TAP, LONG_PRESS, SEEK, BRIGHTNESS, VOLUME, FULLSCREEN_SWIPE }

    private const val SEEK_FULL_RANGE_MS = 120_000L
    const val DOUBLE_TAP_SEEK_MS = 10_000L

    /** Vertical swipe enters/exits fullscreen past this fraction of the height. */
    const val FULLSCREEN_SWIPE_MIN_FRACTION = 0.08f

    /**
     * Minimal vertical velocity estimate (px/s, sign follows y-down). Only the
     * samples inside [windowMs] before the last point count, so a slow hold
     * before release cannot masquerade as a fling.
     */
    class VelocityTracker(private val windowMs: Long = 100L) {
        private val times = LongArray(16)
        private val offsets = FloatArray(16)
        private var count = 0

        fun reset() {
            count = 0
        }

        fun add(timeMs: Long, y: Float) {
            if (count == times.size) {
                val drop = count / 2
                System.arraycopy(times, drop, times, 0, count - drop)
                System.arraycopy(offsets, drop, offsets, 0, count - drop)
                count -= drop
            }
            times[count] = timeMs
            offsets[count] = y
            count++
        }

        /** Signed px/s over the trailing window; 0 when no movement is sampled. */
        fun velocityY(): Float {
            if (count < 2) return 0f
            val lastTime = times[count - 1]
            var first = count - 1
            while (first > 0 && lastTime - times[first - 1] <= windowMs) first--
            val dt = lastTime - times[first]
            if (dt <= 0L) return 0f
            return (offsets[count - 1] - offsets[first]) * 1000f / dt
        }
    }

    /**
     * Suppresses the tap that follows a zoom or long-press release. The tap
     * detector and the drag tracker are sibling pointerInput handlers and the
     * tap side sees the up event first, so a stationary hold cannot cancel it
     * by consuming events — the tap fires shortly after release. Must be
     * longer than the double-tap window the tap detector waits out. Also used
     * by the queue sheet's drag-release tap guard.
     */
    const val TAP_SUPPRESS_MS = 400L

    private const val BRIGHTNESS_ZONE_END = 0.35f
    private const val VOLUME_ZONE_START = 0.65f

    /** Scroll sensitivity: brightness 1.5, volume 1.2 (per gesture pixel). */
    private const val BRIGHTNESS_FACTOR = 1.5f
    private const val VOLUME_FACTOR = 1.2f

    fun doubleTapZone(x: Float, width: Float): DoubleTapZone = when {
        width <= 0f -> DoubleTapZone.CENTER
        x < width / 3f -> DoubleTapZone.LEFT
        x > width * 2f / 3f -> DoubleTapZone.RIGHT
        else -> DoubleTapZone.CENTER
    }

    /** Pixels → seek offset; full width maps to ±[SEEK_FULL_RANGE_MS]. */
    fun seekOffsetMs(dx: Float, width: Float): Long {
        if (width <= 0f) return 0L
        return (dx / width * SEEK_FULL_RANGE_MS).toLong()
    }

    /** Vertical drag → brightness delta in [-1, 1]; up increases. */
    fun brightnessDelta(dy: Float, height: Float): Float {
        if (height <= 0f) return 0f
        return -dy / height * BRIGHTNESS_FACTOR
    }

    /** Vertical drag → volume delta in [-1, 1]; up increases. */
    fun volumeDelta(dy: Float, height: Float): Float {
        if (height <= 0f) return 0f
        return -dy / height * VOLUME_FACTOR
    }

    /** Chooses the drag mode once the touch slop is exceeded. */
    fun resolveDragMode(
        dx: Float,
        dy: Float,
        startX: Float,
        width: Float,
        verticalFeed: Boolean = false,
    ): DragMode {
        if (abs(dx) > abs(dy)) return DragMode.SEEK
        // Shorts (and similar vertical feeds): every vertical drag switches
        // the page video; brightness/volume must not eat the swipe.
        if (verticalFeed) return DragMode.NONE
        return when {
            startX < width * BRIGHTNESS_ZONE_END -> DragMode.BRIGHTNESS
            startX > width * VOLUME_ZONE_START -> DragMode.VOLUME
            else -> DragMode.NONE
        }
    }

    /** Gesture-session level: apply [delta] without re-reading a quantized system value. */
    fun accumulateLevel(current: Float, delta: Float, min: Float, max: Float): Float =
        (current + delta).coerceIn(min, max)

    /** Overlay percent from a session level in `[0, max]`. */
    fun levelPercent(level: Float, max: Float): Int {
        if (max <= 0f) return 0
        return ((level / max) * 100f).roundToInt().coerceIn(0, 100)
    }

}
