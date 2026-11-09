package com.hhst.youtubelite.player

/**
 * Shared TTL for persisted player prefs; the engine and view model must
 * agree so a pref never outlives the other's writes.
 */
const val PREF_TTL_MS: Long = 3650L * 24 * 60 * 60 * 1000
