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

class MediaLibraryRepository(private val client: MediaClient) : LibraryRepository {
    override suspend fun you(): YouPage {
        val account = client.read { context, http ->
            val profile = loadAccounts(context, http).requireValue().firstOrNull { it.selected && !it.disabled }
                ?: throw MediaException(RequestError(RequestFailure.AUTH, "account_unavailable"))
            AccountProfile(profile.name.orEmpty(), profile.email.orEmpty(), profile.photo.orEmpty(), profile.pageId.orEmpty())
        }
        return YouPage(account, history().entries, playlists())
    }
    override suspend fun history(): HistoryPage = client.read { context, http ->
        val page = loadLibrary(LibrarySection.HISTORY, context, http = http).requireValue()
        HistoryPage(page.items.filter { it.kind == "video" }.map { HistoryEntry(it.id, it.id, it.title.orEmpty(),
            it.author.orEmpty(), it.channelId.orEmpty(), it.thumbnail.orEmpty(), it.duration.orEmpty(), it.progressPercent?.div(100f), "History") })
    }
    override suspend fun playlists() = client.read { context, http ->
        loadLibrary(LibrarySection.PLAYLISTS, context, http = http).requireValue().items.filter { it.kind == "playlist" }.map(MediaItem::playlistSummary)
    }
}

class MediaSubscriptionsRepository(private val client: MediaClient) : SubscriptionsRepository {
    override suspend fun subscriptions(filter: SubscriptionsFilter, continuation: String?): SubscriptionsPage = client.read { context, http ->
        if (filter == SubscriptionsFilter.Today || filter == SubscriptionsFilter.Posts)
            throw MediaException(RequestError(RequestFailure.UNSUPPORTED, "subscription_filter_unavailable"))
        val page = loadLibrary(LibrarySection.SUBSCRIPTIONS, context, continuation, http).requireValue()
        val videos = page.items.filter { it.kind == "video" }.map(MediaItem::feedVideo)
        val channels = if (continuation == null) loadSubscribedChannels(context, http).requireValue().map {
            SubscribedChannel(it.id, it.title.orEmpty(), it.thumbnail.orEmpty(), "")
        } else emptyList()
        SubscriptionsPage(when (filter) {
            SubscriptionsFilter.Live -> videos.filter { it.live }
            SubscriptionsFilter.Unwatched -> videos.filter { it.progress == null || it.progress == 0f }
            SubscriptionsFilter.ContinueWatching -> videos.filter { (it.progress ?: 0f) > 0f }
            else -> videos
        }, channels, page.nextKey)
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
        val page = if (channelId == null) loadHome(HomeSection.SHORTS, context, http = http).requireValue() else {
            val channel = loadChannel(channelId, context, http = http).requireValue()
            val tab = channel.page.tabs.firstOrNull { it.title.equals("Shorts", true) }
            if (tab == null) channel.page.copy(items = emptyList()) else loadChannel(channelId, context, tab.params, http = http).requireValue().page
        }
        val items = page.items.filter { it.kind in setOf("video", "short") }.map {
            ShortItem(it.id, it.title.orEmpty(), it.author.orEmpty(), it.channelId.orEmpty(), it.thumbnail.orEmpty(), "", "", "")
        }.toMutableList()
        if (startId != null && items.none { it.id == startId }) {
            val video = loadWatch(startId, context, http = http).requireValue()
            items.add(0, ShortItem(startId, video.title.orEmpty(), video.author.orEmpty(), video.channelId.orEmpty(),
                "https://i.ytimg.com/vi/$startId/hqdefault.jpg", "", "", ""))
        }
        ShortsPage(items)
    }
}
