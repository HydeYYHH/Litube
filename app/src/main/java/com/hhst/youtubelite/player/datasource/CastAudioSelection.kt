package com.hhst.youtubelite.player.datasource

import com.hhst.youtubelite.extractor.Format

/**
 * Chromecast audio pick: keep the current identity when an AAC rendition of
 * it exists; otherwise any AAC (default receivers cannot decode Opus). Only
 * fall back to a non-AAC format when the pool has no AAC at all.
 */
object CastAudioSelection {

    fun select(
        audioOnly: List<Format>,
        preferredKey: String?,
        isAac: (Format) -> Boolean,
    ): Format? {
        val dash = audioOnly.filter { it.hasDashRanges }
        if (dash.isEmpty()) return null
        val matching = if (preferredKey.isNullOrBlank()) {
            emptyList()
        } else {
            dash.filter { AudioTrackIdentity.matches(it, preferredKey) }
        }
        val aacMatching = matching.filter(isAac)
        val aacAll = dash.filter(isAac)
        return aacMatching.maxByOrNull { it.bitrate }
            ?: aacAll.maxByOrNull { it.bitrate }
            ?: matching.maxByOrNull { it.bitrate }
            ?: dash.maxByOrNull { it.bitrate }
    }
}
