package com.hhst.youtubelite.explore.data

import com.hhst.youtubelite.explore.domain.ExploreCategory
import com.hhst.youtubelite.explore.domain.ExplorePage
import kotlinx.coroutines.delay

/** All artwork and metadata are packaged locally. This class has no network dependency. */
class FixtureExploreRepository(private val scenario: ExploreScenario = ExploreScenario.Content) : ExploreRepository {
    override suspend fun explore(category: ExploreCategory): ExplorePage {
        delay(140)
        if (scenario == ExploreScenario.Error) throw java.io.IOException("Local error scenario")
        return ExplorePage(category, sample(category))
    }

    companion object {
        /** One stable, category-scoped id per item; the underlying media stays the shared fixture set. */
        fun sample(category: ExploreCategory) = when (category) {
            ExploreCategory.Music -> videos("music", "1.2M views", "3 days ago")
            ExploreCategory.Gaming -> videos("gaming", "845K views", "1 week ago")
            ExploreCategory.News -> videos("news", "212K views", "9 hours ago")
            ExploreCategory.Sports -> videos("sports", "1.8M views", "2 weeks ago")
        }

        private fun videos(prefix: String, views: String, age: String) =
            com.hhst.youtubelite.subscriptions.data.FixtureSubscriptionsRepository.sampleVideos()
                .take(4)
                .mapIndexed { index, video ->
                    val isLive = prefix == "news" && index == 0
                    video.copy(id = "$prefix-${video.id}", metadata = views, age = age,
                        duration = if (isLive) "LIVE" else if (video.live) "18:20" else video.duration,
                        live = isLive)
                }
    }
}

interface ExploreRepository {
    suspend fun explore(category: ExploreCategory): ExplorePage
}

enum class ExploreScenario(val argument: String) {
    Content("content"), Loading("loading"), Error("error");

    companion object { fun parse(value: String?) = entries.firstOrNull { it.argument == value } ?: Content }
}
