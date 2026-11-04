package com.hhst.youtubelite.watch.chat

import com.hhst.youtubelite.watch.domain.WatchVideo

data class LiveChatTarget(val key: String, val replay: Boolean, val messageParams: String? = null)

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
    return LiveChatTarget(key, replay = false,
        messageParams = com.liskovsoft.youtubeapi.videoinfo.V2.chatMessageParameters(video.id, video.channelId))
}
