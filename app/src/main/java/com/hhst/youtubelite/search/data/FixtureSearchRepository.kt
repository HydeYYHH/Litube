package com.hhst.youtubelite.search.data

import com.hhst.youtubelite.search.domain.*
import kotlinx.coroutines.delay

/** All artwork and metadata are packaged locally. This class has no network dependency. */
class FixtureSearchRepository(private val scenario: SearchScenario = SearchScenario.Results) : SearchRepository {
    override suspend fun search(query: String, filters: SearchFilters): SearchPage {
        delay(140)
        if (scenario == SearchScenario.Error) throw java.io.IOException("Local error scenario")
        if (scenario == SearchScenario.Empty) return SearchPage(query, emptyList())
        val results = sampleResults().filter { result ->
            val days = result.uploadedDays ?: return@filter filters.uploaded == SearchUploadDate.Anytime
            val limit = filters.uploaded.maxAgeDays ?: return@filter true
            days <= limit
        }
        val playlists = if (filters.type == SearchResultType.Playlists)
            com.hhst.youtubelite.playlist.data.FixturePlaylistRepository.summaries().filter { summary ->
                val limit = filters.uploaded.maxAgeDays ?: return@filter true
                val days = summary.updatedDays ?: return@filter false
                days <= limit
            } else emptyList()
        val channels = if (filters.type == SearchResultType.Channels) sampleChannels() else emptyList()
        val shorts = if (filters.type == SearchResultType.Shorts) sampleShorts() else emptyList()
        return SearchPage(query, if (filters.type == SearchResultType.All) results else emptyList(), playlists, channels, shorts)
    }

    companion object {
        /** The shorts shelf entries for the Shorts filter; ids match the shorts browse fixture. */
        fun sampleShorts() = listOf(
            SearchResult("short-1", "A whole world in a single frame", "Blender", "blender", "128K views", "1 week ago", "0:38", "bunny", uploadedDays = 7),
            SearchResult("short-2", "The art behind the story", "Blender", "blender", "92K views", "1 week ago", "0:24", "dream", uploadedDays = 7),
            SearchResult("short-3", "Behind the scenes", "Studio", "studio", "240K views", "1 week ago", "0:41", "dream", uploadedDays = 7),
            SearchResult("short-4", "Every detail tells a story", "Blender", "blender", "38K views", "2 weeks ago", "0:28", "bunny", uploadedDays = 14),
        )
        fun sampleChannels() = listOf(
            SearchChannel("blender", "Blender", "@blender", "30M subscribers", "bunny"),
            SearchChannel("studio", "Blender Studio", "@BlenderStudio", "577K subscribers", "sintel"),
            SearchChannel("northlight", "Northlight Films", "@northlight", "428K subscribers", "dream"),
        )
        fun sampleResults() = listOf(
            SearchResult("harbour", "City light trails — a night walk through the old harbour",
                "Northlight Films", "northlight", "1.4M views", "3 weeks ago", "8:12", "dream", progress = 0.35f, uploadedDays = 21),
            SearchResult("studio", "Inside the studio: building a scene from scratch",
                "Northlight Studio", "northlight", "428K views", "2 months ago", "12:04", "bunny", uploadedDays = 60),
            SearchResult("meadow", "Meadow at dawn — a short nature film",
                "Open Field", "openfield", "92K views", "1 year ago", "5:37", "sintel", badge = "4K", uploadedDays = 365),
            SearchResult("workshop", "Workshop diaries: shaping wood by hand",
                "Handmade Channel", "handmade", "2.1M views", "8 months ago", "17:45", "bunny", uploadedDays = 240),
            SearchResult("orbit", "Orbit — an animated short about leaving home",
                "Northlight Animation", "northlight", "6.7M views", "2 years ago", "9:56", "sintel", uploadedDays = 730),
            SearchResult("river", "River sound — one hour of calm water",
                "Quiet Place", "quietplace", "315K views", "5 months ago", "1:02:18", "dream", uploadedDays = 150),
        )
    }
}

/** Debug intent names are stable so previews and instrumentation use the same fixtures. */
enum class SearchScenario(val argument: String) {
    Results("results"), Empty("empty"), Loading("loading"), Error("error");

    fun initialContent(query: String): SearchContent = when (this) {
        Results, Loading -> SearchContent.Loading
        Empty -> SearchContent.Empty(query)
        Error -> SearchContent.Failed
    }

    /** Only the ordinary results entry loads the local fixture; other states are pinned previews. */
    fun loadsResults(): Boolean = this == Results

    companion object {
        fun parse(value: String?) = entries.firstOrNull { it.argument == value } ?: Results
    }
}
