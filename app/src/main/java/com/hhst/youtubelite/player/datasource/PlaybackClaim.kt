package com.hhst.youtubelite.player.datasource

/**
 * The video foreground playback currently owns. Speculative warming for that same video stands
 * down: on a slow link a background prefetch that keeps reading competes with the player for the
 * same bytes, and the first frame pays for it. Warming other targets is unaffected, and the
 * extraction result stays claimable, so playback still joins the work already done.
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
