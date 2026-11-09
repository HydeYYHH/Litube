package com.hhst.youtubelite.watch.data

import com.hhst.youtubelite.watch.domain.WatchComment
import com.hhst.youtubelite.watch.domain.WatchContent
import com.hhst.youtubelite.watch.domain.WatchPage
import com.hhst.youtubelite.watch.domain.WatchRelated
import com.hhst.youtubelite.watch.domain.WatchRepository
import com.hhst.youtubelite.watch.domain.WatchVideo
import kotlinx.coroutines.delay

class FixtureWatchRepository(
    private val scenario: WatchScenario = WatchScenario.Content,
    /** Debug instrumentation seam; the default keeps every fixture chatKey null and goldens stable. */
    private val chatKey: String? = null,
) : WatchRepository {
    override suspend fun watch(videoId: String): WatchPage {
        delay(140)
        if (scenario == WatchScenario.Error) throw java.io.IOException("Local error scenario")
        val page = samplePage(videoId)
        if (scenario == WatchScenario.Description) {
            return page.copy(video = page.video.copy(description = linkedSampleDescription))
        }
        if (chatKey != null) return page.copy(video = page.video.copy(chatKey = chatKey))
        return page
    }

    companion object {
        private data class Entry(
            val title: String,
            val channel: String,
            val handle: String,
            val channelId: String,
            val views: String,
            val age: String,
            val artwork: String,
        )

        /** Known home/search ids keep list metadata; anything else uses the default sample. */
        private val entries = mapOf(
            "bunny" to Entry("Big Buck Bunny | A short film by the Blender Foundation",
                "Blender", "@Blender", "blender", "12M views", "2 years ago", "bunny"),
            "dream" to Entry("Elephants Dream — the world's first open movie",
                "Blender Studio", "@BlenderStudio", "studio", "4.8M views", "3 years ago", "dream"),
            "sintel" to Entry("Sintel | An adventure beyond the mountains",
                "Blender", "@Blender", "blender", "7.2M views", "4 years ago", "sintel"),
            "harbour" to Entry("City light trails — a night walk through the old harbour",
                "Northlight Films", "@northlight", "northlight", "1.4M views", "3 weeks ago", "dream"),
            "studio" to Entry("Inside the studio: building a scene from scratch",
                "Northlight Studio", "@northlight", "northlight", "428K views", "2 months ago", "bunny"),
            "meadow" to Entry("Meadow at dawn — a short nature film",
                "Open Field", "@openfield", "openfield", "92K views", "1 year ago", "sintel"),
            "workshop" to Entry("Workshop diaries: shaping wood by hand",
                "Handmade Channel", "@handmade", "handmade", "2.1M views", "8 months ago", "bunny"),
            "orbit" to Entry("Orbit — an animated short about leaving home",
                "Northlight Animation", "@northlight", "northlight", "6.7M views", "2 years ago", "sintel"),
            "river" to Entry("River sound — one hour of calm water",
                "Quiet Place", "@quietplace", "quietplace", "315K views", "5 months ago", "dream"),
            "live" to Entry("Open movie studio — animation and art",
                "Blender Studio", "@BlenderStudio", "studio", "1.2K watching", "LIVE", "dream"),
            "live-1" to Entry("SINGULARITY Premiere Party", "Blender Studio", "@BlenderStudio", "studio", "8.7K views", "Streamed 5 months ago", "dream"),
            "live-2" to Entry("DOGWALK Release Party", "Blender Studio", "@BlenderStudio", "studio", "13K views", "Streamed 1 year ago", "sintel"),
            "live-3" to Entry("Rigging \"Critter\" with Demeter Dzadik", "Blender Studio", "@BlenderStudio", "studio", "14K views", "Streamed 1 year ago", "bunny"),
            "live-4" to Entry("Project Gold Premiere Party", "Blender Studio", "@BlenderStudio", "studio", "12K views", "Streamed 1 year ago", "dream"),
            "live-5" to Entry("Making a scene with Grease Pencil 3", "Blender Studio", "@BlenderStudio", "studio", "33K views", "Streamed 2 years ago", "sintel"),
            "live-6" to Entry("Character Rigging with Demeter #3", "Blender Studio", "@BlenderStudio", "studio", "6.9K views", "Streamed 2 years ago", "bunny"),
            "live-7" to Entry("Character Rigging with Demeter #2", "Blender Studio", "@BlenderStudio", "studio", "6.9K views", "Streamed 2 years ago", "dream"),
            "live-8" to Entry("CloudRig coding stream with Demeter", "Blender Studio", "@BlenderStudio", "studio", "6.1K views", "Streamed 2 years ago", "sintel"),
        )

        private val defaultEntry = entries.getValue("bunny")

        /** Fixed English sample; reaction counts are synthetic and shared by every entry. */
        fun sampleVideo(videoId: String): WatchVideo {
            val categoryItem = com.hhst.youtubelite.explore.domain.ExploreCategory.entries.asSequence()
                .flatMap { com.hhst.youtubelite.explore.data.FixtureExploreRepository.sample(it).asSequence() }
                .firstOrNull { it.id == videoId }
            val entry = entries[videoId] ?: categoryItem?.let { item ->
                val original = entries[item.id.substringAfter('-')] ?: defaultEntry
                original.copy(title = item.title, channel = item.author, channelId = item.channelId,
                    views = item.metadata, age = item.age.orEmpty(), artwork = item.artwork)
            } ?: defaultEntry
            return WatchVideo(
                id = videoId,
                title = entry.title,
                channel = entry.channel,
                handle = entry.handle,
                channelId = entry.channelId,
                artwork = entry.artwork,
                likes = "110K likes",
                views = entry.views,
                age = entry.age,
                commentCount = "5.2K",
                topComment = WatchComment(
                    author = "Maya K.",
                    text = "The lighting still holds up after all these years — every rewatch turns up something new.",
                    artwork = "sintel",
                ),
                description = "An open movie short rendered end to end with free tools. " +
                    "This local sample never leaves the device: no link, chapter or external page is opened from it.",
                live = categoryItem?.live ?: (videoId == "live"),
            )
        }

        fun sampleRelated(): List<WatchRelated> = listOf(
            WatchRelated("harbour", "City light trails — a night walk through the old harbour",
                "Northlight Films", "northlight", "1.4M views", "3 weeks ago", "8:12", "dream", progress = 0.35f),
            WatchRelated("studio", "Inside the studio: building a scene from scratch",
                "Northlight Studio", "northlight", "428K views", "2 months ago", "12:04", "bunny"),
            WatchRelated("meadow", "Meadow at dawn — a short nature film",
                "Open Field", "openfield", "92K views", "1 year ago", "5:37", "sintel", badge = "4K"),
            WatchRelated("workshop", "Workshop diaries: shaping wood by hand",
                "Handmade Channel", "handmade", "2.1M views", "8 months ago", "17:45", "bunny"),
            WatchRelated("orbit", "Orbit — an animated short about leaving home",
                "Northlight Animation", "northlight", "6.7M views", "2 years ago", "9:56", "sintel"),
            WatchRelated("river", "River sound — one hour of calm water",
                "Quiet Place", "quietplace", "315K views", "5 months ago", "1:02:18", "dream"),
            WatchRelated("bunny", "Big Buck Bunny | A short film by the Blender Foundation",
                "Blender", "blender", "12M views", "2 years ago", "9:56", "bunny", progress = 0.28f),
            WatchRelated("dream", "Elephants Dream — the world's first open movie",
                "Blender Studio", "studio", "4.8M views", "3 years ago", "10:53", "dream"),
        )

        fun samplePage(videoId: String): WatchPage = WatchPage(
            video = sampleVideo(videoId),
            related = sampleRelated().filterNot { it.id == videoId },
        )

        /**
         * Description for [WatchScenario.Description]: chapter timestamps (incl. an
         * invalid one), a same-video link with start time, another video, an external page.
         */
        val linkedSampleDescription = """
            Chapters: 0:42 setup, 12:34 the middle act, 1:02:03 credits.
            Cut 99:99 is not a real timestamp. Sources and a full write-up at
            https://www.blender.org/about/ and on https://youtu.be/aqz-KE-bpKQ?t=90.
        """.trimIndent().replace('\n', ' ')
    }
}

/** Debug intent names are stable so previews and instrumentation use the same fixtures. */
enum class WatchScenario(val argument: String) {
    Content("content"), Loading("loading"), Error("error"),
    /** Test seam: the sample video carries a description with timestamps and links. */
    Description("description");

    fun initialContent(): WatchContent = if (this == Error) WatchContent.Failed else WatchContent.Loading

    /** Only content scenarios load the local fixture; other states are pinned previews. */
    fun loadsContent(): Boolean = this == Content || this == Description

    companion object {
        fun parse(value: String?) = entries.firstOrNull { it.argument == value } ?: Content
    }
}
