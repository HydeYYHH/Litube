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

class MediaWatchRepository(private val client: MediaClient,
    private val shared: SharedWatchReads? = null) : WatchRepository {
    private val menus = DeferredMenuCache()
    override suspend fun watch(videoId: String): AppWatchPage = readWatch(videoId, fresh = false)
    override suspend fun watchFresh(videoId: String): AppWatchPage = readWatch(videoId, fresh = true)

    private suspend fun readWatch(videoId: String, fresh: Boolean): AppWatchPage {
        val (page, initialDetails) = client.readTogether(
            { context, http ->
                val core: CoreWatchPage = if (shared != null)
                    shared.read(videoId, context, http, fresh) { id, ctx, httpClient ->
                        loadWatch(id, ctx, http = httpClient)
                    }.requireValue()
                else loadWatch(videoId, context, http = http).requireValue()
                Triple(core, context.generation, context.signedIn)
            },
            { context, http -> loadPlayerDetails(videoId, context, http).value },
        )
        // TV music metadata omits publication dates, and its player response may be
        // partial. Supplement display fields from public WEB metadata, retaining the
        // signed-in tracking identity used by playback/reporting.
        val details = if (page.third && (page.first.published.isNullOrBlank() && initialDetails?.published.isNullOrBlank() ||
            page.first.author.isNullOrBlank() && initialDetails?.author.isNullOrBlank())) {
            val supplement = try { client.read { context, http ->
                if (context.generation != page.second) throw kotlinx.coroutines.CancellationException("Session changed")
                loadPlayerDetails(videoId, context.anonymous(), http).value
            } } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            initialDetails?.copy(title = initialDetails.title ?: supplement?.title,
                author = initialDetails.author ?: supplement?.author,
                channelId = initialDetails.channelId ?: supplement?.channelId,
                description = initialDetails.description ?: supplement?.description,
                published = initialDetails.published ?: supplement?.published)
                ?: supplement?.copy(tracking = null)
        } else initialDetails
        val result = page.first.toAppWatchPage(videoId, details, page.second, page.third)
        // Signed-in TV /next omits liveChatRenderer even when the stream has an
        // active chat. Discover its continuation via public WEB /next, then keep
        // polling/posting through the current account's existing chat transport.
        // Merge only the chat key: public recommendations and account state must
        // never replace the signed-in page.
        if (!page.third || !result.video.live || !result.video.chatKey.isNullOrBlank()) return result
        return try {
            val chatKey = client.read { context, http ->
                if (context.generation != page.second) throw kotlinx.coroutines.CancellationException("Session changed")
                loadWatch(videoId, context.anonymous(), http = http).requireValue().chatKey
            }
            result.copy(video = result.video.copy(chatKey = chatKey))
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { result.copy(video = result.video.copy(chatLookupFailed = true)) }
    }
    override suspend fun menu(video: WatchRelated): WatchRelated {
        val endpoint = video.menuEndpoint ?: video.feedbackGeneration?.let { recommendationMenuEndpoint(video.id) } ?: return video
        return client.readIdempotent { context, http ->
            if (!context.signedIn || video.feedbackGeneration != context.generation)
                throw kotlinx.coroutines.CancellationException("Session changed")
            val key = DeferredMenuCache.Key(context.accountId, context.onBehalfOfUser, context.generation, video.id, endpoint)
            val capabilities = menus.get(key) ?: loadMenu(MenuEndpoint(endpoint.panelId, endpoint.params), context, http)
                .requireValue().let { actions ->
                    DeferredMenuCache.Capabilities(System.currentTimeMillis(),
                        actions.firstOrNull { it.kind == MenuActionKind.NotInterested }?.kindToken,
                        actions.firstOrNull { it.kind == MenuActionKind.DontRecommendChannel }?.kindToken)
                }.also { menus.put(key, it) }
            video.copy(notInterestedToken = capabilities.notInterested, dontRecommendToken = capabilities.dontRecommend,
                feedbackGeneration = context.generation)
        }
    }
    override suspend fun next(videoId: String, cursor: String): RelatedPage = client.read { context, http ->
        val page = loadWatch(videoId, context, continuation = cursor, http = http).requireValue()
        RelatedPage(page.related.filter { it.kind == "video" }.map { it.watchRelated(context.generation.takeIf { context.signedIn }) }, page.nextKey)
    }
    override suspend fun feedback(video: WatchRelated, action: WatchVideoMenuAction) = client.write { context, http ->
        if (!context.signedIn) throw SignInRequiredException()
        val token = when (action) {
            WatchVideoMenuAction.NotInterested -> video.notInterestedToken
            WatchVideoMenuAction.DontRecommend -> video.dontRecommendToken
            else -> null
        }
        if (token.isNullOrBlank() || video.feedbackGeneration != context.generation) throw UnconfirmedActionException()
        sendFeedback(token, context, http).requireValue()
        shared?.clear()
        Unit
    }
}

internal fun CoreWatchPage.toAppWatchPage(id: String, details: PlayerDetails?, generation: Long? = null, feedbackAllowed: Boolean = false): AppWatchPage {
    fun String?.present() = this?.takeIf { it.isNotBlank() }
    val title = title.present() ?: details?.title.present()
        ?: throw MediaException(RequestError(RequestFailure.PARSE, "missing_video_details"))
    return AppWatchPage(WatchVideo(id, title,
        author.present() ?: details?.author.orEmpty(), "", channelId.present() ?: details?.channelId.orEmpty(),
        details?.thumbnail.orEmpty(), "", viewCount.present() ?: details?.viewCount?.let {
            java.text.NumberFormat.getIntegerInstance(java.util.Locale.US).format(it) + " views"
        }.orEmpty(), published.present() ?: details?.published?.substringBefore('T').orEmpty(), commentCount.orEmpty(), WatchComment("", "", ""),
        description.present() ?: details?.description.orEmpty(), details?.live == true,
        rating = when (rating) { "LIKE" -> VideoRating.Like; "DISLIKE" -> VideoRating.Dislike; else -> VideoRating.None },
        subscribed = subscribed == true, avatar = authorThumbnail.orEmpty(), commentsStatus = WatchCommentsStatus.Loading,
        tracking = details?.tracking, chatKey = chatKey, sessionGeneration = generation),
        related.filter { it.kind == "video" }.map { it.watchRelated(generation.takeIf { feedbackAllowed }) }, nextKey)

}

private fun MediaItem.watchRelated(generation: Long? = null) = WatchRelated(id, title.orEmpty(), author.orEmpty(),
    channelId.orEmpty(), viewCount.orEmpty(), published.orEmpty(), duration.orEmpty(), thumbnail.orEmpty(),
    if (live) "LIVE" else null, progressPercent?.div(100f), authorThumbnail.orEmpty(),
    notInterestedToken = notInterestedToken().takeIf { generation != null },
    dontRecommendToken = dontRecommendToken().takeIf { generation != null }, feedbackGeneration = generation,
    menuEndpoint = menuEndpoint?.takeIf { generation != null }?.let {
        com.hhst.youtubelite.home.domain.FeedMenuEndpoint(it.panelId, it.params)
    })

class MediaPlaylistRepository(private val client: MediaClient, private val local: LocalLibraryStore? = null) : PlaylistRepository {
    // First page only: painting requires just the head, so the page paints immediately and
    // appends from [playlistNext] instead of draining every continuation before first paint.
    override suspend fun playlist(id: String): AppPlaylistPage? {
        if (id.startsWith("local:") || id == "WL" && useDeviceLibrary(local)) return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
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
    private class Landing(val owner: MediaChannelRepository, val context: RequestContext,
        val page: com.liskovsoft.youtubeapi.videoinfo.V2.ChannelPage) : ChannelReadReference

    override suspend fun channel(channelId: String): AppChannelPage = client.readIdempotent { context, http ->
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
            page.page.items.filter { it.kind == "playlist" }.map(MediaItem::playlistSummary),
            page.page.groups.mapNotNull { group ->
                val videos = group.items.filter { it.kind == "video" }.map { it.channelVideo() }
                val playlists = group.items.filter { it.kind == "playlist" }.map(MediaItem::playlistSummary)
                if (videos.isEmpty() && playlists.isEmpty()) null
                else ChannelHomeShelf(group.title.orEmpty(), videos = videos, playlists = playlists)
            }.ifEmpty {
                val videos = page.page.items.filter { it.kind == "video" }.map { it.channelVideo() }
                val playlists = page.page.items.filter { it.kind == "playlist" }.map(MediaItem::playlistSummary)
                if (videos.isEmpty() && playlists.isEmpty()) emptyList()
                else listOf(ChannelHomeShelf("", videos = videos, playlists = playlists))
            },
            videosNextKey = if (videosInline) page.page.nextKey else null,
            videosDeferred = !videosInline,
            playlistsDeferred = page.page.tabs.any { it.title.equals("Playlists", true) },
            readReference = Landing(this, context, page))
    }

    override suspend fun playlists(channelId: String): com.hhst.youtubelite.library.domain.PlaylistCollectionPage = playlistPage(channelId, null)

    override suspend fun playlists(channelId: String, opened: AppChannelPage): com.hhst.youtubelite.library.domain.PlaylistCollectionPage = playlistPage(channelId, opened)

    private suspend fun playlistPage(channelId: String, opened: AppChannelPage?): com.hhst.youtubelite.library.domain.PlaylistCollectionPage = client.readIdempotent { context, http ->
        val page = loadTab(channelId, "Playlists", opened, context, http)!!
        com.hhst.youtubelite.library.domain.PlaylistCollectionPage(
            page.page.items.filter { it.kind == "playlist" }.map(MediaItem::playlistSummary), page.page.nextKey)
    }

    override suspend fun playlistsNext(channelId: String, cursor: String): com.hhst.youtubelite.library.domain.PlaylistCollectionPage = client.readIdempotent { context, http ->
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

    override suspend fun videos(channelId: String, continuation: String?): ChannelVideosPage = videoPage(channelId, continuation, null)

    override suspend fun videos(channelId: String, opened: AppChannelPage): ChannelVideosPage = videoPage(channelId, null, opened)

    private suspend fun videoPage(channelId: String, continuation: String?, opened: AppChannelPage?): ChannelVideosPage = client.read { context, http ->
        val page = if (continuation != null) loadChannel(resolveChannel(channelId, context, http), context,
            continuation = continuation, http = http).requireValue()
        else loadTab(channelId, "Videos", opened, context, http)!!
        ChannelVideosPage(page.page.items.filter { it.kind == "video" }.map { it.channelVideo() }, page.page.nextKey)
    }

    override suspend fun shorts(channelId: String): ChannelShortsPage {
        return shortsPage(channelId, null)
    }
    override suspend fun shorts(channelId: String, opened: AppChannelPage): ChannelShortsPage = shortsPage(channelId, opened)

    private suspend fun shortsPage(channelId: String, opened: AppChannelPage?): ChannelShortsPage {
        val (items, nextKey) = tabPage(channelId, "Shorts", opened)
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
        return livePage(channelId, null)
    }
    override suspend fun live(channelId: String, opened: AppChannelPage): ChannelLivePage = livePage(channelId, opened)

    private suspend fun livePage(channelId: String, opened: AppChannelPage?): ChannelLivePage {
        val (items, nextKey) = tabPage(channelId, "Live", opened)
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

    override suspend fun postsNext(channelId: String, cursor: String): ChannelPostsPage = posts.channelPostsNext(channelId, cursor)

    /** One tab page with its continuation, so Shorts/Live keep the cursors the adapter dropped. */
    private suspend fun tabPage(channelId: String, label: String, opened: AppChannelPage?): Pair<List<MediaItem>, String?> =
        client.read { context, http ->
            loadTab(channelId, label, opened, context, http)?.page?.let { it.items to it.nextKey }
                ?: (emptyList<MediaItem>() to null)
        }

    private fun loadTab(channelId: String, label: String, opened: AppChannelPage?, context: RequestContext,
                        http: HttpClient): com.liskovsoft.youtubeapi.videoinfo.V2.ChannelPage? {
        // Endpoints belong to the opened page, not a long-lived channel cache. A changed
        // account, token, visitor or locale must discover that session's own endpoints.
        val landing = (opened?.readReference as? Landing)?.takeIf {
            it.owner === this && it.context == context && it.page.id == channelId
        }
        val resolved = landing?.page?.id ?: resolveChannel(channelId, context, http)
        val first = landing?.page ?: loadChannel(resolved, context, http = http).requireValue()
        val tab = first.page.tabs.firstOrNull { it.title.equals(label, true) }
        if (tab == null) return first.takeIf { label == "Videos" || label == "Playlists" }
        if (tab.selected || label == "Playlists" && tab.params == null) return first
        return loadChannel(tab.browseId ?: resolved, context, tab.params, http = http).requireValue()
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
