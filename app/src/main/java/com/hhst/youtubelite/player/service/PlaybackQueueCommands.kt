package com.hhst.youtubelite.player.service

import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/**
 * Media3 1.10 single-item players do not advertise next/previous. The app
 * queue must add those commands when it can navigate, and remove them when
 * it cannot — not only trim a wrapped player that already claimed them.
 */
@UnstableApi
object PlaybackQueueCommands {

    private val NEXT = intArrayOf(
        Player.COMMAND_SEEK_TO_NEXT,
        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
    )
    private val PREVIOUS = intArrayOf(
        Player.COMMAND_SEEK_TO_PREVIOUS,
        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
    )

    fun apply(base: Player.Commands, hasNext: Boolean, hasPrevious: Boolean): Player.Commands {
        val builder = base.buildUpon()
        applyDir(builder, hasNext, NEXT)
        applyDir(builder, hasPrevious, PREVIOUS)
        return builder.build()
    }

    private fun applyDir(builder: Player.Commands.Builder, enable: Boolean, commands: IntArray) {
        if (enable) {
            commands.forEach { builder.add(it) }
        } else {
            commands.forEach { builder.remove(it) }
        }
    }
}
