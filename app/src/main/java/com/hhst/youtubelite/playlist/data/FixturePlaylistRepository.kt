package com.hhst.youtubelite.playlist.data

import com.hhst.youtubelite.playlist.domain.*
import kotlinx.coroutines.delay

class FixturePlaylistRepository(private val scenario: PlaylistScenario = PlaylistScenario.Content) : PlaylistRepository {
    override suspend fun playlist(id: String): PlaylistPage? {
        delay(140)
        if (scenario == PlaylistScenario.Error) throw java.io.IOException("Local error scenario")
        if (scenario == PlaylistScenario.Unavailable) return null
        val summary = personalLists().firstOrNull { it.id == id } ?: summaries().firstOrNull { it.id == id } ?: return null
        return PlaylistPage(if (scenario == PlaylistScenario.Empty) summary.copy(videoCount = 0) else summary, "Watch the open movies made with Blender!",
            if (scenario == PlaylistScenario.Empty) emptyList() else entries(),
            if (scenario == PlaylistScenario.Paged) "fixture-page-2" else null)
    }

    override suspend fun playlistNext(id: String, cursor: String): PlaylistAppendPage? {
        delay(140)
        if (scenario == PlaylistScenario.Paged && cursor == "fixture-page-2") {
            return PlaylistAppendPage(entries().map { it.copy(id = it.id + "-2", videoId = it.videoId + "2") }, null)
        }
        return null
    }

    companion object {
        const val MOVIES = "PL6B3937A5D230E335"
        const val STUDIO = "PLav47HAVZMjnTFVZL-aImCQIC0uLZtNCz"

        /** Personal lists the site identifies by fixed ids. */
        const val WATCH_LATER = "WL"
        const val LIKED = "LL"

        fun personalLists() = listOf(
            PlaylistSummary(WATCH_LATER, "Watch later", "Litube Studio", "litube", 4, "dream", "Private", null, 0xFF2D4F72),
            PlaylistSummary(LIKED, "Liked videos", "Litube Studio", "litube", 12, "bunny", "Private", null, 0xFF70513B),
        )

        fun summaries() = listOf(
            PlaylistSummary(MOVIES, "Official Blender Open Movies", "Blender", "blender", 3, "bunny", "926K views", 21),
            PlaylistSummary(STUDIO, "Blender Open Movies", "Blender Studio", "studio", 3, "sintel", "1.2M views", 60, 0xFF5B3C48),
        )

        fun entries() = listOf(
            PlaylistEntry("bunny-1", "bunny", "Big Buck Bunny | A short film by the Blender Foundation", "Blender", "blender", "bunny", "9:56", "12M views", "2 years ago"),
            PlaylistEntry("dream-1", "dream", "Elephants Dream — the world's first open movie", "Blender", "blender", "dream", "10:53", "4.8M views", "3 years ago"),
            PlaylistEntry("sintel-1", "sintel", "Sintel | An adventure beyond the mountains", "Blender", "blender", "sintel", "14:48", "7.2M views", "4 years ago"),
        )
    }
}

enum class PlaylistScenario(val argument: String) {
    Content("content"), Empty("empty"), Loading("loading"), Error("error"), Unavailable("unavailable"),
    /** First page plus one server continuation for append/enumeration tests. */
    Paged("paged");

    companion object { fun parse(value: String?) = entries.firstOrNull { it.argument == value } ?: Content }
}
