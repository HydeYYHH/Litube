package com.hhst.youtubelite.player.sponsor

import com.hhst.youtubelite.player.sponsor.SponsorBlockManager.Segment

/**
 * Countdown-gated sponsor skipping (pure state machine, unit-testable).
 *
 * Rules:
 * - Default (countdown on): a pre-skip card counts down while the position
 *   approaches a segment (or for [countdownMs] after first finding the
 *   position already inside one); the skip fires at the deadline. Cancelling
 *   — or seeking into a segment by hand — suppresses that segment for the
 *   rest of the video; a small manual "skip" chip covers the suppressed case.
 * - Countdown off: legacy behavior — the first frame inside a segment skips
 *   to its end immediately.
 * - Zero-length segments (SponsorBlock's poi_highlight point markers) never
 *   auto-skip: while the playhead is within [HIGHLIGHT_CHIP_WINDOW_MS] of the
 *   point, a "jump" chip appears; tapping seeks to the point and retires it
 *   for the video.
 *
 * Suppression is keyed on [Segment] identity (start/end/category), not list
 * index, so a later refetch of the same segments keeps the user's opt-out.
 */
class SponsorSkipController(
    private val countdownMs: Long = DEFAULT_COUNTDOWN_MS,
) {
    private val suppressed = HashSet<Segment>()
    private val retiredHighlights = HashSet<Segment>()
    private var pending: Segment? = null
    private var countdownDeadlineMs: Long = -1L
    private var highlightChip: Segment? = null

    /** Whole seconds left on the pre-skip countdown; null hides the card. */
    var countdownSec: Int? = null
        private set

    /** True while the position sits inside a segment that will not auto-skip. */
    var chipActive: Boolean = false
        private set

    /** True when [chipActive] is driven by a highlight point (chip jumps TO it). */
    var chipIsHighlight: Boolean = false
        private set

    /**
     * Advances the state by [posMs]. Returns the seek target when the skip
     * should fire now, else null.
     */
    fun tick(posMs: Long, segments: List<Segment>, countdownEnabled: Boolean): Long? {
        val skippable = segments.filter { it.endMs > it.startMs }
        countdownSec = null
        chipActive = false
        chipIsHighlight = false
        highlightChip = highlightAt(posMs, segments)
        val inside = segmentAt(posMs, skippable)
        if (inside != null && inside in suppressed) {
            chipActive = true
            pending = null
            return null
        }
        if (inside != null) {
            if (!countdownEnabled) {
                pending = null
                return skipFired(inside)
            }
            if (pending != inside) {
                pending = inside
                countdownDeadlineMs = posMs + countdownMs
            }
            if (posMs >= countdownDeadlineMs) {
                pending = null
                return skipFired(inside)
            }
            countdownSec = remainingSeconds(countdownDeadlineMs - posMs)
            return null
        }
        // API order is not guaranteed; the earliest segment in the window
        // owns the countdown card.
        val upcoming = skippable
            .filter { posMs < it.startMs && posMs >= it.startMs - countdownMs }
            .minByOrNull { it.startMs }
        if (upcoming != null && upcoming !in suppressed && countdownEnabled) {
            if (pending != upcoming) {
                pending = upcoming
                countdownDeadlineMs = upcoming.startMs
            }
            countdownSec = remainingSeconds(upcoming.startMs - posMs)
        } else if (pending != null && pending != upcoming) {
            pending = null
        }
        // Highlight chip only when no segment chip shows (skip logic above
        // owns the surface then); official semantics: manual, never auto.
        if (highlightChip != null) {
            chipActive = true
            chipIsHighlight = true
        }
        return null
    }

    /** Segment whose countdown/skip logic owns [posMs]. */
    private fun segmentAt(posMs: Long, segments: List<Segment>): Segment? =
        segments.firstOrNull { posMs >= it.startMs && posMs < it.endMs }

    /**
     * The highlight point whose chip window covers [posMs]. Zero-length
     * segments are point markers; ±window keeps the chip reachable without
     * frame-perfect timing.
     */
    private fun highlightAt(posMs: Long, segments: List<Segment>): Segment? =
        segments.firstOrNull {
            it.endMs == it.startMs && it.endMs > 0 && it !in retiredHighlights &&
                posMs >= it.startMs - HIGHLIGHT_CHIP_WINDOW_MS &&
                posMs < it.startMs + HIGHLIGHT_CHIP_WINDOW_MS
        }

    /** Milliseconds remaining → whole seconds, rounding up, never negative. */
    private fun remainingSeconds(ms: Long): Int =
        ((ms + 999) / 1000).toInt().coerceAtLeast(0)

    /**
     * Marks a just-skipped segment suppressed and returns its end. Without
     * this a keyframe/cue snap-back that lands before [Segment.endMs] would
     * re-enter the segment on the next tick and re-arm the skip — a seek
     * storm (countdown off) or a repeated countdown (countdown on). The chip
     * covers the suppressed case, so the user can still skip again manually.
     */
    private fun skipFired(segment: Segment): Long {
        suppressed += segment
        return segment.endMs
    }

    /** User pressed "cancel" on the pre-skip card: suppress that segment. */
    fun cancelPending() {
        pending?.let { suppressed += it }
        pending = null
        countdownSec = null
    }

    fun skipPending(): Long? {
        val target = pending?.endMs
        pending?.let { suppressed += it }
        pending = null
        countdownSec = null
        return target
    }

    /** A deliberate seek: landing inside a segment opts out of skipping it. */
    fun onUserSeek(posMs: Long, segments: List<Segment>) {
        segmentAt(posMs, segments)?.let { suppressed += it }
        pending = null
        countdownSec = null
    }

    /** Target for the manual chip: the highlight point, or the segment end at [posMs]. */
    fun chipTarget(posMs: Long, segments: List<Segment>): Long? =
        if (chipIsHighlight) highlightChip?.startMs else segmentAt(posMs, segments)?.endMs

    /** The highlight chip was tapped: retire that point for the rest of the video. */
    fun dismissHighlightChip() {
        highlightChip?.let { retiredHighlights += it }
        highlightChip = null
        chipActive = false
        chipIsHighlight = false
    }

    /** New video: drop suppression state from the previous one. */
    fun reset() {
        suppressed.clear()
        retiredHighlights.clear()
        pending = null
        highlightChip = null
        countdownDeadlineMs = -1L
        countdownSec = null
        chipActive = false
        chipIsHighlight = false
    }

    companion object {
        private const val DEFAULT_COUNTDOWN_MS = 5_000L

        /** Highlight chip visibility window around the point marker. */
        private const val HIGHLIGHT_CHIP_WINDOW_MS = 15_000L
    }
}
