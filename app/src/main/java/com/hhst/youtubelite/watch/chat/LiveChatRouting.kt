package com.hhst.youtubelite.watch.chat

import com.hhst.youtubelite.watch.domain.WatchVideo

/** Which chat surface the comments affordance opens for the current page. */
data class LiveChatTarget(val key: String, val replay: Boolean)

/**
 * One routing decision for the three page kinds: a stream that is live now
 * opens the live continuation, an ended stream opens the same panel in replay
 * mode, and a normal video (or a panel that is not visible) opens nothing so
 * the comments panel owns the tap. `replay` is driven by the player's live
 * state, not by the watch card, because `isLiveContent` stays true forever.
 */
fun liveChatTarget(
    video: WatchVideo?,
    playerLive: Boolean,
    commentsOpen: Boolean,
    searchOpen: Boolean,
    fullscreen: Boolean,
    pip: Boolean,
): LiveChatTarget? {
    if (!commentsOpen || searchOpen || fullscreen || pip) return null
    val key = video?.chatKey?.takeIf { it.isNotBlank() } ?: return null
    return LiveChatTarget(key, replay = !playerLive)
}
