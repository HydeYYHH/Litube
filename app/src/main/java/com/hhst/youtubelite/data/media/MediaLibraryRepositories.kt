package com.hhst.youtubelite.data.media

import com.hhst.youtubelite.library.domain.*
import com.hhst.youtubelite.library.data.LibraryRepository
import com.hhst.youtubelite.subscriptions.data.*
import com.hhst.youtubelite.subscriptions.domain.*
import com.hhst.youtubelite.notifications.data.NotificationsRepository
import com.hhst.youtubelite.notifications.domain.*
import com.hhst.youtubelite.explore.data.ExploreRepository
import com.hhst.youtubelite.explore.domain.*
import com.hhst.youtubelite.shorts.data.ShortsRepository
import com.hhst.youtubelite.shorts.domain.*
import com.liskovsoft.youtubeapi.videoinfo.V2.*
import kotlinx.coroutines.async

class MediaLibraryRepository(private val client: MediaClient, private val local: LocalLibraryStore? = null) : LibraryRepository {
    override val supportsLocal get() = local != null
    private suspend fun <T> device(block: (LocalLibraryStore) -> T): T {
        val generation = MediaSession.snapshot().generation
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            MediaSession.withAnonymousSession(generation) { block(requireNotNull(local)) }
        }
    }
    override suspend fun you(): YouPage = kotlinx.coroutines.coroutineScope {
        if (!MediaSession.signedIn() && local != null) return@coroutineScope device {
            YouPage(AccountProfile("On this device", "Local history and playlists", "", ""), it.history().entries, it.playlists().playlists)
        }
        // The account is the primary read; history and playlists are optional shelves that
        // fail on their own so one rejected read cannot blank a usable page.
        val account = async {
            client.read { context, http ->
                val accounts = loadAccounts(context, http).requireValue()
                val profile = accounts.firstOrNull { it.id == context.accountId && !it.disabled }
                    ?: accounts.firstOrNull { it.selected && !it.disabled }
                    ?: throw MediaException(RequestError(RequestFailure.AUTH, "account_unavailable"))
                AccountProfile(profile.name.orEmpty(), profile.email.orEmpty(), profile.photo.orEmpty(), profile.pageId.orEmpty())
            }
        }
        val history = async { try { Result.success(history()) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { Result.failure(e) } }
        val playlists = async { try { Result.success(playlists()) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { Result.failure(e) } }
        val historyResult = history.await()
        val playlistResult = playlists.await()
        YouPage(account.await(),
            historyResult.getOrDefault(HistoryPage(emptyList())).entries,
            playlistResult.getOrDefault(PlaylistCollectionPage(emptyList())).playlists,
            historyFailed = historyResult.isFailure,
            playlistsFailed = playlistResult.isFailure)
    }
    override suspend fun history(): HistoryPage = if (!MediaSession.signedIn() && local != null) device { it.history() } else client.read { context, http ->
        historyPage(context, http, null)
    }
    override suspend fun historyNext(cursor: String): HistoryPage = client.read { context, http ->
        historyPage(context, http, cursor)
    }
    private fun historyPage(context: RequestContext, http: HttpClient, continuation: String?): HistoryPage {
        val page = loadLibrary(LibrarySection.HISTORY, context, continuation, http = http).requireValue()
        return HistoryPage(page.items.filter { it.kind == "video" }.map { HistoryEntry(it.id, it.id, it.title.orEmpty(),
            it.author.orEmpty(), it.channelId.orEmpty(), it.thumbnail.orEmpty(), it.duration.orEmpty(), it.progressPercent?.div(100f), "History",
            removeToken = it.actions.firstNotNullOfOrNull { action -> action.feedbackToken() }) }, page.nextKey)
    }
    override suspend fun removeFromHistory(feedbackToken: String) {
        if (!MediaSession.signedIn() && local != null) return device { it.remove(feedbackToken) }
        historyWrite { context, http -> removeWatchHistory(feedbackToken, context, http).requireValue() }
    }
    override suspend fun setHistoryPaused(paused: Boolean) {
        if (!MediaSession.signedIn() && local != null) return device { it.pause(paused) }
        historyWrite { context, http -> setWatchHistory(!paused, context, http).requireValue() }
    }
    override suspend fun clearHistory() {
        if (!MediaSession.signedIn() && local != null) return device { it.clear() }
        historyWrite { context, http -> clearWatchHistory(context, http).requireValue() }
    }
    private suspend fun historyWrite(block: (RequestContext, HttpClient) -> ActionReply) {
        try {
            client.read { context, http ->
                if (!context.signedIn) throw com.hhst.youtubelite.watch.domain.SignInRequiredException()
                if (!block(context, http).confirmed)
                    throw com.hhst.youtubelite.watch.domain.UnconfirmedActionException()
            }
        } catch (error: MediaException) {
            if (error.failure.kind == RequestFailure.AUTH) throw com.hhst.youtubelite.watch.domain.SignInRequiredException()
            throw error
        }
    }
    override suspend fun playlists(): PlaylistCollectionPage = if (!MediaSession.signedIn() && local != null) device { it.playlists() } else client.read { context, http ->
        val page = loadLibrary(LibrarySection.PLAYLISTS, context, http = http).requireValue()
        PlaylistCollectionPage(page.items.filter { it.kind == "playlist" }.map(MediaItem::playlistSummary), page.nextKey)
    }
    override suspend fun playlistsNext(cursor: String): PlaylistCollectionPage = client.read { context, http ->
        val page = loadLibrary(LibrarySection.PLAYLISTS, context, cursor, http = http).requireValue()
        PlaylistCollectionPage(page.items.filter { it.kind == "playlist" }.map(MediaItem::playlistSummary), page.nextKey)
    }
}

class MediaSubscriptionsRepository(private val client: MediaClient) : SubscriptionsRepository {
    // Today/Posts have no server-side lane: the subscriptions browse only filters by params we
    // don't have for those two, so they are hidden rather than offered and failed.
    override val supportedFilters = SubscriptionsFilter.entries.toSet() - SubscriptionsFilter.Today - SubscriptionsFilter.Posts
    override suspend fun subscriptions(filter: SubscriptionsFilter, continuation: String?): SubscriptionsPage = client.read { context, http ->
        if (filter == SubscriptionsFilter.Today || filter == SubscriptionsFilter.Posts)
            throw MediaException(RequestError(RequestFailure.UNSUPPORTED, "subscription_filter_unavailable"))
        val page = loadLibrary(LibrarySection.SUBSCRIPTIONS, context, continuation, http).requireValue()
        val videos = page.items.filter { it.kind == "video" }.map(MediaItem::feedVideo)
        SubscriptionsPage(when (filter) {
            SubscriptionsFilter.Live -> videos.filter { it.live }
            SubscriptionsFilter.Unwatched -> videos.filter { it.progress == null || it.progress == 0f }
            SubscriptionsFilter.ContinueWatching -> videos.filter { (it.progress ?: 0f) > 0f }
            else -> videos
        }, emptyList(), page.nextKey)
    }

    // The channel guide is its own read: a rejected guide request must not erase a usable feed.
    override suspend fun subscribedChannels(): List<SubscribedChannel> = client.read { context, http ->
        loadSubscribedChannels(context, http).requireValue().map {
            SubscribedChannel(it.id, it.title.orEmpty(), it.thumbnail.orEmpty(), "")
        }
    }
}

class MediaNotificationsRepository(private val client: MediaClient) : NotificationsRepository {
    override suspend fun notifications() = client.read { context, http ->
        val page = loadNotices(context, http = http).requireValue()
        NotificationsPage(page.notices.mapIndexed { index, it -> NotificationItem("${it.videoId}:${it.published}:$index", "", it.author.orEmpty(), "",
            it.message ?: it.title.orEmpty(), it.published.orEmpty(), it.thumbnail.orEmpty(), it.videoId) })
    }
}

class MediaExploreRepository(private val client: MediaClient) : ExploreRepository {
    override suspend fun explore(category: ExploreCategory) = client.read { context, http ->
        val page = loadHome(HomeSection.valueOf(category.name.uppercase()), context, http = http).requireValue()
        ExplorePage(category, page.items.filter { it.kind == "video" }.map(MediaItem::feedVideo))
    }
}

class MediaShortsRepository(private val client: MediaClient, private val startId: String? = null, private val channelId: String? = null) : ShortsRepository {
    override suspend fun shorts() = client.read { context, http ->
        // An explicit seed is the primary read: it resolves first and independently, so a
        // rejected global shorts feed (the seedless reel_watch_sequence is network-rejected
        // on some networks) cannot block a valid seeded entry. When the seed itself fails,
        // the whole read fails — the pager never quietly plays a different video.
        if (startId != null) {
            val video = loadWatch(startId, context, http = http).requireValue()
            val seed = ShortItem(startId, video.title.orEmpty(), video.author.orEmpty(), video.channelId.orEmpty(),
                "https://i.ytimg.com/vi/$startId/hqdefault.jpg", "", "", "")
            val rest = try {
                surroundingItems(context, http).filter { it.id != startId }
            } catch (error: MediaException) {
                emptyList()
            }
            ShortsPage(listOf(seed) + rest)
        } else {
            ShortsPage(surroundingItems(context, http))
        }
    }

    /** Feed items around the seed; plain failure for seedless entries. */
    private fun surroundingItems(context: RequestContext, http: HttpClient): List<ShortItem> {
        val page = if (channelId == null) loadHome(HomeSection.SHORTS, context, http = http).requireValue() else {
            val channel = loadChannel(channelId!!, context, http = http).requireValue()
            val tab = channel.page.tabs.firstOrNull { it.title.equals("Shorts", true) }
            if (tab == null) channel.page.copy(items = emptyList()) else loadChannel(channelId, context, tab.params, http = http).requireValue().page
        }
        return page.items.filter { it.kind in setOf("video", "short") }.map {
            ShortItem(it.id, it.title.orEmpty(), it.author.orEmpty(), it.channelId.orEmpty(), it.thumbnail.orEmpty(), "", "", "")
        }
    }
}
