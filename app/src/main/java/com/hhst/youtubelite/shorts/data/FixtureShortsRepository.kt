package com.hhst.youtubelite.shorts.data

import com.hhst.youtubelite.shorts.domain.ShortItem
import com.hhst.youtubelite.shorts.domain.ShortsPage
import kotlinx.coroutines.delay

class FixtureShortsRepository(private val scenario: ShortsScenario = ShortsScenario.Content,
    private val channelId: String? = null,
) : ShortsRepository {
    override suspend fun shorts(): ShortsPage {
        delay(140)
        if (scenario == ShortsScenario.Error) throw java.io.IOException("Local error scenario")
        return ShortsPage(if (scenario == ShortsScenario.Empty) emptyList() else channelId?.let { id ->
            val profile = com.hhst.youtubelite.channel.data.FixtureChannelRepository.profile(id)
            com.hhst.youtubelite.channel.data.FixtureChannelRepository.sampleShorts().map { item ->
                ShortItem(item.id, item.title, profile.name, id, item.artwork, item.views, "1.2K", "42")
            }
        } ?: sample())
    }

    companion object {
        /** The same shorts the home shelf lists, one stable id per item. */
        fun sample(): List<ShortItem> = listOf(
            ShortItem("short-1", "A whole world in a single frame", "Blender", "blender", "bunny", "128K views", "12K", "214"),
            ShortItem("short-2", "The art behind the story", "Blender", "blender", "dream", "92K views", "8.1K", "96"),
            ShortItem("short-3", "Behind the scenes", "Studio", "studio", "dream", "240K views", "19K", "431"),
            ShortItem("short-4", "Every detail tells a story", "Blender", "blender", "bunny", "38K views", "3.4K", "58"),
            ShortItem("short-5", "A moment in the mountains", "Studio", "studio", "dream", "75K views", "6.8K", "120"),
            ShortItem("short-6", "From sketches to the screen", "Blender", "blender", "dream", "12K views", "1.1K", "31"),
            ShortItem("short-7", "A little adventure", "Studio", "studio", "bunny", "96K views", "9.2K", "187"),
            ShortItem("short-8", "Finding the perfect light", "Blender", "blender", "dream", "53K views", "4.7K", "83"),
        )
    }
}

enum class ShortsScenario(val argument: String) {
    Content("content"), Loading("loading"), Empty("empty"), Error("error");

    companion object { fun parse(value: String?) = entries.firstOrNull { it.argument == value } ?: Content }
}
