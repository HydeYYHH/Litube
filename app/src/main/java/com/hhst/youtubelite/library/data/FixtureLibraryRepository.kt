package com.hhst.youtubelite.library.data

import com.hhst.youtubelite.library.domain.*
import com.hhst.youtubelite.playlist.data.FixturePlaylistRepository
import com.hhst.youtubelite.playlist.domain.PlaylistSummary
import kotlinx.coroutines.delay

class FixtureLibraryRepository(private val scenario: LibraryScenario = LibraryScenario.Content,
    override val supportsLocal: Boolean = false) : LibraryRepository {
    // The error models a transient outage: the first load fails and Retry recovers.
    private var failedOnce = false

    private fun guard() {
        if (scenario == LibraryScenario.Error && !failedOnce) {
            failedOnce = true
            throw java.io.IOException("Local error scenario")
        }
    }

    override suspend fun you(): YouPage {
        delay(140)
        guard()
        return YouPage(profile(), historyEntries(), playlists().playlists)
    }

    override suspend fun history(): HistoryPage {
        delay(140)
        guard()
        return HistoryPage(if (scenario == LibraryScenario.HistoryEmpty) emptyList() else historyEntries())
    }

    override suspend fun playlists(): PlaylistCollectionPage {
        delay(140)
        guard()
        return PlaylistCollectionPage(if (scenario == LibraryScenario.PlaylistsEmpty) emptyList() else collections())
    }

    companion object {
        fun profile() = AccountProfile("Litube Studio", "@litube", "sintel", "litube")

        fun historyEntries() = listOf(
            HistoryEntry("history-bunny", "bunny", "Big Buck Bunny | A short film by the Blender Foundation",
                "Blender", "blender", "bunny", "9:56", progress = 0.28f, section = "Today", views = "12M views"),
            HistoryEntry("history-live", "live", "Open movie studio — animation and art",
                "Blender Studio", "studio", "dream", "LIVE", section = "Today", views = "48K watching"),
            HistoryEntry("history-sintel", "sintel", "Sintel | An adventure beyond the mountains",
                "Blender", "blender", "sintel", "14:48", progress = 0.62f, section = "Yesterday", views = "6.4M views"),
            HistoryEntry("history-dream", "dream", "Elephants Dream — the world's first open movie",
                "Blender Studio", "studio", "dream", "10:53", progress = 1f, section = "Yesterday", views = "3.1M views"),
        )

        fun collections(): List<PlaylistSummary> = listOf(
            FixturePlaylistRepository.personalLists()[0],
            FixturePlaylistRepository.personalLists()[1],
            FixturePlaylistRepository.summaries()[0],
            FixturePlaylistRepository.summaries()[1],
        )
    }
}

enum class LibraryScenario(val argument: String) {
    Content("content"), Loading("loading"), Error("error"),
    HistoryEmpty("history-empty"), PlaylistsEmpty("playlists-empty");

    companion object { fun parse(value: String?) = entries.firstOrNull { it.argument == value } ?: Content }
}

object SignedOutPrompts {
    fun forContent(content: String) = SignInPrompt(
        "Don't miss new videos",
        "Sign in to see updates from your favorite YouTube channels",
        "Sign in",
    ).let { base ->
        when (content) {
            "history" -> base.copy(title = "Keep track of what you watch", message = "Watch and like videos to see your history here.")
            "playlists" -> base.copy(title = "Keep your favorite videos together", message = "Sign in to create and manage playlists.")
            "subscriptions" -> base
            else -> base
        }
    }
}
