package com.hhst.youtubelite.player.service

import android.os.Handler
import android.os.Looper

/**
 * Decouples [PlaybackService] (notification buttons, MediaSession media keys)
 * from the ViewModel that owns queue and cast semantics. The host app
 * registers a handler at startup; notification intents and session commands
 * call into it on the main thread. Play/pause also route here so a headset
 * key drives the cast receiver while casting instead of the paused local
 * player.
 */
object PlaybackCommandRouter {
    @Volatile
    var handler: Handler? = null

    private val main = Handler(Looper.getMainLooper())

    fun playPause(isCurrent: () -> Boolean = { true }) = post(isCurrent) { it.onPlayPause() }
    fun play(isCurrent: () -> Boolean = { true }) = post(isCurrent) { it.onPlay() }
    fun pause(isCurrent: () -> Boolean = { true }) = post(isCurrent) { it.onPause() }
    fun stop(isCurrent: () -> Boolean = { true }) = post(isCurrent) { it.onStop() }
    fun next(isCurrent: () -> Boolean = { true }) = post(isCurrent) { it.onNext() }
    fun previous(isCurrent: () -> Boolean = { true }) = post(isCurrent) { it.onPrevious() }

    private fun post(isCurrent: () -> Boolean, block: (Handler) -> Unit) {
        val h = handler ?: return
        if (!isCurrent()) return
        main.post { if (handler === h && isCurrent()) block(h) }
    }

    interface Handler {
        fun onPlayPause()
        fun onPlay()
        fun onPause()
        fun onStop()
        fun onNext()
        fun onPrevious()
    }
}
