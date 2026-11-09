package com.hhst.youtubelite.share

object PlaylistShare {
    private val idPattern = Regex("PL[A-Za-z0-9_-]{14,32}")
    fun urlFor(playlistId: String): String? = playlistId.takeIf(idPattern::matches)
        ?.let { "https://www.youtube.com/playlist?list=$it" }
}
