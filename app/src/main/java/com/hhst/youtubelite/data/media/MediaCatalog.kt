package com.hhst.youtubelite.data.media

import com.hhst.youtubelite.home.domain.*
import com.hhst.youtubelite.search.domain.*
import com.hhst.youtubelite.playlist.domain.PlaylistSummary
import com.liskovsoft.youtubeapi.videoinfo.V2.MediaItem
import com.liskovsoft.youtubeapi.videoinfo.V2.HomeSection
import com.liskovsoft.youtubeapi.videoinfo.V2.loadHome
import com.liskovsoft.youtubeapi.videoinfo.V2.loadSearch

// Metadata joins drop blank parts so a missing half cannot leave an orphaned separator;
// the avatar is null (not "") when the response did not carry one, which is the signal
// the shared avatar directory enriches from.
internal fun MediaItem.feedVideo() = FeedItem.Video(id, title.orEmpty(), author.orEmpty(),
    listOfNotNull(viewCount, published).filter(String::isNotBlank).joinToString(" · "), thumbnail.orEmpty(),
    duration.orEmpty(), channelId.orEmpty(), live, progressPercent?.div(100f), avatar = authorThumbnail?.takeIf(String::isNotBlank))

internal fun MediaItem.playlistSummary() = PlaylistSummary(id.removePrefix("VL"), title.orEmpty(), author.orEmpty(),
    channelId.orEmpty(), null, thumbnail.orEmpty(), "", null, videoCountText = videoCountText)

class MediaHomeRepository(private val client: MediaClient) : HomeRepository {
    override val filters = listOf("All", "Music", "Gaming", "Live", "News", "Sports")
    override suspend fun loadHome(filter: String, continuation: String?): HomePage = client.read { context, http ->
        val section = when (filter) {
            "All" -> HomeSection.HOME
            "Music" -> HomeSection.MUSIC
            "Live" -> HomeSection.LIVE
            "Gaming" -> HomeSection.GAMING
            "News" -> HomeSection.NEWS
            "Sports" -> HomeSection.SPORTS
            else -> throw MediaException(com.liskovsoft.youtubeapi.videoinfo.V2.RequestError(
                com.liskovsoft.youtubeapi.videoinfo.V2.RequestFailure.UNSUPPORTED, "filter_unavailable"))
        }
        val page = loadHome(section, context, continuation, http).requireValue()
        if (!context.signedIn) MediaSession.rememberVisitor(page.visitor, context.generation)
        HomePage(homeFeed(page.items), page.messages, page.nextKey)
    }
}

/**
 * Server order, shelf by shelf: consecutive shorts fold into one inline rail identified by
 * its first video, so a rail keeps its identity across refreshes and appended rails cannot
 * collide with a fixed id. Mix and Post containers appear only when the parse produced one,
 * which the home document never does — none are fabricated to fill the layout.
 */
internal fun homeFeed(items: List<MediaItem>): List<FeedItem> {
    val feed = ArrayList<FeedItem>()
    var rail = ArrayList<FeedItem.Video>()
    fun flushRail() {
        if (rail.isNotEmpty()) {
            feed += FeedItem.Shorts("shorts-" + rail.first().id, rail.toList())
            rail = ArrayList()
        }
    }
    for (item in items) when (item.kind) {
        "short" -> rail += item.feedVideo()
        "video" -> {
            flushRail()
            feed += item.feedVideo()
        }
        else -> Unit
    }
    flushRail()
    return feed
}

class MediaSearchRepository(private val client: MediaClient) : SearchRepository {
    override suspend fun search(query: String, filters: SearchFilters): SearchPage = client.read { context, http ->
        var params: String? = null
        var page = loadSearch(query, context, http = http).requireValue()
        val labels = listOfNotNull(
            filters.uploaded.takeUnless { it == SearchUploadDate.Anytime }?.let { when (it) {
                SearchUploadDate.Today -> "Today"
                SearchUploadDate.ThisWeek -> "This week"
                SearchUploadDate.ThisMonth -> "This month"
                SearchUploadDate.ThisYear -> "This year"
                else -> null
            } },
            filters.type.takeUnless { it == SearchResultType.All }?.let { when (it) {
                SearchResultType.Channels -> "Channel"
                SearchResultType.Playlists -> "Playlist"
                SearchResultType.Shorts -> "Shorts"
                else -> null
            } },
        )
        // Each response carries the filter panel for its own state: the next label's
        // cumulative params come from the page we already have, not from a refetch.
        for (label in labels) {
            val filter = page.filters.firstOrNull {
                it.title.equals(label, true) || it.title.equals(label + "s", true)
            } ?: throw MediaException(com.liskovsoft.youtubeapi.videoinfo.V2.RequestError(
                com.liskovsoft.youtubeapi.videoinfo.V2.RequestFailure.UNSUPPORTED, "filter_unavailable"))
            params = filter.params
            page = loadSearch(query, context, params, http = http).requireValue()
        }
        page.toSearchPage(query)
    }

    override suspend fun next(query: String, filters: SearchFilters, cursor: String): SearchPage = client.read { context, http ->
        loadSearch(query, context, continuation = cursor, http = http).requireValue().toSearchPage(query)
    }
}

private fun com.liskovsoft.youtubeapi.videoinfo.V2.SearchPage.toSearchPage(query: String): SearchPage {
    fun MediaItem.result() = SearchResult(id, title.orEmpty(), author.orEmpty(), channelId.orEmpty(), viewCount.orEmpty(), published.orEmpty(),
        duration.orEmpty(), thumbnail.orEmpty(), if (live) "LIVE" else null, progressPercent?.div(100f), avatar = authorThumbnail?.takeIf(String::isNotBlank))
    return SearchPage(query, items.filter { it.kind == "video" }.map { it.result() },
        items.filter { it.kind == "playlist" }.map(MediaItem::playlistSummary),
        items.filter { it.kind == "channel" }.map { SearchChannel(it.id, it.title.orEmpty(), "", "", it.thumbnail.orEmpty()) },
        items.filter { it.kind == "short" }.map { it.result() }, nextKey)
}
