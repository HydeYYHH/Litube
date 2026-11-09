package com.hhst.youtubelite.watch.domain

import com.hhst.youtubelite.channel.domain.ChannelVideo
import com.hhst.youtubelite.home.domain.FeedItem
import com.hhst.youtubelite.library.domain.HistoryEntry
import com.hhst.youtubelite.playlist.domain.PlaylistEntry
import com.hhst.youtubelite.search.domain.SearchResult

/**
 * Presentation-only summary a tapped card hands to Watch: fields the source list
 * already resolved. No tokens/account state/counts the card never showed; unknown
 * slots load independently with the page read.
 */
data class WatchProvisional(
    val videoId: String,
    val title: String = "",
    val channel: String = "",
    val channelId: String = "",
    val avatar: String? = null,
    val artwork: String = "",
    val views: String = "",
    val age: String = "",
    val duration: String = "",
    val live: Boolean = false,
    val progress: Float? = null,
)

/**
 * Bounded in-process handoff of card summaries. Entries are consumed on read so a
 * later navigation without a card starts clean, and the map stays bounded when a
 * session walks many cards without opening them.
 */
object WatchProvisionalStore {
    private const val LIMIT = 24
    private val entries = object : LinkedHashMap<String, WatchProvisional>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, WatchProvisional>) = size > LIMIT
    }

    fun put(provisional: WatchProvisional) {
        synchronized(entries) { entries[provisional.videoId] = provisional }
    }

    fun consume(videoId: String): WatchProvisional? = synchronized(entries) { entries.remove(videoId) }
}

fun FeedItem.Video.toProvisional() = WatchProvisional(
    videoId = id, title = title, channel = author, channelId = channelId, avatar = avatar,
    artwork = artwork, views = metadata, age = age.orEmpty(), duration = duration,
    live = live, progress = progress,
)

fun FeedItem.Mix.toProvisional() = WatchProvisional(
    videoId = id, title = title, channel = authors, artwork = artwork,
)

fun SearchResult.toProvisional() = WatchProvisional(
    videoId = id, title = title, channel = channel, channelId = channelId, avatar = avatar,
    artwork = artwork, views = views, age = age, duration = duration, progress = progress,
)

fun ChannelVideo.toProvisional(channel: String, channelId: String, avatar: String?) = WatchProvisional(
    videoId = id, title = title, channel = channel, channelId = channelId, avatar = avatar,
    artwork = artwork, views = views, age = age, duration = duration, live = live,
)

fun PlaylistEntry.toProvisional() = WatchProvisional(
    videoId = videoId, title = title, channel = channel, channelId = channelId,
    artwork = artwork, views = views, age = age, duration = duration,
)

fun HistoryEntry.toProvisional() = WatchProvisional(
    videoId = videoId, title = title, channel = author, channelId = channelId,
    artwork = artwork, views = views, duration = duration, progress = progress,
)

fun WatchRelated.toProvisional() = WatchProvisional(
    videoId = id, title = title, channel = channel, channelId = channelId, avatar = avatar,
    artwork = artwork, views = views, age = age, duration = duration, progress = progress,
)
