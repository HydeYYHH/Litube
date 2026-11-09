package com.hhst.youtubelite.explore.data

import com.hhst.youtubelite.explore.domain.ExploreCategory
import com.hhst.youtubelite.explore.domain.ExplorePage
import com.hhst.youtubelite.explore.domain.ExploreSection
import com.hhst.youtubelite.home.domain.FeedItem
import kotlinx.coroutines.delay

/** All artwork and metadata are packaged locally. This class has no network dependency. */
class FixtureExploreRepository(private val scenario: ExploreScenario = ExploreScenario.Content) : ExploreRepository {
    override suspend fun explore(category: ExploreCategory): ExplorePage {
        delay(140)
        if (scenario == ExploreScenario.Error) throw java.io.IOException("Local error scenario")
        if (scenario == ExploreScenario.Sections) {
            // Two shelves with live cursors, so shelf and page appends are exercisable offline.
            val shelves = (0..1).map { shelf ->
                ExploreSection("Shelf ${shelf + 1}", sample(category).take(3).map {
                    it.copy(id = "${category.name.lowercase()}-s$shelf-${it.id.substringAfterLast('-')}")
                }, nextKey = "fixture:shelf-$shelf:1")
            }
            return ExplorePage(category, shelves.flatMap { it.videos }, shelves, nextKey = "fixture:page:1")
        }
        // The plain content scenario carries no cursor, so screenshot baselines never see a tail.
        if (scenario == ExploreScenario.Paged) return ExplorePage(category, sample(category), nextKey = "fixture:page:1")
        return ExplorePage(category, sample(category))
    }

    /** One canned second page per category, then the list ends. */
    override suspend fun exploreNext(category: ExploreCategory, key: String): ExplorePage {
        delay(120)
        if (scenario == ExploreScenario.Error) throw java.io.IOException("Local error scenario")
        val more = sample(category).map { it.copy(id = "${it.id}-p2") }
        if (scenario == ExploreScenario.Sections) {
            return ExplorePage(category, more.take(2), listOf(ExploreSection("More ${category.label}", more.take(2))), nextKey = null)
        }
        return ExplorePage(category, more, nextKey = null)
    }

    /** One canned shelf page per shelf cursor, then the shelf ends. */
    override suspend fun exploreShelf(category: ExploreCategory, key: String): ExploreSection {
        delay(120)
        if (scenario == ExploreScenario.Error) throw java.io.IOException("Local error scenario")
        // Ids carry the shelf segment so two shelves never mint colliding identities.
        val shelf = "${key.substringBeforeLast(':').substringAfterLast('-')}-${key.substringAfterLast(':')}"
        val more = sample(category).take(2).map { it.copy(id = "${it.id}-s$shelf") }
        return ExploreSection(null, more, nextKey = null)
    }

    companion object {
        /** One stable, category-scoped id per item; the underlying media stays the shared fixture set. */
        fun sample(category: ExploreCategory) = when (category) {
            ExploreCategory.Music -> videos("music", "1.2M views", "3 days ago")
            ExploreCategory.Gaming -> videos("gaming", "845K views", "1 week ago")
            ExploreCategory.News -> videos("news", "212K views", "9 hours ago")
            ExploreCategory.Sports -> videos("sports", "1.8M views", "2 weeks ago")
        }

        private fun videos(prefix: String, views: String, age: String): List<FeedItem.Video> =
            com.hhst.youtubelite.subscriptions.data.FixtureSubscriptionsRepository.sampleVideos()
                .take(4)
                .mapIndexed { index, video ->
                    val isLive = prefix == "news" && index == 0
                    video.copy(id = "$prefix-${video.id}", metadata = views, age = age,
                        duration = if (isLive) "LIVE" else if (video.live) "18:20" else video.duration,
                        live = isLive,
                        // The channel's own artwork, so cards never borrow the video still as an avatar.
                        avatar = when (video.channelId) { "studio" -> "dream"; "openmovies" -> "sintel"; else -> "bunny" })
                }
    }
}

enum class ExploreScenario(val argument: String) {
    Content("content"), Loading("loading"), Error("error"), Paged("paged"), Sections("sections");

    companion object { fun parse(value: String?) = entries.firstOrNull { it.argument == value } ?: Content }
}
