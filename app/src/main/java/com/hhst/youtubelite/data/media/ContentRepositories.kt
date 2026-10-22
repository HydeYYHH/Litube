package com.hhst.youtubelite.data.media

import android.content.Intent
import com.hhst.youtubelite.BuildConfig

class ContentRepositories(val client: MediaClient) {
    val home by lazy { MediaHomeRepository(client) }
    val search by lazy { MediaSearchRepository(client) }
    val watch by lazy { MediaWatchRepository(client) }
    val playlist by lazy { MediaPlaylistRepository(client) }
    val posts by lazy { MediaPostRepository(client) }
    val channel by lazy { MediaChannelRepository(client, posts) }
    val votes by lazy { MediaVoteRepository(client) }
    val videoActions by lazy { MediaVideoActions(client) }
    val library by lazy { MediaLibraryRepository(client) }
    val subscriptions by lazy { MediaSubscriptionsRepository(client) }
    val notifications by lazy { MediaNotificationsRepository(client) }
    val explore by lazy { MediaExploreRepository(client) }
    fun shorts(startId: String?, channelId: String?) = MediaShortsRepository(client, startId, channelId)
    fun comments() = MediaCommentsRepository(client)

    companion object {
        fun fixtures(intent: Intent): Boolean = BuildConfig.DEBUG && (ContentSource.fixtures ||
            intent.extras?.keySet()?.any { it.endsWith("_scenario") } == true)
    }
}
