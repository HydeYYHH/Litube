package com.hhst.youtubelite.data.media

import com.hhst.youtubelite.home.domain.*
import com.hhst.youtubelite.search.domain.*
import com.hhst.youtubelite.playlist.domain.PlaylistSummary
import com.liskovsoft.youtubeapi.videoinfo.V2.MediaItem
import com.liskovsoft.youtubeapi.videoinfo.V2.HomeSection
import com.liskovsoft.youtubeapi.videoinfo.V2.loadHome
import com.liskovsoft.youtubeapi.videoinfo.V2.loadSearch
import com.liskovsoft.youtubeapi.videoinfo.V2.request

internal fun MediaItem.feedVideo() = FeedItem.Video(id, title.orEmpty(), author.orEmpty(), listOfNotNull(viewCount, published).joinToString(" · "), thumbnail.orEmpty(),
    duration.orEmpty(), channelId.orEmpty(), live, progressPercent?.div(100f), avatar = authorThumbnail.orEmpty())

internal fun MediaItem.playlistSummary() = PlaylistSummary(id.removePrefix("VL"), title.orEmpty(), author.orEmpty(),
    channelId.orEmpty(), null, thumbnail.orEmpty(), "", 0, videoCountText = videoCountText)

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
        val videos = page.items.filter { it.kind == "video" }.map(MediaItem::feedVideo)
        val shorts = page.items.filter { it.kind == "short" }.map(MediaItem::feedVideo)
        HomePage(videos + if (shorts.isEmpty()) emptyList() else listOf(FeedItem.Shorts("shorts", shorts)), page.messages, page.nextKey)
    }
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
        for (label in labels) {
            val reply = http.execute(com.liskovsoft.youtubeapi.videoinfo.V2.searchQuery(query, context, params).request())
            if (reply.status !in 200..299) throw MediaException(com.liskovsoft.youtubeapi.videoinfo.V2.RequestError(
                com.liskovsoft.youtubeapi.videoinfo.V2.RequestFailure.HTTP, "http_" + reply.status, reply.status))
            val filter = com.liskovsoft.youtubeapi.videoinfo.V2.searchFilters(reply.body).firstOrNull {
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
        duration.orEmpty(), thumbnail.orEmpty(), if (live) "LIVE" else null, progressPercent?.div(100f), avatar = authorThumbnail.orEmpty())
    return SearchPage(query, items.filter { it.kind == "video" }.map { it.result() },
        items.filter { it.kind == "playlist" }.map(MediaItem::playlistSummary),
        items.filter { it.kind == "channel" }.map { SearchChannel(it.id, it.title.orEmpty(), "", "", it.thumbnail.orEmpty()) },
        items.filter { it.kind == "short" }.map { it.result() }, nextKey)
}
