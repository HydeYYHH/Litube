package com.hhst.youtubelite.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object PlaybackSession {
    private val active = MutableStateFlow<PlayerViewModel?>(null)
    val player = active.asStateFlow()
    fun register(player: PlayerViewModel) { active.value = player }
}
