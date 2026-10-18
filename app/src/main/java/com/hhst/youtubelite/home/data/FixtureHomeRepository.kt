package com.hhst.youtubelite.home.data

import com.hhst.youtubelite.home.domain.*
import kotlinx.coroutines.delay

/** All artwork and metadata are packaged locally. This class has no network dependency. */
class FixtureHomeRepository(private val scenario: HomeScenario = HomeScenario.Empty) : HomeRepository {
    override suspend fun loadHome(filter: String, continuation: String?): HomePage {
        delay(160)
        if (scenario == HomeScenario.Error || scenario == HomeScenario.PaginationError && continuation != null) {
            throw java.io.IOException("Local error scenario")
        }
        if (scenario == HomeScenario.Empty) return HomePage(emptyList(), listOf(HomeContent.Empty().title))
        val page = samplePage()
        val filtered = when (filter) {
            "Mixes", "Music" -> page.items.filterIsInstance<FeedItem.Mix>()
            "Live" -> page.items.filterIsInstance<FeedItem.Video>().filter { it.live }
            else -> page.items
        }
        return HomePage(filtered.map { if (continuation == null) it else duplicate(it) }, nextKey = if (continuation == null) "fixture-page-2" else null)
    }

    private fun duplicate(item: FeedItem): FeedItem = when (item) {
        is FeedItem.Video -> item.copy(id = "${item.id}-page2")
        is FeedItem.Mix -> item.copy(id = "${item.id}-page2")
        is FeedItem.Shorts -> item.copy(id = "${item.id}-page2")
        is FeedItem.Post -> item.copy(id = "${item.id}-page2")
    }

    companion object {
        fun samplePage() = HomePage(listOf(
            FeedItem.Video("bunny", "Big Buck Bunny | A short film by the Blender Foundation", "Blender", "12M views · 2 years ago", "bunny", "9:56", "blender", progress = 0.28f),
            FeedItem.Video("dream", "Elephants Dream — the world's first open movie", "Blender Studio", "4.8M views · 3 years ago", "dream", "10:53", "studio"),
            FeedItem.Mix("mix", "Mix – A little room to breathe", "Blender Studio, Open Movies, and more", "sintel"),
            FeedItem.Video("sintel", "Sintel | An adventure beyond the mountains", "Blender", "7.2M views · 4 years ago", "sintel", "14:48", "blender"),
            FeedItem.Shorts("shorts", listOf(
                FeedItem.Video("short-1", "A whole world in a single frame", "Blender", "128K views", "bunny", "0:38", "blender"),
                FeedItem.Video("short-2", "The art behind the story", "Blender", "92K views", "sintel", "0:24", "blender"),
                FeedItem.Video("short-3", "Behind the scenes", "Studio", "240K views", "dream", "0:41", "studio"),
            )),
            FeedItem.Video("live", "Open movie studio — animation and art", "Blender Studio", "1.2K watching", "dream", "LIVE", "studio", live = true),
            FeedItem.Post("post", "Blender Studio", "2 days ago", "A new story starts with a little curiosity. Which open movie would you watch again?", "sintel"),
        ), nextKey = "fixture-page-2")
    }
}

/** Debug intent names are stable so previews and instrumentation use the same fixtures. */
enum class HomeScenario(val argument: String) {
    Empty("empty"), Feed("feed"), Loading("loading"), Refreshing("refreshing"),
    PaginationLoading("pagination-loading"), PaginationError("pagination-error"), Error("error");

    fun initialContent(): HomeContent = when (this) {
        Empty -> HomeContent.Empty()
        Loading -> HomeContent.Loading
        Error -> HomeContent.Failed
        else -> HomeContent.Feed(FixtureHomeRepository.samplePage(), refreshing = this == Refreshing,
            append = when (this) { PaginationLoading -> AppendState.Loading; PaginationError -> AppendState.Failed; else -> AppendState.Idle })
    }
    companion object { fun parse(value: String?) = entries.firstOrNull { it.argument == value } ?: Empty }
}
