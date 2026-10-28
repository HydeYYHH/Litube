package com.hhst.youtubelite.subscriptions.data

import com.hhst.youtubelite.subscriptions.domain.*

interface SubscriptionsRepository {
    /** Filters the backend can actually serve; fixture builds serve everything. */
    val supportedFilters: Set<SubscriptionsFilter> get() = SubscriptionsFilter.entries.toSet()
    suspend fun subscriptions(filter: SubscriptionsFilter, continuation: String? = null): SubscriptionsPage

    /** Independent channel read so a guide failure cannot erase the feed. */
    suspend fun subscribedChannels(): List<SubscribedChannel> = emptyList()
}
