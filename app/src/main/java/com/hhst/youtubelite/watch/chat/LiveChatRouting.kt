package com.hhst.youtubelite.watch.chat

import com.hhst.youtubelite.watch.domain.WatchVideo

/** Which chat surface the comments affordance opens for the current page. */
data class LiveChatTarget(val key: String, val replay: Boolean)

/** Archived chat does not replace the video's comments. */
fun liveChatTarget(
    video: WatchVideo?,
    playerLive: Boolean,
    commentsOpen: Boolean,
    searchOpen: Boolean,
    fullscreen: Boolean,
    pip: Boolean,
): LiveChatTarget? {
    if (!commentsOpen || searchOpen || fullscreen || pip) return null
    if (!playerLive && video?.live != true) return null
    val key = video?.chatKey?.takeIf { it.isNotBlank() } ?: return null
    return LiveChatTarget(key, replay = false)
}
