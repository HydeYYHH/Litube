package com.hhst.youtubelite.subscriptions.data

import com.hhst.youtubelite.home.domain.FeedItem
import com.hhst.youtubelite.library.domain.SignInPrompt
import com.hhst.youtubelite.library.data.SignedOutPrompts
import com.hhst.youtubelite.subscriptions.domain.*
import kotlinx.coroutines.delay

/** All artwork and metadata are packaged locally. This class has no network dependency. */
class FixtureSubscriptionsRepository(private val scenario: SubscriptionsScenario = SubscriptionsScenario.Content) : SubscriptionsRepository {
    override suspend fun subscriptions(filter: SubscriptionsFilter, continuation: String?): SubscriptionsPage {
        delay(140)
        if (scenario == SubscriptionsScenario.Error || scenario == SubscriptionsScenario.PaginationError && continuation != null) {
            throw java.io.IOException("Local error scenario")
        }
        val items = sampleVideos().filter { item ->
            when (filter) {
                SubscriptionsFilter.All -> true
                SubscriptionsFilter.Live -> item.live
                SubscriptionsFilter.Unwatched -> item.progress == null || item.progress < 0.05f
                SubscriptionsFilter.Today -> true
                SubscriptionsFilter.ContinueWatching -> item.progress != null && item.progress in 0.05f..0.95f
                SubscriptionsFilter.Posts -> false
            }
        }
        return SubscriptionsPage(items, channels(), nextKey = if (continuation == null) "fixture-page-2" else null)
    }

    override suspend fun subscribedChannels(): List<SubscribedChannel> {
        delay(140)
        return channels()
    }

    override suspend fun subscribedChannelsPage(cursor: String?): ChannelDirectoryPage {
        delay(140)
        return ChannelDirectoryPage(channels(), null)
    }

    companion object {
        fun sampleVideos(): List<FeedItem.Video> {
            val page = com.hhst.youtubelite.home.data.FixtureHomeRepository.samplePage()
            val avatars = channels().associate { it.channelId to it.avatar }
            return page.items.filterIsInstance<FeedItem.Video>().map { video ->
                video.copy(avatar = video.avatar?.takeIf(String::isNotBlank) ?: avatars[video.channelId])
            }
        }

        fun channels() = listOf(
            SubscribedChannel("blender", "Blender", "bunny", "12 videos · New today", hasNewContent = true),
            SubscribedChannel("studio", "Blender Studio", "dream", "8 videos · New this week", hasNewContent = true),
            SubscribedChannel("openmovies", "Open Movies", "sintel", "31 videos"),
        )
    }
}

enum class SubscriptionsFilter(val chip: SubscriptionsChip) {
    All(SubscriptionsChip.All), Today(SubscriptionsChip.Today), ContinueWatching(SubscriptionsChip.ContinueWatching),
    Unwatched(SubscriptionsChip.Unwatched), Live(SubscriptionsChip.Live), Posts(SubscriptionsChip.Posts);

    companion object { fun from(chip: SubscriptionsChip) = entries.firstOrNull { it.chip == chip } ?: All }
}

enum class SubscriptionsScenario(val argument: String) {
    Content("content"), Loading("loading"), Error("error"), PaginationLoading("pagination-loading"), PaginationError("pagination-error");

    fun initialContent(prompt: SignInPrompt): SubscriptionsContent = when (this) {
        Loading -> SubscriptionsContent.Loading
        Error -> SubscriptionsContent.Failed
        PaginationLoading -> SubscriptionsContent.Feed(page(null), com.hhst.youtubelite.home.domain.AppendState.Loading)
        PaginationError -> SubscriptionsContent.Feed(page(null), com.hhst.youtubelite.home.domain.AppendState.Failed)
        Content -> SubscriptionsContent.Feed(page("fixture-page-2"))
    }

    companion object {
        fun parse(value: String?) = entries.firstOrNull { it.argument == value } ?: Content
        fun page(nextKey: String?) = SubscriptionsPage(FixtureSubscriptionsRepository.sampleVideos(), FixtureSubscriptionsRepository.channels(), nextKey = nextKey)
    }
}

fun subscriptionsSignedOutPrompt(): SignInPrompt = SignedOutPrompts.forContent("subscriptions")
