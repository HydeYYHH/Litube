package com.hhst.youtubelite.channel.data

import com.hhst.youtubelite.channel.domain.ChannelContent
import com.hhst.youtubelite.channel.domain.ChannelPage
import com.hhst.youtubelite.channel.domain.ChannelProfile
import com.hhst.youtubelite.channel.domain.ChannelRepository
import com.hhst.youtubelite.channel.domain.ChannelVideo
import kotlinx.coroutines.delay

/** All artwork and metadata are packaged locally. This class has no network dependency. */
class FixtureChannelRepository(private val scenario: ChannelScenario = ChannelScenario.Content) : ChannelRepository {
    override suspend fun channel(channelId: String): ChannelPage {
        delay(160)
        if (scenario == ChannelScenario.Error) throw java.io.IOException("Local error scenario")
        if (scenario == ChannelScenario.Empty) return ChannelPage(profile(), emptyList())
        return ChannelPage(profile(), sampleVideos())
    }

    companion object {
        /** Fixed English sample; metadata is synthetic and every asset is bundled locally. */
        fun profile() = ChannelProfile(
            name = "Blender",
            handle = "@blender",
            avatar = "bunny",
            banner = "sintel",
            subscribers = "30M subscribers",
            // Short enough that the inline "...more" tail renders at every sampled line budget.
            description = "Blender is the free and open source 3D creation suite for films.",
            links = listOf("blender.org", "studio.blender.org"),
        )

        /** The Home tab lists exactly these (homeShelf) videos; the Videos tab shows every row. */
        fun sampleVideos() = listOf(
            ChannelVideo("bunny", "Big Buck Bunny | A short film by the Blender Foundation", "12M views", "2 years ago", "9:56", "bunny", homeShelf = true),
            ChannelVideo("dream", "Elephants Dream — the world's first open movie", "4.8M views", "3 years ago", "10:53", "dream", homeShelf = true),
            ChannelVideo("sintel", "Sintel | An adventure beyond the mountains", "7.2M views", "4 years ago", "14:48", "sintel", homeShelf = true),
            ChannelVideo("trailers", "Making the crops: studio shorts from the archive", "940K views", "5 months ago", "4:31", "dream"),
            ChannelVideo("behind", "Behind the mountains: painting Sintel's story", "1.1M views", "1 year ago", "8:20", "sintel"),
            ChannelVideo("bunny-remastered", "Big Buck Bunny remastered — a night in the forest", "36M views", "6 years ago", "9:56", "bunny"),
            ChannelVideo("live", "Open movie studio — animation and art", "1.2K watching", "", "LIVE", "dream", live = true),
        )
    }
}

/** Debug intent names are stable so previews and instrumentation use the same fixtures. */
enum class ChannelScenario(val argument: String) {
    Content("content"), Empty("empty"), Loading("loading"), Error("error");

    fun initialContent(): ChannelContent = when (this) {
        Content, Loading -> ChannelContent.Loading
        Empty -> ChannelContent.Ready(ChannelPage(FixtureChannelRepository.profile(), emptyList()))
        Error -> ChannelContent.Failed
    }

    fun loadsContent(): Boolean = this == Content

    companion object { fun parse(value: String?) = entries.firstOrNull { it.argument == value } ?: Content }
}
