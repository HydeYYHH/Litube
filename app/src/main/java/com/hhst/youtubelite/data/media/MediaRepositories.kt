package com.hhst.youtubelite.data.media

import com.hhst.youtubelite.watch.domain.*
import com.hhst.youtubelite.playlist.domain.*
import com.hhst.youtubelite.channel.domain.*
import com.hhst.youtubelite.post.domain.PostSummary
import com.liskovsoft.youtubeapi.videoinfo.V2.*
import com.liskovsoft.youtubeapi.videoinfo.V2.WatchPage as CoreWatchPage
import com.hhst.youtubelite.watch.domain.WatchPage as AppWatchPage
import com.hhst.youtubelite.playlist.domain.PlaylistPage as AppPlaylistPage
import com.hhst.youtubelite.channel.domain.ChannelPage as AppChannelPage

class MediaWatchRepository(private val client: MediaClient) : WatchRepository {
    override suspend fun watch(videoId: String): AppWatchPage = client.read { context, http ->
        val page = loadWatch(videoId, context, http = http).requireValue()
        val query = playerQuery(PlayerClient.WEB, videoId, context.visitor)
        val reply = http.execute(query.request())
        val details = if (reply.status == 200) playerAnswer(reply.body).details else null
        if (page.title.isNullOrBlank() && details?.title.isNullOrBlank())
            throw MediaException(RequestError(RequestFailure.PARSE, "missing_video_details"))
        AppWatchPage(WatchVideo(videoId, details?.title ?: page.title.orEmpty(),
            details?.author ?: page.author.orEmpty(), "", details?.channelId ?: page.channelId.orEmpty(),
            details?.thumbnail.orEmpty(), "", details?.viewCount?.let { "$it views" }.orEmpty(), "", "",
            WatchComment("", "", ""), details?.description ?: page.description.orEmpty(), details?.liveContent == true,
            rating = when (page.rating) { "LIKE" -> VideoRating.Like; "DISLIKE" -> VideoRating.Dislike; else -> VideoRating.None },
            subscribed = page.subscribed == true, avatar = page.authorThumbnail.orEmpty()),
            page.related.filter { it.kind == "video" }.map { WatchRelated(it.id, it.title.orEmpty(), it.author.orEmpty(),
                it.channelId.orEmpty(), it.viewCount.orEmpty(), it.published.orEmpty(), it.duration.orEmpty(), it.thumbnail.orEmpty(),
                if (it.live) "LIVE" else null, it.progressPercent?.div(100f), it.authorThumbnail.orEmpty()) })
    }
}

class MediaPlaylistRepository(private val client: MediaClient) : PlaylistRepository {
    override suspend fun playlist(id: String): AppPlaylistPage? = client.read { context, http ->
        val first = loadPlaylist(id, context, http = http).requireValue()
        if (first.title == null && first.videos.isEmpty()) return@read null
        val videos = first.videos.toMutableList()
        var next = first.nextKey
        val seen = mutableSetOf<String>()
        while (next != null) {
            if (!seen.add(next) || videos.size >= 1000) throw MediaException(RequestError(RequestFailure.UNSUPPORTED, "playlist_limit"))
            val page = loadPlaylist(id, context, next, http).requireValue()
            videos.addAll(page.videos)
            next = page.nextKey
        }
        val entries = videos.mapIndexed { index, it -> PlaylistEntry(it.setVideoId ?: "${it.videoId}:$index", it.videoId,
            it.title.orEmpty(), it.author.orEmpty(), "", it.thumbnail.orEmpty(), it.length.orEmpty(), "", "") }
        AppPlaylistPage(PlaylistSummary(id, first.title.orEmpty(), first.owner.orEmpty(), "", entries.size,
            entries.firstOrNull()?.artwork.orEmpty(), "", 0), "", entries)
    }
}

class MediaChannelRepository(private val client: MediaClient, private val posts: com.hhst.youtubelite.post.domain.PostRepository) : ChannelRepository {
    override suspend fun channel(channelId: String): AppChannelPage = client.read { context, http ->
        val resolved = resolveChannel(channelId, context, http)
        val page = loadChannel(resolved, context, http = http).requireValue()
        val playlistsTab = page.page.tabs.firstOrNull { it.title.equals("Playlists", true) }
        val playlists = if (playlistsTab?.params == null) page.page.items.filter { it.kind == "playlist" } else
            loadChannel(resolved, context, playlistsTab.params, http = http).requireValue().page.items.filter { it.kind == "playlist" }
        AppChannelPage(ChannelProfile(resolved, page.title.orEmpty(), "", page.thumbnail.orEmpty(), null,
            page.subscriberCount.orEmpty(), page.description.orEmpty(), canonicalUrl = "https://www.youtube.com/channel/$resolved"),
            page.page.items.filter { it.kind == "video" }.map { ChannelVideo(it.id, it.title.orEmpty(), it.viewCount.orEmpty(), it.published.orEmpty(),
                it.duration.orEmpty(), it.thumbnail.orEmpty(), it.live) }, playlists.map(MediaItem::playlistSummary))
    }

    private suspend fun tab(channelId: String, label: String): List<MediaItem> = client.read { context, http ->
        val resolved = resolveChannel(channelId, context, http)
        val first = loadChannel(resolved, context, http = http).requireValue()
        val tab = first.page.tabs.firstOrNull { it.title.equals(label, true) } ?: return@read emptyList()
        if (tab.selected) first.page.items else loadChannel(resolved, context, tab.params, http = http).requireValue().page.items
    }
    override suspend fun shorts(channelId: String) = tab(channelId, "Shorts").filter { it.kind in setOf("video", "short") }
        .mapIndexed { index, it -> ChannelShort(it.id, it.title.orEmpty(), it.viewCount.orEmpty(), it.thumbnail.orEmpty(), index) }
    override suspend fun live(channelId: String): ChannelLivePage {
        val videos = tab(channelId, "Live").filter { it.kind == "video" }.mapIndexed { index, it ->
            ChannelLiveVideo(it.id, it.title.orEmpty(), it.viewCount.orEmpty(), it.published.orEmpty(), it.duration.orEmpty(), it.thumbnail.orEmpty(), it.live, index)
        }
        return ChannelLivePage(videos.filter { it.liveNow }, videos.filterNot { it.liveNow })
    }
    override suspend fun posts(channelId: String): List<PostSummary> = posts.channelPosts(channelId)
}

class MediaVoteRepository(private val client: MediaClient) : VoteCountRepository {
    override suspend fun counts(videoId: String): VoteCounts = client.read { _, http ->
        val votes = loadDislikes(videoId, http).requireValue()
        VoteCounts(votes.likes?.coerceAtLeast(0), votes.dislikes?.coerceAtLeast(0))
    }
}

internal fun resolveChannel(id: String, context: RequestContext, http: HttpClient): String {
    if (!id.startsWith("@")) return id
    val template = channelQuery("UC", context)
    val body = com.google.gson.JsonParser.parseString(template.body).asJsonObject
    body.remove("browseId")
    body.addProperty("url", "https://www.youtube.com/" + id)
    val reply = http.execute(template.copy(url = "https://www.youtube.com/youtubei/v1/navigation/resolve_url", body = body.toString()).request())
    if (reply.status !in 200..299) throw MediaException(RequestError(RequestFailure.HTTP, "http_" + reply.status, reply.status))
    return com.google.gson.JsonParser.parseString(reply.body).objects("browseEndpoint").firstNotNullOfOrNull { it.string("browseId") }
        ?: throw MediaException(RequestError(RequestFailure.PARSE, "channel_not_found"))
}
