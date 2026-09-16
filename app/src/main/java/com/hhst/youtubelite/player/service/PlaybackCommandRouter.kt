package com.hhst.youtubelite.player.service

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

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    fun playPause() = post { it.onPlayPause() }
    fun play() = post { it.onPlay() }
    fun pause() = post { it.onPause() }
    fun next() = post { it.onNext() }
    fun previous() = post { it.onPrevious() }

    private fun post(block: (Handler) -> Unit) {
        val h = handler ?: return
        main.post { block(h) }
    }

    /** Navigation handler contract (implemented by PlayerViewModel). */
    interface Handler {
        fun onPlayPause()
        fun onPlay()
        fun onPause()
        fun onNext()
        fun onPrevious()
    }
}
