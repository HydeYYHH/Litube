package com.hhst.youtubelite.data.media

import com.hhst.youtubelite.home.domain.FeedMenuEndpoint
import java.util.Base64

/**
 * TV's shared recommendation context menu (observed from signed-in Home get_panel):
 * field 175 { video_id: 1, menu_type: 4 = 158, source: 8 { type: 1 = 2, browse_id: 2 } }.
 * The recommendation source is required: omitting it returns a save/share-only menu.
 * Menu READ only — never a synthesized feedback token. Use only on recommendation feeds;
 * search, subscriptions, history and user collections must keep their own server capabilities.
 */
internal fun recommendationMenuEndpoint(videoId: String): FeedMenuEndpoint? {
    if (!videoId.matches(Regex("[A-Za-z0-9_-]{11}"))) return null
    val browseId = "FEwhat_to_watch".toByteArray(Charsets.UTF_8)
    val source = byteArrayOf(8, 2, 18, browseId.size.toByte()) + browseId
    val menu = byteArrayOf(10, 11) + videoId.toByteArray(Charsets.UTF_8) +
        byteArrayOf(32, 158.toByte(), 1, 66, source.size.toByte()) + source
    val params = byteArrayOf(250.toByte(), 10, menu.size.toByte()) + menu
    return FeedMenuEndpoint("PAcontext_menu", Base64.getUrlEncoder().encodeToString(params))
}
