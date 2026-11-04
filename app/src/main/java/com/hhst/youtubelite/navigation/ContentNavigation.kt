package com.hhst.youtubelite.navigation

import android.content.Context
import android.content.Intent
import com.hhst.youtubelite.watch.WatchActivity
import com.hhst.youtubelite.channel.ChannelActivity
import com.hhst.youtubelite.playlist.PlaylistActivity
import com.hhst.youtubelite.search.SearchResultsActivity
import com.hhst.youtubelite.shorts.ShortsActivity
import com.hhst.youtubelite.post.PostDetailActivity
import com.hhst.youtubelite.library.*
import com.hhst.youtubelite.home.HomeActivity
import com.hhst.youtubelite.subscriptions.SubscriptionsActivity

fun ContentLink.intent(context: Context): Intent = when (this) {
    is ContentLink.Video -> WatchActivity.intent(context, id).apply {
        // Only a present extra is an explicit start: writing 0 would turn an
        // unspecified open into "begin at 0".
        seconds?.let { putExtra(WatchActivity.EXTRA_START_SECONDS, it) }
    }
    is ContentLink.Short -> ShortsActivity.intent(context, id)
    is ContentLink.Playlist -> PlaylistActivity.intent(context, id)
    is ContentLink.Channel -> ChannelActivity.intent(context, id)
    is ContentLink.Post -> PostDetailActivity.intent(context, "", id)
    is ContentLink.Search -> SearchResultsActivity.intent(context, query)
    is ContentLink.Feed -> Intent(context, when (name) {
        "subscriptions" -> SubscriptionsActivity::class.java
        "history" -> HistoryActivity::class.java
        "playlists" -> PlaylistsActivity::class.java
        "you", "library" -> YouActivity::class.java
        else -> HomeActivity::class.java
    })
}

fun Context.openContentLink(url: String): Boolean {
    val link = ContentLink.parse(url) ?: return false
    startActivity(link.intent(this))
    return true
}
