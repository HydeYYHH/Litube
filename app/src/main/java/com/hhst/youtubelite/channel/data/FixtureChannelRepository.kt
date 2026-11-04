package com.hhst.youtubelite.channel.data

import com.hhst.youtubelite.channel.domain.ChannelContent
import com.hhst.youtubelite.channel.domain.ChannelLivePage
import com.hhst.youtubelite.channel.domain.ChannelLiveVideo
import com.hhst.youtubelite.channel.domain.ChannelPage
import com.hhst.youtubelite.channel.domain.ChannelProfile
import com.hhst.youtubelite.channel.domain.ChannelRepository
import com.hhst.youtubelite.channel.domain.ChannelShort
import com.hhst.youtubelite.channel.domain.ChannelShortsPage
import com.hhst.youtubelite.channel.domain.ChannelVideo
import com.hhst.youtubelite.post.data.FixturePostRepository
import com.hhst.youtubelite.post.domain.ChannelPostsPage
import com.hhst.youtubelite.post.domain.PostSummary
import kotlinx.coroutines.delay

class FixtureChannelRepository(private val scenario: ChannelScenario = ChannelScenario.Content) : ChannelRepository {
    override suspend fun channel(channelId: String): ChannelPage {
        delay(160)
        if (scenario == ChannelScenario.Error) throw java.io.IOException("Local error scenario")
        if (scenario == ChannelScenario.Empty) return ChannelPage(profile(channelId).copy(videoCount = "0 videos"), emptyList())
        return samplePage(channelId)
    }

    override suspend fun shorts(channelId: String): ChannelShortsPage {
        delay(160)
        if (scenario == ChannelScenario.Error) throw java.io.IOException("Local error scenario")
        if (scenario == ChannelScenario.Empty) return ChannelShortsPage(emptyList())
        return ChannelShortsPage(sampleShorts())
    }

    override suspend fun live(channelId: String): ChannelLivePage {
        delay(160)
        if (scenario == ChannelScenario.Error) throw java.io.IOException("Local error scenario")
        if (scenario == ChannelScenario.Empty) return ChannelLivePage()
        return sampleLive()
    }

    override suspend fun posts(channelId: String): ChannelPostsPage {
        delay(160)
        if (scenario == ChannelScenario.Error) throw java.io.IOException("Local error scenario")
        if (scenario == ChannelScenario.Empty) return ChannelPostsPage(emptyList())
        return ChannelPostsPage(FixturePostRepository.samplePosts(channelId))
    }

    override suspend fun playlists(channelId: String): com.hhst.youtubelite.library.domain.PlaylistCollectionPage {
        delay(160)
        if (scenario == ChannelScenario.Error) throw java.io.IOException("Local error scenario")
        if (scenario == ChannelScenario.Empty) return com.hhst.youtubelite.library.domain.PlaylistCollectionPage(emptyList())
        return com.hhst.youtubelite.library.domain.PlaylistCollectionPage(
            com.hhst.youtubelite.playlist.data.FixturePlaylistRepository.summaries())
    }

    companion object {
        fun samplePage(channelId: String = "blender") = ChannelPage(
            profile(channelId), sampleVideos(), com.hhst.youtubelite.playlist.data.FixturePlaylistRepository.summaries(),
            listOf(com.hhst.youtubelite.channel.domain.ChannelHomeShelf("Open movies", "The Blender Foundation's open movie projects", sampleVideos().filter { it.homeShelf })),
        )

        /** Fixed English sample; metadata is synthetic and every asset is bundled locally. */
        fun profile(channelId: String = "blender") = ChannelProfile(
            channelId = channelId,
            name = when (channelId) { "studio" -> "Blender Studio"; "northlight" -> "Northlight Films"; else -> "Blender" },
            handle = when (channelId) { "studio" -> "@BlenderStudio"; "northlight" -> "@northlight"; else -> "@blender" },
            avatar = "bunny",
            banner = "sintel",
            subscribers = "30M subscribers",
            // Short enough that the inline "...more" tail renders at every sampled line budget.
            description = "Blender is the free and open source 3D creation suite for films.",
            links = listOf("blender.org", "studio.blender.org"),
            fullDescription = "Blender is the free and open source 3D creation suite. Explore the open movies, animation projects and stories from the artists behind them.",
            country = "Netherlands", joined = "Joined Jan 1, 2010", videoCount = "7 videos", views = "24,000,000 views",
            canonicalUrl = when (channelId) { "blender" -> "https://www.youtube.com/@BlenderOfficial"; "studio" -> "https://www.youtube.com/@BlenderStudio"; else -> "" },
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

        fun sampleShorts() = listOf(
            ChannelShort("shorts-1", "Demeter on dealing with mass while rigging Cowboi", "9.7K views", "dream", 1, 9_700),
            ChannelShort("shorts-2", "Cowboi: The Director's Voice Acting, Ranked", "11K views", "sintel", 2, 11_000),
            ChannelShort("shorts-3", "Painterly Shadows by Blender Studio", "17K views", "bunny", 3, 17_000),
            ChannelShort("shorts-4", "Join Blender Studio today", "10K views", "dream", 4, 10_000),
            ChannelShort("shorts-5", "Just 1000 more subscribers for OVERGROWN to happen", "10K views", "sintel", 5, 10_000),
            ChannelShort("shorts-6", "Last days of Open Doors @ Blender Studio", "6.9K views", "bunny", 6, 6_900),
            ChannelShort("shorts-7", "Open Doors @ Blender Studio", "17K views", "dream", 7, 17_000),
            ChannelShort("shorts-8", "Hjalti and Theo, the cutest power duo behind animation references", "16K views", "sintel", 8, 16_000),
            ChannelShort("shorts-9", "Method Animation Part 2", "6.3K views", "bunny", 9, 6_300),
            ChannelShort("shorts-10", "Have you gotten your hands on our brand new Storm character", "5.2K views", "dream", 10, 5_200),
            ChannelShort("shorts-11", "Get Storm today on studio.blender.org", "4.8K views", "sintel", 11, 4_800),
            ChannelShort("shorts-12", "A quick look inside the animation studio", "4.1K views", "bunny", 12, 4_100),
        )

        fun sampleLive() = ChannelLivePage(
            current = listOf(
                ChannelLiveVideo(
                    "live-now", "Open movie studio — animation and art", "1.2K watching", "Live now",
                    "LIVE", "dream", liveNow = true, order = 0, popularity = 1_200,
                ),
            ),
            past = listOf(
                ChannelLiveVideo("live-1", "SINGULARITY Premiere Party", "8.7K views", "Streamed 5 months ago", "1:14:48", "dream", order = 1, popularity = 8_700),
                ChannelLiveVideo("live-2", "DOGWALK Release Party", "13K views", "Streamed 1 year ago", "1:04:02", "sintel", order = 2, popularity = 13_000),
                ChannelLiveVideo("live-3", "Rigging \"Critter\" with Demeter Dzadik", "14K views", "Streamed 1 year ago", "3:47:21", "bunny", order = 3, popularity = 14_000),
                ChannelLiveVideo("live-4", "Project Gold Premiere Party", "12K views", "Streamed 1 year ago", "1:14:55", "dream", order = 4, popularity = 12_000),
                ChannelLiveVideo("live-5", "Making a scene with Grease Pencil 3", "33K views", "Streamed 2 years ago", "1:20:41", "sintel", order = 5, popularity = 33_000),
                ChannelLiveVideo("live-6", "Character Rigging with Demeter #3", "6.9K views", "Streamed 2 years ago", "3:38:50", "bunny", order = 6, popularity = 6_900),
                ChannelLiveVideo("live-7", "Character Rigging with Demeter #2", "6.9K views", "Streamed 2 years ago", "3:53:37", "dream", order = 7, popularity = 6_900),
                ChannelLiveVideo("live-8", "CloudRig coding stream with Demeter", "6.1K views", "Streamed 2 years ago", "3:25:52", "sintel", order = 8, popularity = 6_100),
            ),
        )
    }
}

/** Debug intent names are stable so previews and instrumentation use the same fixtures. */
enum class ChannelScenario(val argument: String) {
    Content("content"), Empty("empty"), Loading("loading"), Error("error");

    fun initialContent(channelId: String = "blender"): ChannelContent = when (this) {
        Content, Loading -> ChannelContent.Loading
        Empty -> ChannelContent.Ready(ChannelPage(FixtureChannelRepository.profile(channelId), emptyList()))
        Error -> ChannelContent.Failed
    }

    fun loadsContent(): Boolean = this == Content

    companion object { fun parse(value: String?) = entries.firstOrNull { it.argument == value } ?: Content }
}
