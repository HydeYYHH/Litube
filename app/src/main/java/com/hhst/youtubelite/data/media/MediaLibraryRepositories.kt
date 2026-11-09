package com.hhst.youtubelite.data.media

import com.hhst.youtubelite.library.domain.*
import com.hhst.youtubelite.library.data.LibraryRepository
import com.hhst.youtubelite.subscriptions.data.*
import com.hhst.youtubelite.subscriptions.domain.*
import com.hhst.youtubelite.explore.data.ExploreRepository
import com.hhst.youtubelite.explore.domain.*
import com.hhst.youtubelite.shorts.data.ShortsRepository
import com.hhst.youtubelite.shorts.domain.*
import com.hhst.youtubelite.watch.domain.VideoRating
import com.liskovsoft.youtubeapi.videoinfo.V2.*
import kotlinx.coroutines.async

class MediaLibraryRepository(private val client: MediaClient, private val local: LocalLibraryStore? = null) : LibraryRepository {
    override val supportsLocal get() = local != null
    override val hasSavedAccounts get() = MediaSession.accounts().any { !it.disabled }
    private fun useDevice() = useDeviceLibrary(local)
    private suspend fun <T> device(block: (LocalLibraryStore) -> T): T {
        val operation = LibraryOperation(local)
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            operation.local { block(requireNotNull(local)) }
        }
    }
    override suspend fun you(): YouPage = kotlinx.coroutines.coroutineScope {
        val operation = LibraryOperation(local)
        if (useDevice()) return@coroutineScope device {
            YouPage(AccountProfile("On this device", "Local history and playlists", "", ""), it.history().entries, it.playlists().playlists)
        }
        // The account is the primary read; history and playlists are optional shelves that
        // fail on their own so one rejected read cannot blank a usable page.
        val account = async {
            remote { context, http ->
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
        val profile = account.await()
        operation.check()
        YouPage(profile,
            historyResult.getOrDefault(HistoryPage(emptyList())).entries,
            playlistResult.getOrDefault(PlaylistCollectionPage(emptyList())).playlists,
            historyFailed = historyResult.isFailure,
            playlistsFailed = playlistResult.isFailure)
    }
    override suspend fun history(): HistoryPage = if (useDevice()) device { it.history() } else remote { context, http ->
        historyPage(context, http, null)
    }
    override suspend fun historyNext(cursor: String): HistoryPage = if (useDevice()) HistoryPage(emptyList()) else remote { context, http ->
        historyPage(context, http, cursor)
    }
    private fun historyPage(context: RequestContext, http: HttpClient, continuation: String?): HistoryPage {
        val page = loadLibrary(LibrarySection.HISTORY, context, continuation, http = http).requireValue()
        return HistoryPage(page.items.filter { it.kind == "video" }.map { HistoryEntry(it.id, it.id, it.title.orEmpty(),
            it.author.orEmpty(), it.channelId.orEmpty(), it.thumbnail.orEmpty(), it.duration.orEmpty(), it.progressPercent?.div(100f), "History",
            removeToken = it.actions.firstNotNullOfOrNull { action -> action.feedbackToken() }) }, page.nextKey)
    }
    override suspend fun removeFromHistory(feedbackToken: String): RemovedHistoryEntry? {
        if (useDevice()) return device { it.remove(feedbackToken) }
        historyWrite { context, http -> removeWatchHistory(feedbackToken, context, http).requireValue() }
        return null
    }

    override suspend fun restoreHistoryEntry(removed: RemovedHistoryEntry) {
        device { it.restore(removed) }
    }
    override suspend fun setHistoryPaused(paused: Boolean) {
        val operation = LibraryOperation(local)
        if (operation.device) return device { it.pause(paused) }
        try {
            client.desiredStateWrites.setState("history_recording", "paused", paused.toString(),
                checkSource = operation::check,
                write = { context, http ->
                    operation.check()
                    DesiredStateAttempt(setWatchHistory(!paused, context, operation.transport(http)).requireValue(), Unit)
                },
                observe = { context, http ->
                    operation.check()
                    val result = loadWatchHistoryManagement(context, operation.transport(http))
                    if (result.ok) ReadResult(DesiredStateRead(result.value?.paused?.toString(), Unit))
                    else ReadResult(error = result.error)
                }, onConfirmed = { operation.check() })
        } catch (error: MediaException) {
            if (error.failure.kind == RequestFailure.AUTH)
                throw com.hhst.youtubelite.watch.domain.SignInRequiredException()
            throw error
        }
    }
    override suspend fun clearHistory() {
        if (useDevice()) return device { it.clear() }
        historyWrite { context, http -> clearWatchHistory(context, http).requireValue() }
    }
    private suspend fun historyWrite(block: (RequestContext, HttpClient) -> ActionReply) {
        try {
            remote(writing = true) { context, http ->
                if (!context.signedIn) throw com.hhst.youtubelite.watch.domain.SignInRequiredException()
                block(context, http).requireConfirmation()
            }
        } catch (error: MediaException) {
            if (error.failure.kind == RequestFailure.AUTH) {
                if (error.failure.code == "session_changed") throw kotlinx.coroutines.CancellationException("Library session changed")
                throw com.hhst.youtubelite.watch.domain.SignInRequiredException()
            }
            throw error
        }
    }
    override suspend fun playlists(): PlaylistCollectionPage = if (useDevice()) device { it.playlists() } else remote { context, http ->
        val page = loadLibrary(LibrarySection.PLAYLISTS, context, http = http).requireValue()
        PlaylistCollectionPage(page.items.filter { it.kind == "playlist" }.map(MediaItem::playlistSummary), page.nextKey)
    }
    override suspend fun playlistsNext(cursor: String): PlaylistCollectionPage = if (useDevice()) PlaylistCollectionPage(emptyList()) else remote { context, http ->
        val page = loadLibrary(LibrarySection.PLAYLISTS, context, cursor, http = http).requireValue()
        PlaylistCollectionPage(page.items.filter { it.kind == "playlist" }.map(MediaItem::playlistSummary), page.nextKey)
    }
    private suspend fun <T> remote(writing: Boolean = false, block: (RequestContext, HttpClient) -> T): T {
        val operation = LibraryOperation(local)
        if (operation.device) throw kotlinx.coroutines.CancellationException("Library source changed")
        val execute: (RequestContext, HttpClient) -> T = { context, http ->
            operation.check()
            block(context, operation.transport(http)).also { operation.check() }
        }
        return if (writing) client.write(execute) else client.read(execute)
    }

}

class MediaSubscriptionsRepository(private val client: MediaClient,
    private val now: () -> Long = System::currentTimeMillis,
    private val avatars: MediaAvatarDirectory? = null) : SubscriptionsRepository {
    private val menus = DeferredMenuCache(now)
    private val initialBrowse = SharedSubscriptionsBrowse(now)
    // Today/Posts have no server-side lane: the subscriptions browse only filters by params we
    // don't have for those two, so they are hidden rather than offered and failed.
    override val supportedFilters = SubscriptionsFilter.entries.toSet() - SubscriptionsFilter.Today - SubscriptionsFilter.Posts
    override suspend fun subscriptions(filter: SubscriptionsFilter, continuation: String?): SubscriptionsPage =
        subscriptionPage(filter, continuation, null)

    override suspend fun channelSubscriptions(channel: SubscribedChannel, filter: SubscriptionsFilter,
                                             continuation: String?): SubscriptionsPage = subscriptionPage(filter, continuation, channel)

    private suspend fun subscriptionPage(filter: SubscriptionsFilter, continuation: String?,
                                         channel: SubscribedChannel?): SubscriptionsPage {
        if (filter == SubscriptionsFilter.Today || filter == SubscriptionsFilter.Posts)
            throw MediaException(RequestError(RequestFailure.UNSUPPORTED, "subscription_filter_unavailable"))
        val params = channel?.let { it.subscriptionParams ?: subscriptionChannelParams(it.channelId) }
        return if (params == null && continuation == null) initialRead(SharedSubscriptionsBrowse.Consumer.Feed) { context, http, opened ->
            subscriptionPage(filter, channel, opened?.feed?.requireValue()
                ?: loadBrowse(LibrarySection.SUBSCRIPTIONS.browseId, context, http = http).requireValue())
        } else client.readIdempotent { context, http ->
            if (!context.signedIn) throw MediaException(RequestError(RequestFailure.AUTH, "sign_in_required"))
            subscriptionPage(filter, channel, loadBrowse(LibrarySection.SUBSCRIPTIONS.browseId, context, params, continuation, http).requireValue())
        }
    }

    private fun subscriptionPage(filter: SubscriptionsFilter, channel: SubscribedChannel?, page: BrowsePage): SubscriptionsPage {
        val videos = page.items.filter { it.kind == "video" }.map { item ->
            val video = item.feedVideo().let { it.copy(avatar = it.avatar ?: avatars?.cached(it.channelId)) }
            if (channel != null && (video.channelId.isBlank() || video.channelId == channel.channelId)) video.copy(channelId = channel.channelId,
                author = video.author.ifBlank { channel.name }, avatar = video.avatar ?: channel.avatar) else video
        }.filter { channel == null || it.channelId == channel.channelId }
        return SubscriptionsPage(when (filter) {
            SubscriptionsFilter.Live -> videos.filter { it.live }
            SubscriptionsFilter.Unwatched -> videos.filter { it.progress == null || it.progress == 0f }
            SubscriptionsFilter.ContinueWatching -> videos.filter { (it.progress ?: 0f) > 0f }
            else -> videos
        }, emptyList(), page.nextKey)
    }

    // The channel guide is its own read: a rejected guide request must not erase a usable feed.
    // The directory pages through the channels browse while the server keeps returning keys.
    override suspend fun subscribedChannelsPage(cursor: String?): com.hhst.youtubelite.subscriptions.domain.ChannelDirectoryPage =
        if (cursor == null) initialRead(SharedSubscriptionsBrowse.Consumer.Directory) { context, http, opened ->
            directoryPage(if (opened == null) loadSubscribedChannelsPage(context, http = http).requireValue()
                else loadSubscribedChannelsPage(context, opened, http).requireValue())
        } else client.readIdempotent { context, http ->
            directoryPage(loadSubscribedChannelsPage(context, cursor, http).requireValue())
        }

    private fun directoryPage(page: com.liskovsoft.youtubeapi.videoinfo.V2.ChannelDirectoryPage) =
        com.hhst.youtubelite.subscriptions.domain.ChannelDirectoryPage(page.channels.map {
                SubscribedChannel(it.id, it.title.orEmpty(), it.thumbnail.orEmpty(), "", hasNewContent = it.hasNewContent,
                    subscriptionParams = it.params)
            }, page.nextKey)

    private sealed interface InitialRead<out T> {
        data class Value<T>(val value: T) : InitialRead<T>
        data class Waiting(val ticket: SharedSubscriptionsBrowse.Ticket) : InitialRead<Nothing>
    }

    private suspend fun <T> initialRead(consumer: SharedSubscriptionsBrowse.Consumer,
        read: (RequestContext, HttpClient, SubscriptionsBrowse?) -> T): T {
        val attempt = client.readIdempotent { context, http ->
            if (!context.signedIn) throw MediaException(RequestError(RequestFailure.AUTH, "sign_in_required"))
            if (context.accessToken == null) return@readIdempotent InitialRead.Value(read(context, http, null))
            val ticket = initialBrowse.acquire(consumer, context)
            if (!ticket.owner) return@readIdempotent InitialRead.Waiting(ticket)
            val opened = try {
                loadSubscriptionsBrowse(context, http).requireValue().also { page ->
                    avatars?.seed(page.channels.mapNotNull { channel ->
                        channel.thumbnail?.takeIf(String::isNotBlank)?.let { channel.id to it }
                    }.toMap())
                    initialBrowse.complete(ticket, page)
                }
            } catch (error: Throwable) {
                initialBrowse.failed(ticket)
                throw error
            }
            InitialRead.Value(read(context, http, opened))
        }
        return when (attempt) {
            is InitialRead.Value -> attempt.value
            is InitialRead.Waiting -> {
                // Await outside MediaClient: the second consumer holds no bounded
                // media worker while the shared response is still on the wire.
                val opened = attempt.ticket.result.await()
                client.readIdempotent { context, http ->
                    if (context != attempt.ticket.context)
                        throw MediaException(RequestError(RequestFailure.AUTH, "session_changed"))
                    read(context, http, opened ?: loadSubscriptionsBrowse(context, http).requireValue())
                }
            }
        }
    }

    override suspend fun menu(video: com.hhst.youtubelite.home.domain.FeedItem.Video): com.hhst.youtubelite.home.domain.FeedItem.Video {
        val endpoint = video.menuEndpoint ?: return video
        return client.readIdempotent { context, http ->
            if (!context.signedIn || video.feedbackGeneration != context.generation)
                throw kotlinx.coroutines.CancellationException("Session changed")
            val key = DeferredMenuCache.Key(context.accountId, context.onBehalfOfUser, context.generation, video.id, endpoint)
            val capabilities = menus.get(key) ?: loadMenu(MenuEndpoint(endpoint.panelId, endpoint.params), context, http)
                .requireValue().let { actions ->
                    DeferredMenuCache.Capabilities(now(), actions.firstOrNull { it.kind == MenuActionKind.NotInterested }?.kindToken,
                        actions.firstOrNull { it.kind == MenuActionKind.DontRecommendChannel }?.kindToken)
                }.also { loaded -> menus.put(key, loaded) }
            video.copy(notInterestedToken = capabilities.notInterested, dontRecommendToken = capabilities.dontRecommend,
                feedbackGeneration = context.generation)
        }
    }

    override suspend fun feedback(video: com.hhst.youtubelite.home.domain.FeedItem.Video, action: com.hhst.youtubelite.home.domain.FeedMenuAction) {
        client.write { context, http ->
            if (!context.signedIn) throw com.hhst.youtubelite.watch.domain.SignInRequiredException()
            val token = when (action) {
                com.hhst.youtubelite.home.domain.FeedMenuAction.NotInterested -> video.notInterestedToken
                com.hhst.youtubelite.home.domain.FeedMenuAction.DontRecommend -> video.dontRecommendToken
                else -> null
            }
            if (token.isNullOrBlank() || video.feedbackGeneration != context.generation)
                throw com.hhst.youtubelite.watch.domain.UnconfirmedActionException()
            sendFeedback(token, context, http).requireValue()
        }
    }
}

class MediaExploreRepository(private val client: MediaClient) : ExploreRepository {
    override suspend fun explore(category: ExploreCategory) = client.read { context, http ->
        loadHome(section(category), context, http = http).requireValue().toExplorePage(category)
    }

    override suspend fun exploreNext(category: ExploreCategory, key: String) = client.read { context, http ->
        loadHome(section(category), context, key, http).requireValue().toExplorePage(category)
    }

    override suspend fun exploreShelf(category: ExploreCategory, key: String) = client.read { context, http ->
        // A shelf continuation answers with that shelf's next items; the server answers
        // a spent shelf with a bookkeeping-only envelope, which parses as an empty page.
        val page = loadHome(section(category), context, key, http).requireValue()
        val group = page.groups.singleOrNull()
        val source = group?.items ?: page.items
        val items = source.filter { it.kind == "video" }.map(MediaItem::feedVideo)
        ExploreSection(null, items, group?.nextKey ?: page.nextKey,
            playlists = source.filter { it.kind == "playlist" }.map(MediaItem::playlistSummary))
    }

    private fun section(category: ExploreCategory) = HomeSection.valueOf(category.name.uppercase())

    private fun BrowsePage.toExplorePage(category: ExploreCategory): ExplorePage {
        // Shelves stay carousels; the flat list keeps the fallback grid useful.
        val sections = groups.mapNotNull { group ->
            val videos = group.items.filter { it.kind == "video" }.map(MediaItem::feedVideo)
            val playlists = group.items.filter { it.kind == "playlist" }.map(MediaItem::playlistSummary)
            ExploreSection(group.title, videos, group.nextKey, playlists = playlists)
                .takeIf { videos.isNotEmpty() || playlists.isNotEmpty() || group.nextKey != null }
        }
        return ExplorePage(category,
            sections.flatMap { it.videos }.ifEmpty { items.filter { it.kind == "video" }.map(MediaItem::feedVideo) },
            sections, nextKey, sections.flatMap { it.playlists }
                .ifEmpty { items.filter { it.kind == "playlist" }.map(MediaItem::playlistSummary) })
    }
}

class MediaShortsRepository(private val client: MediaClient, private val startId: String? = null, private val channelId: String? = null) : ShortsRepository {
    override suspend fun shorts(): com.hhst.youtubelite.shorts.domain.ShortsPage {
        val started = android.os.SystemClock.elapsedRealtime()
        // A known id can attach its video surface immediately. The playback pipeline decides
        // playability; metadata failure must neither hold that surface nor replace the seed.
        val page = if (startId != null) ShortsPage(listOf(ShortItem(
            startId, "", "", "", "https://i.ytimg.com/vi/$startId/hqdefault.jpg", "", "", "",
        )), INITIAL_CURSOR) else client.read { context, http -> shortsPage(context, http, null) }
        com.hhst.youtubelite.diagnostics.AppLog.event(com.hhst.youtubelite.diagnostics.AppLog.Category.PLAYER,
            "shorts_seed_read", mapOf("ms" to (android.os.SystemClock.elapsedRealtime() - started),
                "seeded" to (startId != null), "items" to page.items.size))
        return page
    }

    override suspend fun shortsNext(cursor: String): ShortsPage = client.read { context, http ->
        shortsPage(context, http, cursor.takeUnless { it == INITIAL_CURSOR })
    }

    /** One watch read per item; the ViewModel bounds and cancels these. */
    override suspend fun hydrate(itemId: String): ShortDetail? = client.read { context, http ->
        val page = loadWatch(itemId, context, http = http).value ?: return@read null
        val details = loadPlayerDetails(itemId, context, http).value
        val title = page.title ?: details?.title ?: return@read null
        ShortDetail(
            ShortItem(itemId, title, page.author ?: details?.author.orEmpty(),
                page.channelId ?: details?.channelId.orEmpty(), details?.thumbnail.orEmpty(),
                page.viewCount ?: details?.viewCount?.let {
                    java.text.NumberFormat.getIntegerInstance(java.util.Locale.US).format(it) + " views"
                }.orEmpty(), page.likeCount.orEmpty(), page.commentCount.orEmpty(), page.authorThumbnail.orEmpty()),
            page.description?.takeIf { it.isNotBlank() },
            page.subscribed,
            details?.tracking,
            context.generation,
            when (page.rating) {
                "LIKE" -> VideoRating.Like
                "DISLIKE" -> VideoRating.Dislike
                "INDIFFERENT" -> VideoRating.None
                else -> null
            },
        )
    }

    private fun shortsPage(context: RequestContext, http: HttpClient, cursor: String?): ShortsPage {
        val page = pageFor(cursor, context, http)
        return ShortsPage(page.items.filter { it.kind in setOf("video", "short") }.map {
            ShortItem(it.id, it.title.orEmpty(), it.author.orEmpty(), it.channelId.orEmpty(), it.thumbnail.orEmpty(), "", "", "", it.authorThumbnail.orEmpty())
        }, page.nextKey)
    }

    private fun pageFor(cursor: String?, context: RequestContext, http: HttpClient): BrowsePage {
        if (channelId == null) return loadHome(HomeSection.SHORTS, context, cursor, http).requireValue()
        return if (cursor != null) loadChannel(channelId, context, continuation = cursor, http = http).requireValue().page
        else {
            val channel = loadChannel(channelId, context, http = http).requireValue()
            val tab = channel.page.tabs.firstOrNull { it.title.equals("Shorts", true) }
            if (tab == null) channel.page.copy(items = emptyList()) else loadChannel(channelId, context, tab.params, http = http).requireValue().page
        }
    }

    private companion object { const val INITIAL_CURSOR = "local:shorts-initial" }
}
