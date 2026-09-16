package com.hhst.youtubelite.player.engine

import androidx.media3.common.Player

/**
 * Bridge between the engine and the playback notification service. The engine
 * owns playback truth; the service renders it as a MediaStyle notification +
 * MediaSession for background/headset control.
 */
interface PlaybackNotificationController {
    /** Start (or refresh) the foreground notification for the loaded item. */
    fun onLoaded(player: Player, title: String, author: String?, thumbnailUrl: String?)

    /** Playing/paused flip; refreshes the play-pause action. */
    fun onPlayingChanged(isPlaying: Boolean)

    /** Playback stopped/hidden — remove the notification and stop the service. */
    fun onStopped()

    /** Queue nav availability for prev/next notification actions. */
    fun onQueueNavigation(hasNext: Boolean, hasPrevious: Boolean)
}
