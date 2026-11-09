package com.hhst.youtubelite.player.datasource

import com.hhst.youtubelite.extractor.Format

/**
 * Menu-merge rules for the on-demand audio-catalog supplement. A selected source sometimes
 * exposes fewer named tracks than the service provides; a bounded catalog read on menu open
 * discovers the rest without touching running media.
 */
object AudioCatalogSupplement {

    fun extraChoices(knownKeys: Set<String>, discovered: List<Format>): List<AudioTrackChoice> =
        AudioTrackIdentity.choices(discovered).filter { it.key !in knownKeys }

    fun needsSupplement(currentFormats: List<Format>, supplementFormats: List<Format>?, trackKey: String?): Boolean {
        if (trackKey.isNullOrBlank() || supplementFormats == null) return false
        if (AudioTrackIdentity.matchingFormats(currentFormats, trackKey).isNotEmpty()) return false
        return AudioTrackIdentity.matchingFormats(supplementFormats, trackKey).isNotEmpty()
    }
}
