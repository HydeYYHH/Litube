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
import androidx.room.Room
import com.hhst.youtubelite.downloader.core.DownloadCoordinator
import com.hhst.youtubelite.downloader.core.DownloadInteraction
import com.hhst.youtubelite.downloader.core.DownloadPrefs
import com.hhst.youtubelite.downloader.core.DownloadResolver
import com.hhst.youtubelite.downloader.core.MmkvDownloadPrefs
import com.hhst.youtubelite.downloader.data.DownloadRepository
import com.hhst.youtubelite.downloader.data.DownloaderDatabase
import com.hhst.youtubelite.downloader.data.RoomDownloadRepository
import com.hhst.youtubelite.downloader.net.DownloadHttpClients
import com.hhst.youtubelite.downloader.resolve.DownloadCatalogSource
import com.hhst.youtubelite.downloader.resolve.DownloadPoTokenLifecycle
import com.hhst.youtubelite.downloader.resolve.DownloadResolverImpl
import com.hhst.youtubelite.downloader.resolve.PoTokenEvictor
import com.hhst.youtubelite.downloader.resolve.SharedExtractorCatalogSource
import com.hhst.youtubelite.downloader.ui.DownloadViewModel
import com.hhst.youtubelite.ui.browser.BrowserViewModel
import com.hhst.youtubelite.ui.extension.ExtensionViewModel
import com.tencent.mmkv.MMKV
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.core.qualifier.named
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
    single<DownloadPrefs> { MmkvDownloadPrefs(get(), get()) }
    single { ExtensionManager(get()) }

    single { HttpDownloader(get(), get<PlayerCache>()) }
    single { com.hhst.youtubelite.downloader.webview.WebViewTimerOccupancy(
        com.hhst.youtubelite.downloader.webview.AndroidWebViewTimerClock(),
    ) }
    single { PoTokenProvider(androidContext(), get(), get()) }
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

    single(named("downloadHttp")) {
        val prefs = get<DownloadPrefs>()
        DownloadHttpClients.create(maxRequests = prefs.maxConnections())
    }
    single { com.hhst.youtubelite.downloader.io.DownloadDirectories.underCache(androidContext().cacheDir) }
    single<com.hhst.youtubelite.downloader.io.NetworkMonitor> {
        com.hhst.youtubelite.downloader.io.AndroidNetworkMonitor(androidContext())
    }
    single {
        val prefs = get<DownloadPrefs>()
        com.hhst.youtubelite.downloader.net.DownloadTransportImpl(
            client = get(named("downloadHttp")),
            cookies = com.hhst.youtubelite.downloader.net.WebViewCookies,
            network = get(),
            wifiOnlyProvider = { prefs.wifiOnly() },
            forbidden = com.hhst.youtubelite.downloader.net.ForbiddenRecovery { taskId, identity ->
                get<DownloadResolverImpl>().recoverSource(taskId, identity)
            },
        )
    }
    single<com.hhst.youtubelite.downloader.core.DownloadTransport> {
        get<com.hhst.youtubelite.downloader.net.DownloadTransportImpl>()
    }
    single<com.hhst.youtubelite.downloader.core.DownloadFinalizer> {
        com.hhst.youtubelite.downloader.mux.DownloadFinalizerImpl()
    }
    single<com.hhst.youtubelite.downloader.core.DownloadPublisher> {
        com.hhst.youtubelite.downloader.publish.DownloadPublisherImpl(
            backend = com.hhst.youtubelite.downloader.publish.createPublishBackend(androidContext()),
            workDir = get<com.hhst.youtubelite.downloader.io.DownloadDirectories>().workRoot(),
        )
    }
    single {
        com.hhst.youtubelite.downloader.work.AndroidWorkEnqueuePort(androidContext())
    }
    single<com.hhst.youtubelite.downloader.work.WorkEnqueuePort> { get<com.hhst.youtubelite.downloader.work.AndroidWorkEnqueuePort>() }
    single {
        com.hhst.youtubelite.downloader.work.AndroidUidtJobPort(androidContext())
    }
    single<com.hhst.youtubelite.downloader.work.UidtJobPort> { get<com.hhst.youtubelite.downloader.work.AndroidUidtJobPort>() }
    single {
        com.hhst.youtubelite.downloader.notify.AndroidNotificationPort(androidContext())
    }
    single<com.hhst.youtubelite.downloader.notify.DownloadNotificationPort> {
        get<com.hhst.youtubelite.downloader.notify.AndroidNotificationPort>()
    }
    single {
        com.hhst.youtubelite.downloader.notify.DownloadNotificationController(get())
    }
    single {
        com.hhst.youtubelite.downloader.work.BackgroundDownloadScheduler(
            repository = get(),
            coordinator = { get() },
            work = get(),
            uidt = get(),
            notifications = get(),
            sdk = { android.os.Build.VERSION.SDK_INT },
            prefs = get(),
        )
    }
    single<DownloadInteraction> { get<com.hhst.youtubelite.downloader.work.BackgroundDownloadScheduler>() }
    single<DownloadCatalogSource> { SharedExtractorCatalogSource(get()) }
    single {
        val provider = get<PoTokenProvider>()
        DownloadPoTokenLifecycle(
            catalogs = get(),
            poToken = PoTokenEvictor { provider.evict(it) },
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

    single {
        Room.databaseBuilder(
            androidContext(),
            DownloaderDatabase::class.java,
            "downloader.db",
        ).fallbackToDestructiveMigration(dropAllTables = true).build()
    }
    single<DownloadRepository> { RoomDownloadRepository(get()) }
    single {
        DownloadCoordinator(
            repository = get(),
            transport = get<com.hhst.youtubelite.downloader.net.DownloadTransportImpl>(),
            scheduler = get<com.hhst.youtubelite.downloader.work.BackgroundDownloadScheduler>(),
            publisher = get(),
        )
    }
    single {
        DownloadResolverImpl(
            coordinator = get(),
            repository = get(),
            catalogs = get(),
            poTokens = get(),
        )
    }
    single<DownloadResolver> { get<DownloadResolverImpl>() }
    single {
        com.hhst.youtubelite.downloader.engine.DownloadEngine(
            coordinator = get(),
            repository = get(),
            resolver = get(),
            transport = get(),
            finalizer = get(),
            publisher = get(),
            directories = get(),
        )
    }
    single {
        com.hhst.youtubelite.downloader.work.DownloadBatchExecutor(
            engine = get(),
            repository = get(),
            coordinator = get(),
            scheduler = get(),
        )
    }
    single {
        com.hhst.youtubelite.downloader.work.DownloadStartupReconciler(
            repository = get(),
            coordinator = get(),
            scheduler = get(),
            publisher = get(),
            directories = get(),
        )
    }
    single {
        com.hhst.youtubelite.downloader.notify.DownloadNotificationWatcher(
            coordinator = get(),
            repository = get(),
            controller = get(),
        )
    }
    viewModel {
        DownloadViewModel(
            coordinator = get(),
            catalogs = get(),
            prefs = get(),
            interaction = get(),
            downloadHttp = get(named("downloadHttp")),
        )
    }
}
