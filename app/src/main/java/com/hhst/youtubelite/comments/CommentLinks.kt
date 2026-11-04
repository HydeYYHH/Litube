package com.hhst.youtubelite.comments

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.hhst.youtubelite.comments.domain.CommentLink
import com.hhst.youtubelite.comments.domain.safeCommentUrl
import com.hhst.youtubelite.navigation.openContentLink
import com.hhst.youtubelite.navigation.ContentLink
import com.hhst.youtubelite.player.PlayerViewModel

internal fun CommentLink.seekPosition(currentId: String?, durationMs: Long, live: Boolean): Long? {
    val video = when (this) {
        is CommentLink.Video -> this
        is CommentLink.External -> (ContentLink.parse(url) as? ContentLink.Video)?.let { CommentLink.Video(it.id, it.seconds) }
        else -> null
    } ?: return null
    if (video.id != currentId || live) return null
    val position = video.seconds?.takeIf { it >= 0 && it <= Long.MAX_VALUE / 1000 }?.times(1000) ?: return null
    return if (durationMs > 0) position.coerceAtMost(durationMs) else position
}

internal fun Context.openCommentLink(link: CommentLink, player: PlayerViewModel? = null) {
    player?.uiState?.value?.let { state ->
        link.seekPosition(state.videoId, state.durationMs, state.isLive)?.let { player.onSeek(it); return }
    }
    val url = when (link) {
        is CommentLink.Video -> if (link.id.matches(Regex("[A-Za-z0-9_-]{11}")))
            "https://www.youtube.com/watch?v=${link.id}" + (link.seconds?.takeIf { it >= 0 }?.let { "&t=$it" } ?: "") else null
        is CommentLink.Channel -> if (link.id.matches(Regex("UC[A-Za-z0-9_-]{22}"))) "https://www.youtube.com/channel/${link.id}" else null
        is CommentLink.External -> safeCommentUrl(link.url)
    } ?: return
    if (openContentLink(url)) return
    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)) }
}
