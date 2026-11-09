package com.hhst.youtubelite.player.datasource

/**
 * Video foreground playback currently owns. Speculative warming for that same video stands
 * down: on a slow link a background prefetch competes with the player for the same bytes and
 * the first frame pays. Warming other targets is unaffected; playback still joins existing work.
 */
internal object PlaybackClaim {
    @Volatile
    var videoId: String? = null

    /** True when foreground playback owns [candidate], so warming must stop competing. */
    fun blocks(candidate: String?): Boolean {
        val owned = videoId ?: return false
        return candidate == null || candidate == owned
    }
}
