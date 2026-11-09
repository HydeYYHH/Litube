package com.hhst.youtubelite.data.media

import android.content.Intent
import com.hhst.youtubelite.BuildConfig

class ContentRepositories(val client: MediaClient,
    kv: com.hhst.youtubelite.core.PreferenceStore? = null,
    val snapshots: com.hhst.youtubelite.core.ContentSnapshots? = null,
    private val sharedWatchReads: SharedWatchReads? = null,
    private val postWebContext: (com.liskovsoft.youtubeapi.videoinfo.V2.RequestContext) -> com.liskovsoft.youtubeapi.videoinfo.V2.RequestContext? =
        { it.takeIf { it.webSession?.signedIn == true } }) {
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
    val posts by lazy { MediaPostRepository(client, postWebContext) }
    val channel by lazy { MediaChannelRepository(client, posts) }
    val votes by lazy { MediaVoteRepository(client) }
    val videoActions by lazy { MediaVideoActions(client, onConfirmed = {
        watch.clearMemory()
        sharedWatchReads?.clear()
    }) }
    val saves by lazy { MediaSaveRepository(client, localLibrary) }
    val library by lazy { MediaLibraryRepository(client, localLibrary) }
    val subscriptions by lazy { MediaSubscriptionsRepository(client, avatars = avatars) }
    val explore by lazy { MediaExploreRepository(client) }
    val avatars by lazy { MediaAvatarDirectory(client, disk = kv?.let { com.hhst.youtubelite.core.PreferenceJsonCache(it) }) }
    val suggestions by lazy { MediaSearchSuggestions(client, kv ?: error("search_history_store_missing"),
        privateBrowsing = MediaSession::privateBrowsing) }
    val shortsEntry by lazy { com.hhst.youtubelite.shorts.data.ShortsEntryPreparation(
        load = { MediaShortsRepository(client).shorts() },
        identity = { MediaSession.snapshot().generation }, enabled = { !MediaSession.privateBrowsing() }) }
    fun shorts(startId: String?, channelId: String?): com.hhst.youtubelite.shorts.data.ShortsRepository {
        val repository = MediaShortsRepository(client, startId, channelId)
        if (startId != null || channelId != null) return repository
        return object : com.hhst.youtubelite.shorts.data.ShortsRepository by repository {
            override suspend fun shorts() = shortsEntry.read()
        }
    }
    fun comments() = MediaCommentsRepository(client, sharedWatchReads, postWebContext)

    companion object {
        /** Debug-only marker a navigation intent carries so the destination screen stays in fixtures too. */
        const val EXTRA_FIXTURE_SCENE = "fixture_scene"

        fun fixtures(intent: Intent): Boolean = BuildConfig.DEBUG && (ContentSource.fixtures ||
            intent.getBooleanExtra(EXTRA_FIXTURE_SCENE, false) ||
            intent.extras?.keySet()?.any { it.endsWith("_scenario") } == true)
    }
}
