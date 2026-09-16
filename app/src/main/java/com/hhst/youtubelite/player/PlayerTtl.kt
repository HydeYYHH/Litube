package com.hhst.youtubelite.player

/**
 * Shared TTL for persisted player preferences (speed, quality, loop mode,
 * subtitles, resize mode). The engine and the view model must agree on one
 * value so a pref never outlives the other's writes.
 */
const val PREF_TTL_MS: Long = 3650L * 24 * 60 * 60 * 1000
