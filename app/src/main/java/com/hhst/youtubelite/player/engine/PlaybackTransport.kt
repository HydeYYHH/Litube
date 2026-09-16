package com.hhst.youtubelite.player.engine

/**
 * Notification / MediaSession "playing" presentation. Buffering while the
 * user still wants playback must look like an active session (pause action,
 * ongoing notification), not like a user pause.
 */
object PlaybackTransport {
    fun showAsPlaying(userWantsPlay: Boolean, isPlaying: Boolean): Boolean =
        userWantsPlay || isPlaying
}
