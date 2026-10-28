package com.hhst.youtubelite.notifications.data

import com.hhst.youtubelite.library.data.SignedOutPrompts
import com.hhst.youtubelite.notifications.domain.NotificationsPage
import kotlinx.coroutines.delay

/** All artwork and metadata are packaged locally. This class has no network dependency. */
class FixtureNotificationsRepository(private val scenario: NotificationsScenario = NotificationsScenario.Content) : NotificationsRepository {
    override suspend fun notifications(): NotificationsPage {
        delay(140)
        if (scenario == NotificationsScenario.Error) throw java.io.IOException("Local error scenario")
        return NotificationsPage(if (scenario == NotificationsScenario.Empty) emptyList() else sample())
    }

    companion object {
        fun sample() = listOf(
            com.hhst.youtubelite.notifications.domain.NotificationItem("note-1", "blender", "Blender", "bunny",
                "Blender uploaded: Big Buck Bunny — new artist breakdown", "2 hours ago", "bunny", videoId = "bunny", unread = true),
            com.hhst.youtubelite.notifications.domain.NotificationItem("note-2", "studio", "Blender Studio", "dream",
                "Blender Studio is live: open movie studio — animation and art", "Streaming now", "dream", videoId = "live", unread = true),
            com.hhst.youtubelite.notifications.domain.NotificationItem("note-3", "studio", "Blender Studio", "dream",
                "Blender Studio uploaded: Elephants Dream — the world's first open movie", "1 day ago", "dream", videoId = "dream"),
            com.hhst.youtubelite.notifications.domain.NotificationItem("note-4", "blender", "Blender", "bunny",
                "Blender uploaded: Sintel — an adventure beyond the mountains", "4 days ago", "sintel", videoId = "sintel"),
        )
    }
}

enum class NotificationsScenario(val argument: String) {
    Content("content"), Loading("loading"), Empty("empty"), Error("error");

    companion object { fun parse(value: String?) = entries.firstOrNull { it.argument == value } ?: Content }
}

fun notificationsSignedOutPrompt() = SignedOutPrompts.forContent("subscriptions")
