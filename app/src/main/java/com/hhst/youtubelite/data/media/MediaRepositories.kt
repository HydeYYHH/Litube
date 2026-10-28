package com.hhst.youtubelite.data.media

import com.hhst.youtubelite.watch.domain.*
import com.hhst.youtubelite.playlist.domain.*
import com.hhst.youtubelite.channel.domain.*
import com.hhst.youtubelite.post.domain.ChannelPostsPage
import com.hhst.youtubelite.post.domain.PostSummary
import com.liskovsoft.youtubeapi.videoinfo.V2.*
import com.liskovsoft.youtubeapi.videoinfo.V2.WatchPage as CoreWatchPage
import com.hhst.youtubelite.watch.domain.WatchPage as AppWatchPage
import com.hhst.youtubelite.playlist.domain.PlaylistPage as AppPlaylistPage
import com.hhst.youtubelite.playlist.domain.PlaylistAppendPage as AppAppendPage
import com.hhst.youtubelite.channel.domain.ChannelPage as AppChannelPage

class MediaWatchRepository(private val client: MediaClient) : WatchRepository {
    override suspend fun watch(videoId: String): AppWatchPage = client.read { context, http ->
        val page = loadWatch(videoId, context, http = http).requireValue()
        val details = loadPlayerDetails(videoId, context, http).value
        page.toAppWatchPage(videoId, details, context.generation)
    }
    override suspend fun next(videoId: String, cursor: String): RelatedPage = client.read { context, http ->
        val page = loadWatch(videoId, context, continuation = cursor, http = http).requireValue()
        RelatedPage(page.related.filter { it.kind == "video" }.map(MediaItem::watchRelated), page.nextKey)
    }
}

internal fun CoreWatchPage.toAppWatchPage(id: String, details: PlayerDetails?, generation: Long? = null): AppWatchPage {
    fun String?.present() = this?.takeIf { it.isNotBlank() }
    val title = title.present() ?: details?.title.present()
        ?: throw MediaException(RequestError(RequestFailure.PARSE, "missing_video_details"))
    return AppWatchPage(WatchVideo(id, title,
        author.present() ?: details?.author.orEmpty(), "", channelId.present() ?: details?.channelId.orEmpty(),
        details?.thumbnail.orEmpty(), "", viewCount.present() ?: details?.viewCount?.let {
            java.text.NumberFormat.getIntegerInstance(java.util.Locale.US).format(it) + " views"
        }.orEmpty(), published.orEmpty(), commentCount.orEmpty(), WatchComment("", "", ""),
        description.present() ?: details?.description.orEmpty(), details?.liveContent == true,
        rating = when (rating) { "LIKE" -> VideoRating.Like; "DISLIKE" -> VideoRating.Dislike; else -> VideoRating.None },
        subscribed = subscribed == true, avatar = authorThumbnail.orEmpty(), commentsStatus = WatchCommentsStatus.Loading,
        tracking = details?.tracking, chatKey = chatKey, sessionGeneration = generation),
        related.filter { it.kind == "video" }.map(MediaItem::watchRelated), nextKey)

}

private fun MediaItem.watchRelated() = WatchRelated(id, title.orEmpty(), author.orEmpty(),
    channelId.orEmpty(), viewCount.orEmpty(), published.orEmpty(), duration.orEmpty(), thumbnail.orEmpty(),
    if (live) "LIVE" else null, progressPercent?.div(100f), authorThumbnail.orEmpty())

class MediaPlaylistRepository(private val client: MediaClient, private val local: LocalLibraryStore? = null,
    private val source: () -> com.hhst.youtubelite.library.domain.LibrarySource = { com.hhst.youtubelite.library.domain.LibrarySource.Account }) : PlaylistRepository {
    // First page only: painting requires just the head, so the page paints immediately and
    // appends from [playlistNext] instead of draining every continuation before first paint.
    override suspend fun playlist(id: String): AppPlaylistPage? {
        if (id.startsWith("local:") || id == "WL" && useDeviceLibrary(local, source)) return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            local?.playlist(if (id == "WL") LocalLibraryStore.WATCH_LATER else id)
        }
        return client.read { context, http ->
        val first = loadPlaylist(id, context, http = http).requireValue()
        if (first.title == null && first.videos.isEmpty()) return@read null
        playlistPage(id, first.title, first.owner, first.videos, first.nextKey)
        }
    }

    override suspend fun playlistNext(id: String, cursor: String): AppAppendPage? = client.read { context, http ->
        val page = loadPlaylist(id, context, cursor, http = http).requireValue()
        AppAppendPage(playlistEntries(page.videos), page.nextKey)
    }

    /** The loaded count is only stated once enumeration made it the whole truth. */
    internal fun playlistPage(id: String, title: String?, owner: String?, videos: List<PlaylistVideo>,
        nextKey: String?): AppPlaylistPage {
        val entries = playlistEntries(videos)
        val summary = PlaylistSummary(id, title.orEmpty(), owner.orEmpty(), "", null,
            entries.firstOrNull()?.artwork.orEmpty(), "", null)
        return AppPlaylistPage(summary, "", entries, nextKey)
    }

    private fun playlistEntries(videos: List<PlaylistVideo>) = videos.take(PlaylistLimits.MAX_ENTRIES).mapIndexed { index, it ->
        PlaylistEntry(it.setVideoId ?: "${it.videoId}:$index", it.videoId,
            it.title.orEmpty(), it.author.orEmpty(), "", it.thumbnail.orEmpty(), it.length.orEmpty(), "", "")
    }
}

class MediaChannelRepository(private val client: MediaClient, private val posts: com.hhst.youtubelite.post.domain.PostRepository) : ChannelRepository {
    override suspend fun channel(channelId: String): AppChannelPage = client.read { context, http ->
        val resolved = resolveChannel(channelId, context, http)
        val page = loadChannel(resolved, context, http = http).requireValue()
        // Optional tabs stay out of the first paint: the playlists tab and an unselected
        // videos tab load on demand, so their latency or failure never blocks the header.
        val videosTab = page.page.tabs.firstOrNull { it.title.equals("Videos", true) }
        val videosInline = videosTab == null || videosTab.selected
        AppChannelPage(ChannelProfile(resolved, page.title.orEmpty(), page.handle.orEmpty(), page.thumbnail.orEmpty(), page.banner,
            page.subscriberCount.orEmpty(), page.description.orEmpty(), videoCount = page.videoCount.orEmpty(),
            canonicalUrl = "https://www.youtube.com/channel/$resolved", subscribed = page.subscribed),
            if (videosInline) page.page.items.filter { it.kind == "video" }.map { it.channelVideo() } else emptyList(),
            emptyList(),
            page.page.groups.mapNotNull { group ->
                val videos = group.items.filter { it.kind == "video" }.map { it.channelVideo() }
                if (videos.isEmpty()) null else ChannelHomeShelf(group.title.orEmpty(), videos = videos)
            }.ifEmpty {
                page.page.items.filter { it.kind == "video" }.map { it.channelVideo() }.takeIf { it.isNotEmpty() }
                    ?.let { listOf(ChannelHomeShelf("", videos = it)) }.orEmpty()
            },
            videosNextKey = if (videosInline) page.page.nextKey else null,
            videosDeferred = !videosInline,
            playlistsDeferred = page.page.tabs.any { it.title.equals("Playlists", true) })
    }

    override suspend fun playlists(channelId: String): com.hhst.youtubelite.library.domain.PlaylistCollectionPage = client.read { context, http ->
        val resolved = resolveChannel(channelId, context, http)
        val first = loadChannel(resolved, context, http = http).requireValue()
        val tab = first.page.tabs.firstOrNull { it.title.equals("Playlists", true) }
        val page = if (tab?.params == null || tab.selected) first
            else loadChannel(tab.browseId ?: resolved, context, tab.params, http = http).requireValue()
        com.hhst.youtubelite.library.domain.PlaylistCollectionPage(
            page.page.items.filter { it.kind == "playlist" }.map(MediaItem::playlistSummary), page.page.nextKey)
    }

    override suspend fun playlistsNext(channelId: String, cursor: String): com.hhst.youtubelite.library.domain.PlaylistCollectionPage = client.read { context, http ->
        val resolved = resolveChannel(channelId, context, http)
        val page = loadChannel(resolved, context, continuation = cursor, http = http).requireValue()
        com.hhst.youtubelite.library.domain.PlaylistCollectionPage(
            page.page.items.filter { it.kind == "playlist" }.map(MediaItem::playlistSummary), page.page.nextKey)
    }

    override val serverSearch: Boolean get() = true

    override suspend fun searchVideos(channelId: String, query: String): ChannelSearchResults = client.read { context, http ->
        val resolved = resolveChannel(channelId, context, http)
        val page = loadChannel(resolved, context, search = query.trim(), http = http).requireValue()
        ChannelSearchResults(page.page.items.filter { it.kind == "video" }.map { it.channelVideo() }, page.page.nextKey)
    }

    override suspend fun searchNext(channelId: String, cursor: String): ChannelSearchResults = client.read { context, http ->
        val resolved = resolveChannel(channelId, context, http)
        val page = loadChannel(resolved, context, continuation = cursor, http = http).requireValue()
        ChannelSearchResults(page.page.items.filter { it.kind == "video" }.map { it.channelVideo() }, page.page.nextKey)
    }

    override suspend fun videos(channelId: String, continuation: String?): ChannelVideosPage = client.read { context, http ->
        val resolved = resolveChannel(channelId, context, http)
        val page = if (continuation != null) loadChannel(resolved, context, continuation = continuation, http = http).requireValue()
        else {
            val first = loadChannel(resolved, context, http = http).requireValue()
            val tab = first.page.tabs.firstOrNull { it.title.equals("Videos", true) }
            if (tab == null || tab.selected) first else loadChannel(resolved, context, tab.params, http = http).requireValue()
        }
        ChannelVideosPage(page.page.items.filter { it.kind == "video" }.map { it.channelVideo() }, page.page.nextKey)
    }

    override suspend fun shorts(channelId: String): ChannelShortsPage {
        val (items, nextKey) = tabPage(channelId, "Shorts")
        return ChannelShortsPage(items.filter { it.kind in setOf("video", "short") }.mapIndexed { index, it ->
            ChannelShort(it.id, it.title.orEmpty(), it.viewCount.orEmpty(), it.thumbnail.orEmpty(), index, viewMagnitude(it.viewCount))
        }, nextKey)
    }
    override suspend fun shortsNext(channelId: String, cursor: String): ChannelShortsPage = client.read { context, http ->
        val resolved = resolveChannel(channelId, context, http)
        val page = loadChannel(resolved, context, continuation = cursor, http = http).requireValue()
        ChannelShortsPage(page.page.items.filter { it.kind in setOf("video", "short") }.mapIndexed { index, it ->
            ChannelShort(it.id, it.title.orEmpty(), it.viewCount.orEmpty(), it.thumbnail.orEmpty(), index, viewMagnitude(it.viewCount))
        }, page.page.nextKey)
    }
    override suspend fun live(channelId: String): ChannelLivePage {
        val (items, nextKey) = tabPage(channelId, "Live")
        val videos = items.filter { it.kind == "video" }.mapIndexed { index, it ->
            ChannelLiveVideo(it.id, it.title.orEmpty(), it.viewCount.orEmpty(), it.published.orEmpty(), it.duration.orEmpty(), it.thumbnail.orEmpty(), it.live, index, viewMagnitude(it.viewCount))
        }
        return ChannelLivePage(videos.filter { it.liveNow }, videos.filterNot { it.liveNow }, nextKey)
    }
    override suspend fun liveNext(channelId: String, cursor: String): ChannelLivePage = client.read { context, http ->
        val resolved = resolveChannel(channelId, context, http)
        val page = loadChannel(resolved, context, continuation = cursor, http = http).requireValue()
        val videos = page.page.items.filter { it.kind == "video" }.mapIndexed { index, it ->
            ChannelLiveVideo(it.id, it.title.orEmpty(), it.viewCount.orEmpty(), it.published.orEmpty(), it.duration.orEmpty(), it.thumbnail.orEmpty(), it.live, index, viewMagnitude(it.viewCount))
        }
        ChannelLivePage(videos.filter { it.liveNow }, videos.filterNot { it.liveNow }, page.page.nextKey)
    }
    override suspend fun posts(channelId: String): ChannelPostsPage = posts.channelPosts(channelId)

    /** One tab page with its continuation, so Shorts/Live keep the cursors the adapter dropped. */
    private suspend fun tabPage(channelId: String, label: String): Pair<List<MediaItem>, String?> {
        val result = client.read<Pair<List<MediaItem>, String?>> { context, http ->
            val resolved = resolveChannel(channelId, context, http)
            val first = loadChannel(resolved, context, http = http).requireValue()
            val tab = first.page.tabs.firstOrNull { it.title.equals(label, true) }
                ?: return@read Pair(emptyList(), null)
            if (tab.selected) Pair(first.page.items, first.page.nextKey)
            else loadChannel(resolved, context, tab.params, http = http).requireValue()
                .let { Pair(it.page.items, it.page.nextKey) }
        }
        return result
    }
}

class MediaVoteRepository(private val client: MediaClient) : VoteCountRepository {
    override suspend fun counts(videoId: String): VoteCounts = client.read { _, http ->
        val votes = loadDislikes(videoId, http).requireValue()
        VoteCounts(votes.likes?.coerceAtLeast(0), votes.dislikes?.coerceAtLeast(0))
    }
}

internal fun resolveChannel(id: String, context: RequestContext, http: HttpClient): String =
    com.liskovsoft.youtubeapi.videoinfo.V2.resolveChannelId(id, context, http).requireValue()

private fun MediaItem.channelVideo() = ChannelVideo(id, title.orEmpty(), viewCount.orEmpty(), published.orEmpty(),
    duration.orEmpty(), thumbnail.orEmpty(), live)

internal fun viewMagnitude(label: String?): Long {
    val match = Regex("([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*([KMB]?)", RegexOption.IGNORE_CASE).find(label.orEmpty()) ?: return 0
    val amount = match.groupValues[1].replace(",", "").toDoubleOrNull() ?: return 0
    val scale = when (match.groupValues[2].uppercase()) { "K" -> 1e3; "M" -> 1e6; "B" -> 1e9; else -> 1.0 }
    return (amount * scale).coerceAtMost(Long.MAX_VALUE.toDouble()).toLong()
}
