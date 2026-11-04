package com.hhst.youtubelite.data.media

import android.content.Intent
import com.hhst.youtubelite.BuildConfig

class ContentRepositories(val client: MediaClient,
    kv: com.hhst.youtubelite.core.PreferenceStore? = null,
    val snapshots: com.hhst.youtubelite.core.ContentSnapshots? = null,
    private val sharedWatchReads: SharedWatchReads? = null) {
    private val authKv = object : AuthStore {
        private val storage = kv ?: error("device_library_store_missing")
        override fun read(key: String) = storage.readString(key)
        override fun write(key: String, value: String?) {
            storage.writeStringDurably(key, value)
        }
    }
    val localLibrary by lazy { LocalLibraryStore(authKv, writer = LocalLibraryStore.backgroundWriter()) }
    val home by lazy { MediaHomeRepository(client) }
    val search by lazy { MediaSearchRepository(client) }
    val watch by lazy { CachedWatchRepository(MediaWatchRepository(client, sharedWatchReads), identity = { MediaSession.snapshot().generation }) }
    val playlist by lazy { MediaPlaylistRepository(client, localLibrary) }
    val posts by lazy { MediaPostRepository(client) }
    val channel by lazy { MediaChannelRepository(client, posts) }
    val votes by lazy { MediaVoteRepository(client) }
    val videoActions by lazy { MediaVideoActions(client, onConfirmed = { watch.clearMemory() }) }
    val saves by lazy { MediaSaveRepository(client, localLibrary) }
    val library by lazy { MediaLibraryRepository(client, localLibrary) }
    val subscriptions by lazy { MediaSubscriptionsRepository(client) }
    val notifications by lazy { MediaNotificationsRepository(client) }
    val explore by lazy { MediaExploreRepository(client) }
    val avatars by lazy { MediaAvatarDirectory(client) }
    val suggestions by lazy { MediaSearchSuggestions(client, kv ?: error("search_history_store_missing")) }
    fun shorts(startId: String?, channelId: String?) = MediaShortsRepository(client, startId, channelId)
    fun comments() = MediaCommentsRepository(client)

    companion object {
        /** Debug-only marker a navigation intent carries so the destination screen stays in fixtures too. */
        const val EXTRA_FIXTURE_SCENE = "fixture_scene"

        fun fixtures(intent: Intent): Boolean = BuildConfig.DEBUG && (ContentSource.fixtures ||
            intent.getBooleanExtra(EXTRA_FIXTURE_SCENE, false) ||
            intent.extras?.keySet()?.any { it.endsWith("_scenario") } == true)
    }
}
