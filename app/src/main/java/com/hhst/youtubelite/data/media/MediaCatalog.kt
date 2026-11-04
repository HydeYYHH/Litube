package com.hhst.youtubelite.data.media

import com.hhst.youtubelite.home.domain.*
import com.hhst.youtubelite.search.domain.*
import com.hhst.youtubelite.playlist.domain.PlaylistSummary
import com.liskovsoft.youtubeapi.videoinfo.V2.MediaItem
import com.liskovsoft.youtubeapi.videoinfo.V2.HomeSection
import com.liskovsoft.youtubeapi.videoinfo.V2.MenuActionKind
import com.liskovsoft.youtubeapi.videoinfo.V2.loadHome
import com.liskovsoft.youtubeapi.videoinfo.V2.loadSearch
import com.liskovsoft.youtubeapi.videoinfo.V2.sendFeedback
import com.liskovsoft.youtubeapi.videoinfo.V2.loadMenu
import com.liskovsoft.youtubeapi.videoinfo.V2.MenuEndpoint

// Metadata joins drop blank parts so a missing half cannot leave an orphaned separator;
// the avatar is null (not "") when the response did not carry one, which is the signal
// the shared avatar directory enriches from.
internal fun MediaItem.feedVideo() = FeedItem.Video(id, title.orEmpty(), author.orEmpty(),
    listOfNotNull(viewCount, published).filter(String::isNotBlank).joinToString(" · "), thumbnail.orEmpty(),
    duration.orEmpty(), channelId.orEmpty(), live, progressPercent?.div(100f), avatar = authorThumbnail?.takeIf(String::isNotBlank),
    notInterestedToken = notInterestedToken(),
    dontRecommendToken = dontRecommendToken(),
    menuEndpoint = menuEndpoint?.let { FeedMenuEndpoint(it.panelId, it.params) }, short = kind == "short")

/** The card's own feedback token for a menu operation the server classified semantically. */
internal fun MediaItem.notInterestedToken(): String? =
    actions.firstOrNull { it.kind == MenuActionKind.NotInterested }?.kindToken
internal fun MediaItem.dontRecommendToken(): String? =
    actions.firstOrNull { it.kind == MenuActionKind.DontRecommendChannel }?.kindToken

internal fun MediaItem.playlistSummary() = PlaylistSummary(id.removePrefix("VL"), title.orEmpty(), author.orEmpty(),
    channelId.orEmpty(), null, thumbnail.orEmpty(), "", null, videoCountText = videoCountText)

/**
 * LRU cache of resolved get_panel capabilities, shared by the home and search repositories.
 * Entries are partitioned by identity (account, on-behalf channel, generation) and card, and
 * expire after 60s; a lookup also purges entries from other identities so a stale session's
 * tokens can never be served to the current one.
 */
internal class DeferredMenuCache(private val now: () -> Long = System::currentTimeMillis) {
    data class Key(val account: String?, val channel: String?, val generation: Long, val videoId: String,
        val endpoint: FeedMenuEndpoint)
    data class Capabilities(val at: Long, val notInterested: String?, val dontRecommend: String?)
    private val entries = LinkedHashMap<Key, Capabilities>(32, 0.75f, true)

    fun get(key: Key): Capabilities? = synchronized(entries) {
        entries.entries.removeAll { it.key.account != key.account || it.key.channel != key.channel ||
            it.key.generation != key.generation || now() - it.value.at !in 0..60_000 }
        entries[key]
    }

    fun put(key: Key, capabilities: Capabilities) = synchronized(entries) {
        entries[key] = capabilities
        while (entries.size > 32) entries.remove(entries.keys.first())
    }
}

class MediaHomeRepository(private val client: MediaClient,
    private val now: () -> Long = System::currentTimeMillis) : HomeRepository {
    private val menus = DeferredMenuCache(now)
    private val discovery = HomeDiscovery { section, key, generation ->
        client.readIdempotent { context, http ->
            if (context.generation != generation)
                throw kotlinx.coroutines.CancellationException("Session changed")
            loadHome(section, context.anonymous().copy(language = "en"), key, http).requireValue()
        }
    }
    override val filters = listOf("All", "Music", "Gaming", "Live", "News", "Sports")
    override suspend fun loadHome(filter: String, continuation: String?): HomePage {
        val catalog = HomeCatalog.forFilter(filter)
        val context = client.read { context, _ -> context }
        if (HomeDiscovery.owns(continuation)) {
            if (context.signedIn && catalog != HomeCatalog.MusicCharts || catalog == null)
                throw kotlinx.coroutines.CancellationException("Session changed")
            return discovery.load(context.generation, catalog, continuation)
        }
        if (filter == HomeCatalog.MusicCharts.filter || !context.signedIn && catalog != null && catalog != HomeCatalog.Popular)
            return discovery.load(context.generation, requireNotNull(catalog), continuation)
        val (loadedContext, page) = client.readIdempotent { context, http ->
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
            val homeContext = if (continuation == null) anonymousHomeContext(context, http,
                MediaSession.anonymousHomeIdentityVerified(context)) else context
            val page = loadHome(section, homeContext, continuation, http).requireValue()
            if (!context.signedIn) {
                val cookie = homeContext.visitorCookie
                if (cookie != null) MediaSession.rememberVisitorIdentity(homeContext.visitor, cookie, context.generation)
                else MediaSession.rememberVisitor(page.visitor, context.generation)
            }
            val feed = homeFeed(page.items).map { item ->
                if (item is FeedItem.Video && context.signedIn) item.copy(
                    dontRecommendToken = page.items.firstOrNull { it.id == item.id }?.dontRecommendToken(),
                    feedbackGeneration = context.generation,
                ) else if (item is FeedItem.Video) item.copy(notInterestedToken = null, dontRecommendToken = null,
                    menuEndpoint = null) else item
            }
            context to HomePage(feed, page.messages, page.nextKey)
        }
        return if (!loadedContext.signedIn && filter == "All" && continuation == null && page.items.isEmpty() && page.nextKey == null)
            discovery.load(loadedContext.generation) else page
    }

    override suspend fun menu(video: FeedItem.Video): FeedItem.Video {
        val endpoint = video.menuEndpoint ?: return video
        return client.readIdempotent { context, http ->
            if (!context.signedIn || video.feedbackGeneration != context.generation)
                throw kotlinx.coroutines.CancellationException("Session changed")
            val key = DeferredMenuCache.Key(context.accountId, context.onBehalfOfUser, context.generation, video.id, endpoint)
            val cached = menus.get(key)
            val capabilities = cached ?: loadMenu(MenuEndpoint(endpoint.panelId, endpoint.params), context, http)
                .requireValue().let { actions ->
                    DeferredMenuCache.Capabilities(now(), actions.firstOrNull { it.kind == MenuActionKind.NotInterested }?.kindToken,
                        actions.firstOrNull { it.kind == MenuActionKind.DontRecommendChannel }?.kindToken)
                }.also { loaded -> menus.put(key, loaded) }
            video.copy(notInterestedToken = capabilities.notInterested, dontRecommendToken = capabilities.dontRecommend,
                feedbackGeneration = context.generation)
        }
    }

    override suspend fun feedback(video: FeedItem.Video, action: FeedMenuAction) {
        client.read { context, http ->
            if (!context.signedIn) throw com.hhst.youtubelite.watch.domain.SignInRequiredException()
            val token = when (action) {
                FeedMenuAction.NotInterested -> video.notInterestedToken
                FeedMenuAction.DontRecommend -> video.dontRecommendToken
                else -> null
            }
            if (token.isNullOrBlank() || video.feedbackGeneration != context.generation)
                throw com.hhst.youtubelite.watch.domain.UnconfirmedActionException()
            sendFeedback(token, context, http).requireValue()
        }
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

class MediaSearchRepository(private val client: MediaClient,
    private val now: () -> Long = System::currentTimeMillis) : SearchRepository {
    private val menus = DeferredMenuCache(now)
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
        page.toSearchPage(query, context)
    }

    override suspend fun next(query: String, filters: SearchFilters, cursor: String): SearchPage = client.read { context, http ->
        loadSearch(query, context, continuation = cursor, http = http).requireValue().toSearchPage(query, context)
    }

    override suspend fun menu(result: SearchResult): SearchResult {
        val endpoint = result.menuEndpoint ?: return result
        return client.readIdempotent { context, http ->
            if (!context.signedIn || result.feedbackGeneration != context.generation)
                throw kotlinx.coroutines.CancellationException("Session changed")
            val key = DeferredMenuCache.Key(context.accountId, context.onBehalfOfUser, context.generation, result.id, endpoint)
            val capabilities = menus.get(key) ?: loadMenu(MenuEndpoint(endpoint.panelId, endpoint.params), context, http)
                .requireValue().let { actions ->
                    DeferredMenuCache.Capabilities(now(), actions.firstOrNull { it.kind == MenuActionKind.NotInterested }?.kindToken,
                        actions.firstOrNull { it.kind == MenuActionKind.DontRecommendChannel }?.kindToken)
                }.also { loaded -> menus.put(key, loaded) }
            result.copy(notInterestedToken = capabilities.notInterested, dontRecommendToken = capabilities.dontRecommend,
                feedbackGeneration = context.generation)
        }
    }

    override suspend fun feedback(result: SearchResult, action: SearchVideoMenuAction) {
        client.read { context, http ->
            if (!context.signedIn) throw com.hhst.youtubelite.watch.domain.SignInRequiredException()
            val token = when (action) {
                SearchVideoMenuAction.NotInterested -> result.notInterestedToken
                SearchVideoMenuAction.DontRecommend -> result.dontRecommendToken
                else -> null
            }
            if (token.isNullOrBlank() || result.feedbackGeneration != context.generation)
                throw com.hhst.youtubelite.watch.domain.UnconfirmedActionException()
            sendFeedback(token, context, http).requireValue()
        }
    }
}

private fun com.liskovsoft.youtubeapi.videoinfo.V2.SearchPage.toSearchPage(query: String,
    context: com.liskovsoft.youtubeapi.videoinfo.V2.RequestContext): SearchPage {
    // Anonymous sessions never carry feedback capabilities, so a forged menu action
    // cannot even be attempted from a stale card.
    val signedIn = context.signedIn
    fun MediaItem.result() = SearchResult(id, title.orEmpty(), author.orEmpty(), channelId.orEmpty(), viewCount.orEmpty(), published.orEmpty(),
        duration.orEmpty(), thumbnail.orEmpty(), if (live) "LIVE" else null, progressPercent?.div(100f), avatar = authorThumbnail?.takeIf(String::isNotBlank),
        notInterestedToken = notInterestedToken()?.takeIf { signedIn },
        dontRecommendToken = dontRecommendToken()?.takeIf { signedIn },
        menuEndpoint = menuEndpoint?.takeIf { signedIn }?.let { FeedMenuEndpoint(it.panelId, it.params) },
        feedbackGeneration = context.generation.takeIf { signedIn })
    return SearchPage(query, items.filter { it.kind == "video" }.map { it.result() },
        items.filter { it.kind == "playlist" }.map(MediaItem::playlistSummary),
        items.filter { it.kind == "channel" }.map { SearchChannel(it.id, it.title.orEmpty(), "", "", it.thumbnail.orEmpty()) },
        items.filter { it.kind == "short" }.map { it.result() }, nextKey)
}
