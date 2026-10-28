package com.hhst.youtubelite.data.media

import android.content.Intent
import com.hhst.youtubelite.BuildConfig

class ContentRepositories(val client: MediaClient, kv: com.tencent.mmkv.MMKV? = null,
    val snapshots: com.hhst.youtubelite.core.ContentSnapshots? = null) {
    private val authKv = object : AuthStore {
        private val storage = kv ?: com.tencent.mmkv.MMKV.defaultMMKV()
        override fun read(key: String) = storage.decodeString(key, null)
        override fun write(key: String, value: String?) { check(storage.encode(key, value)) { "device_library_write_failed" } }
    }
    val localLibrary by lazy { LocalLibraryStore(authKv, writer = LocalLibraryStore.backgroundWriter()) }
    /** The explicit device/account library selection, shared by every library surface. */
    val librarySource by lazy { LibrarySourceStore(authKv) }
    private val sourceOf: () -> com.hhst.youtubelite.library.domain.LibrarySource by lazy { { librarySource.current() } }
    val home by lazy { MediaHomeRepository(client) }
    val search by lazy { MediaSearchRepository(client) }
    val watch by lazy { CachedWatchRepository(MediaWatchRepository(client), identity = { MediaSession.snapshot().generation }) }
    val playlist by lazy { MediaPlaylistRepository(client, localLibrary, sourceOf) }
    val posts by lazy { MediaPostRepository(client) }
    val channel by lazy { MediaChannelRepository(client, posts) }
    val votes by lazy { MediaVoteRepository(client) }
    val videoActions by lazy { MediaVideoActions(client) }
    val saves by lazy { MediaSaveRepository(client, localLibrary, sourceOf) }
    val library by lazy { MediaLibraryRepository(client, localLibrary, sourceOf) }
    val subscriptions by lazy { MediaSubscriptionsRepository(client) }
    val notifications by lazy { MediaNotificationsRepository(client) }
    val explore by lazy { MediaExploreRepository(client) }
    val avatars by lazy { MediaAvatarDirectory(client) }
    val suggestions by lazy { MediaSearchSuggestions(client, kv ?: com.tencent.mmkv.MMKV.defaultMMKV()) }
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
