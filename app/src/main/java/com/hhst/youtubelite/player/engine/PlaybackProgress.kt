package com.hhst.youtubelite.player.engine

/** Resume-progress write rules and share-link start clamping. */
internal object PlaybackProgress {
    const val SAFE_ZONE_MS = 5_000L

    enum class Write { SKIP, SAVE, CLEAR }

    /**
     * Near start: skip (replay from 0). Mid-roll: save. Near/at end or
     * [playbackEnded]: clear so a finished video cannot resume mid-roll.
     */
    fun write(positionMs: Long, durationMs: Long, playbackEnded: Boolean): Write {
        if (playbackEnded) return Write.CLEAR
        if (durationMs > 0 && positionMs >= durationMs - SAFE_ZONE_MS) return Write.CLEAR
        if (durationMs > 0 && positionMs > SAFE_ZONE_MS && positionMs < durationMs - SAFE_ZONE_MS) {
            return Write.SAVE
        }
        return Write.SKIP
    }

    fun clampStartMs(startMs: Long, durationMs: Long): Long {
        if (startMs <= 0L) return 0L
        if (durationMs <= 0L) return startMs
        return startMs.coerceAtMost((durationMs - 1L).coerceAtLeast(0L))
    }

    /** Explicit share `t=` (including 0) beats [rememberedMs]; applied once per load. */
    fun resolveStartMs(explicitStartMs: Long?, rememberedMs: Long, durationMs: Long): Long {
        val raw = explicitStartMs ?: rememberedMs
        return clampStartMs(raw, durationMs)
    }
}
