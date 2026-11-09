package com.hhst.youtubelite.player

import java.lang.ref.WeakReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object PlaybackSession {
    private val active = MutableStateFlow<PlayerViewModel?>(null)
    val player = active.asStateFlow()
    fun register(player: PlayerViewModel) { active.value = player }

    private var pipHost: WeakReference<PlaybackActivity>? = null
    private val pipActive = MutableStateFlow(false)
    val inPip = pipActive.asStateFlow()

    internal fun updatePipHost(activity: PlaybackActivity, inPip: Boolean) {
        if (inPip) {
            pipHost = WeakReference(activity)
            pipActive.value = true
        } else if (pipHost?.get() === activity) {
            pipHost = null
            pipActive.value = false
        }
    }
}
