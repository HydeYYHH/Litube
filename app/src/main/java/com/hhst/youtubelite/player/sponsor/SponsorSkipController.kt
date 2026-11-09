package com.hhst.youtubelite.player.sponsor

import com.hhst.youtubelite.player.sponsor.SponsorBlockManager.Segment

/**
 * Countdown-gated sponsor skipping (pure state machine).
 * Default: pre-skip card counts down; skip fires at the deadline. Cancel or
 * manual seek into a segment suppresses it for the rest of the video (manual
 * chip covers that). Countdown off: first frame inside a segment skips to end.
 * Zero-length poi_highlight markers never auto-skip; a jump chip appears
 * within [HIGHLIGHT_CHIP_WINDOW_MS] and retires the point on tap.
 * Suppression keys on [Segment] identity (start/end/category), not list index,
 * so a later refetch keeps the user's opt-out.
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

    /** True while position sits inside a segment that will not auto-skip. */
    var chipActive: Boolean = false
        private set

    /** True when [chipActive] is a highlight point (chip jumps TO it). */
    var chipIsHighlight: Boolean = false
        private set

    /** Advances state by [posMs]; returns seek target when skip should fire, else null. */
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
        // API order is not guaranteed; earliest segment in the window owns the card.
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
        // Highlight chip only when no segment chip shows; official semantics:
        // manual, never auto.
        if (highlightChip != null) {
            chipActive = true
            chipIsHighlight = true
        }
        return null
    }

    private fun segmentAt(posMs: Long, segments: List<Segment>): Segment? =
        segments.firstOrNull { posMs >= it.startMs && posMs < it.endMs }

    /** Highlight point whose chip window covers [posMs]; ±window avoids frame-perfect timing. */
    private fun highlightAt(posMs: Long, segments: List<Segment>): Segment? =
        segments.firstOrNull {
            it.endMs == it.startMs && it.endMs > 0 && it !in retiredHighlights &&
                posMs >= it.startMs - HIGHLIGHT_CHIP_WINDOW_MS &&
                posMs < it.startMs + HIGHLIGHT_CHIP_WINDOW_MS
        }

    /** Ms remaining → whole seconds, rounding up, never negative. */
    private fun remainingSeconds(ms: Long): Int =
        ((ms + 999) / 1000).toInt().coerceAtLeast(0)

    /**
     * Marks a just-skipped segment suppressed and returns its end. Without this
     * a keyframe/cue snap-back before [Segment.endMs] re-enters and re-arms the
     * skip (seek storm or repeated countdown). The chip covers the suppressed case.
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

    /** Manual chip target: highlight point, or segment end at [posMs]. */
    fun chipTarget(posMs: Long, segments: List<Segment>): Long? =
        if (chipIsHighlight) highlightChip?.startMs else segmentAt(posMs, segments)?.endMs

    /** Highlight chip tapped: retire that point for the rest of the video. */
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
