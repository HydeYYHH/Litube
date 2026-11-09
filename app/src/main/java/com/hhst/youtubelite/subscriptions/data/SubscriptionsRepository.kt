package com.hhst.youtubelite.subscriptions.data

import com.hhst.youtubelite.home.domain.FeedItem
import com.hhst.youtubelite.home.domain.FeedMenuAction
import com.hhst.youtubelite.subscriptions.domain.*

interface SubscriptionsRepository {
    /** Filters the backend can actually serve; fixture builds serve everything. */
    val supportedFilters: Set<SubscriptionsFilter> get() = SubscriptionsFilter.entries.toSet()
    suspend fun subscriptions(filter: SubscriptionsFilter, continuation: String? = null): SubscriptionsPage

    /** Channel-scoped subscription feed. Production uses the channel's server filter and cursor. */
    suspend fun channelSubscriptions(channel: SubscribedChannel, filter: SubscriptionsFilter,
                                     continuation: String? = null): SubscriptionsPage {
        val page = subscriptions(filter, continuation)
        return page.copy(items = page.items.filter { it.channelId == channel.channelId })
    }

    /** Independent channel read so a guide failure cannot erase the feed. */
    suspend fun subscribedChannels(): List<SubscribedChannel> = emptyList()

    /**
     * Paged subscribed-channel directory: the guide read first, then [cursor]
     * continuations while the server keeps returning keys. [null] starts the read.
     */
    suspend fun subscribedChannelsPage(cursor: String? = null): ChannelDirectoryPage =
        ChannelDirectoryPage(subscribedChannels(), null)

    /**
     * Resolves one card's deferred get_panel capabilities when it carries an endpoint;
     * cards with inline tokens return unchanged.
     */
    suspend fun menu(video: FeedItem.Video): FeedItem.Video = video

    /** Sends the card's own feedback token for a server-classified menu operation. */
    suspend fun feedback(video: FeedItem.Video, action: FeedMenuAction) {}
}
