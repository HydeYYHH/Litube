@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hhst.youtubelite.di

import com.google.gson.Gson
import com.hhst.youtubelite.cast.CastController
import com.hhst.youtubelite.core.JsonCache
import com.hhst.youtubelite.core.MmkvJsonCache
import com.hhst.youtubelite.extension.ExtensionManager
import com.hhst.youtubelite.extension.MmkvPrefStore
import com.hhst.youtubelite.extension.PrefStore
import com.hhst.youtubelite.extractor.Cache
import com.hhst.youtubelite.extractor.ClientOrderStore
import com.hhst.youtubelite.extractor.DiskCache
import com.hhst.youtubelite.extractor.Extractor
import com.hhst.youtubelite.extractor.HttpDownloader
import com.hhst.youtubelite.extractor.LayeredCache
import com.hhst.youtubelite.extractor.MemCache
import com.hhst.youtubelite.extractor.OEmbedTitleFetcher
import com.hhst.youtubelite.extractor.PlayerCache
import com.hhst.youtubelite.extractor.PoTokenProvider
import com.hhst.youtubelite.player.PlayerViewModel
import com.hhst.youtubelite.player.datasource.MediaSourceResolver
import com.hhst.youtubelite.player.datasource.PlayerDataSource
import com.hhst.youtubelite.player.engine.PlaybackApi
import com.hhst.youtubelite.player.engine.PlaybackEngine
import com.hhst.youtubelite.player.engine.PlaybackNotificationController
import com.hhst.youtubelite.player.queue.QueueRepository
import com.hhst.youtubelite.player.service.ServiceNotificationController
import com.hhst.youtubelite.player.sponsor.SponsorBlockManager
import com.hhst.youtubelite.player.surface.MiniPlayerStore
import com.hhst.youtubelite.ui.browser.BrowserViewModel
import com.hhst.youtubelite.ui.extension.ExtensionViewModel
import com.tencent.mmkv.MMKV
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module
import java.util.concurrent.TimeUnit

/** Application-scoped Koin module. */
val appModule = module {
    single {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
    single { Gson() }
    single { MMKV.defaultMMKV() }
    single<JsonCache> { MmkvJsonCache(kv = get(), gson = get()) }
    single { MemCache() }
    single { PlayerCache() }
    single<Cache> {
        LayeredCache(
            mem = get<MemCache>(),
            disk = DiskCache(store = get()),
        )
    }
    single { ClientOrderStore(kv = get()) }
    single<PrefStore> { MmkvPrefStore(get()) }
    single { ExtensionManager(get()) }

    single { HttpDownloader(get(), get<PlayerCache>()) }
    single { PoTokenProvider(androidContext(), get()) }
    single { OEmbedTitleFetcher(get()) }
    single {
        Extractor(
            downloader = get(),
            cache = get(),
            poToken = get<PoTokenProvider>(),
            clientOrder = get(),
            playerCache = get(),
        )
    }

    // Player
    single { PlayerDataSource.create(androidContext(), get<OkHttpClient>()) }
    single { MediaSourceResolver(get<PlayerDataSource>()) }
    single { SponsorBlockManager(get(), get()) }
    single { QueueRepository(get()) }
    single<PlaybackApi> {
        PlaybackEngine(
            context = androidContext(),
            extractor = get(),
            resolver = get(),
            cache = get(),
            prefs = get(),
            sponsorBlock = get(),
            clientOrder = get(),
            poTokenProvider = get(),
            titleFetcher = get(),
        ).also { engine ->
            engine.notificationController = get()
        }
    }
    single { MiniPlayerStore(get()) }
    single { CastController(androidContext(), get()) }
    single<PlaybackNotificationController> {
        ServiceNotificationController(
            androidContext(),
            get(),
        )
    }
    // Process-scoped: background play, MediaSession keys and queue auto-advance
    // must outlive the activity (home / recents swipe).
    single {
        PlayerViewModel(
            engine = get(),
            queue = get(),
            prefs = get(),
            cache = get(),
            cast = get(),
            appContext = androidContext(),
        )
    }
    viewModelOf(::BrowserViewModel)
    viewModel { ExtensionViewModel(manager = get()) }
}
