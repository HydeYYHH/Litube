@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hhst.youtubelite.di

import android.os.Build
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.room.Room
import com.google.gson.Gson
import com.hhst.youtubelite.cast.CastController
import com.hhst.youtubelite.core.HapticsController
import com.hhst.youtubelite.core.PipSupport
import com.hhst.youtubelite.core.JsonCache
import com.hhst.youtubelite.core.MmkvJsonCache
import com.hhst.youtubelite.downloader.core.DownloadCoordinator
import com.hhst.youtubelite.downloader.core.DownloadFinalizer
import com.hhst.youtubelite.downloader.core.DownloadInteraction
import com.hhst.youtubelite.downloader.core.DownloadPrefs
import com.hhst.youtubelite.downloader.core.DownloadPublisher
import com.hhst.youtubelite.downloader.core.DownloadResolver
import com.hhst.youtubelite.downloader.core.DownloadTransport
import com.hhst.youtubelite.downloader.core.MmkvDownloadPrefs
import com.hhst.youtubelite.downloader.data.DownloadRepository
import com.hhst.youtubelite.downloader.data.DownloaderDatabase
import com.hhst.youtubelite.downloader.data.RoomDownloadRepository
import com.hhst.youtubelite.downloader.engine.DownloadEngine
import com.hhst.youtubelite.downloader.io.AndroidNetworkMonitor
import com.hhst.youtubelite.downloader.io.DownloadDirectories
import com.hhst.youtubelite.downloader.io.NetworkMonitor
import com.hhst.youtubelite.downloader.io.DownloadFinalizerImpl
import com.hhst.youtubelite.downloader.net.DownloadHttpClients
import com.hhst.youtubelite.downloader.net.DownloadTransportImpl
import com.hhst.youtubelite.downloader.net.ForbiddenRecovery
import com.hhst.youtubelite.downloader.net.WebViewCookies
import com.hhst.youtubelite.downloader.notify.AndroidNotificationPort
import com.hhst.youtubelite.downloader.notify.DownloadNotificationController
import com.hhst.youtubelite.downloader.notify.DownloadNotificationPort
import com.hhst.youtubelite.downloader.notify.DownloadNotificationWatcher
import com.hhst.youtubelite.downloader.io.DownloadPublisherImpl
import com.hhst.youtubelite.downloader.io.createPublishBackend
import com.hhst.youtubelite.downloader.resolve.DownloadCatalogSource
import com.hhst.youtubelite.downloader.resolve.DownloadPoTokenLifecycle
import com.hhst.youtubelite.downloader.resolve.DownloadResolverImpl
import com.hhst.youtubelite.downloader.resolve.SharedExtractorCatalogSource
import com.hhst.youtubelite.downloader.ui.DownloadViewModel
import com.hhst.youtubelite.downloader.webview.AndroidWebViewTimerClock
import com.hhst.youtubelite.downloader.webview.WebViewTimerOccupancy
import com.hhst.youtubelite.downloader.engine.AndroidUidtJobPort
import com.hhst.youtubelite.downloader.engine.AndroidWorkEnqueuePort
import com.hhst.youtubelite.downloader.engine.BackgroundDownloadScheduler
import com.hhst.youtubelite.downloader.engine.DownloadBatchExecutor
import com.hhst.youtubelite.downloader.engine.DownloadStartupReconciler
import com.hhst.youtubelite.downloader.engine.UidtJobPort
import com.hhst.youtubelite.downloader.engine.WorkEnqueuePort
import com.hhst.youtubelite.extension.ExtensionManager
import com.hhst.youtubelite.extension.MmkvPrefStore
import com.hhst.youtubelite.extension.PrefStore
import com.hhst.youtubelite.extractor.AndroidChallengeSolver
import com.hhst.youtubelite.extractor.BrowserPlayerResponses
import com.hhst.youtubelite.extractor.Cache
import com.hhst.youtubelite.extractor.DiskCache
import com.hhst.youtubelite.extractor.ExtractionDiagnostics
import com.hhst.youtubelite.extractor.Extractor
import com.hhst.youtubelite.extractor.HeavyJsGate
import com.hhst.youtubelite.extractor.HttpDownloader
import com.hhst.youtubelite.extractor.LayeredCache
import com.hhst.youtubelite.extractor.MemCache
import com.hhst.youtubelite.extractor.OEmbedTitleFetcher
import com.hhst.youtubelite.extractor.PoTokenProvider
import com.hhst.youtubelite.extractor.YoutubeExtractionHost
import com.hhst.youtubelite.extractor.YoutubeMediaRequests
import com.hhst.youtubelite.extractor.YoutubeSessionProvider
import com.hhst.youtubelite.player.PlayerViewModel
import com.hhst.youtubelite.player.datasource.MediaSourceResolver
import com.hhst.youtubelite.player.datasource.PlaybackStartup
import com.hhst.youtubelite.player.datasource.PlayerDataSource
import com.hhst.youtubelite.player.engine.PlaybackApi
import com.hhst.youtubelite.player.engine.PlaybackEngine
import com.hhst.youtubelite.player.engine.PlaybackNotificationController
import com.hhst.youtubelite.player.QueueRepository
import com.hhst.youtubelite.player.service.ServiceNotificationController
import com.hhst.youtubelite.player.sponsor.SponsorBlockManager
import com.hhst.youtubelite.player.surface.MiniPlayerStore
import com.hhst.youtubelite.ui.browser.BrowserViewModel
import com.hhst.youtubelite.ui.extension.ExtensionViewModel
import com.tencent.mmkv.MMKV
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.core.qualifier.named
import org.koin.dsl.module

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
    single<Cache> {
        LayeredCache(
            mem = get<MemCache>(),
            disk = DiskCache(store = get()),
        )
    }
    single<PrefStore> { MmkvPrefStore(get()) }
    single<DownloadPrefs> { MmkvDownloadPrefs(get(), get()) }
    single { ExtensionManager(get()) }
    single { HapticsController(androidContext(), get()) }

    single { HttpDownloader(get()) }
    single { WebViewTimerOccupancy(AndroidWebViewTimerClock()) }
    single { HeavyJsGate() }
    single { ExtractionDiagnostics() }
    single { YoutubeSessionProvider(androidContext(), get(), get()) }
    single { BrowserPlayerResponses(get()) }
    single { YoutubeMediaRequests(get<YoutubeSessionProvider>(), get()) }
    single { AndroidChallengeSolver(androidContext(), get(), get()) }
    single { PoTokenProvider(androidContext(), get(), get()) }
    single { YoutubeExtractionHost(get(), get(), get(), get(), get(), get(), get()) }
    single { OEmbedTitleFetcher(get()) }
    single {
        Extractor(
            downloader = get(),
            cache = get(),
            host = get(),
        )
    }

    single(named("downloadHttp")) {
        val prefs = get<DownloadPrefs>()
        DownloadHttpClients.create(maxRequests = prefs.maxConnections())
    }
    single { DownloadDirectories.underCache(androidContext().cacheDir) }
    single<NetworkMonitor> { AndroidNetworkMonitor(androidContext()) }
    single {
        val prefs = get<DownloadPrefs>()
        DownloadTransportImpl(
            client = get<OkHttpClient>(named("downloadHttp")).newBuilder()
                .followRedirects(false).followSslRedirects(false)
                .addInterceptor(get<YoutubeMediaRequests>().interceptor()).build(),
            cookies = WebViewCookies,
            network = get(),
            wifiOnlyProvider = { prefs.wifiOnly() },
            forbidden = ForbiddenRecovery { taskId, identity ->
                get<DownloadResolverImpl>().recoverSource(taskId, identity)
            },
            mediaRequests = get(),
            fallback = ForbiddenRecovery { taskId, identity ->
                get<DownloadResolverImpl>().backupSource(taskId, identity)
            },
        )
    }
    single<DownloadTransport> { get<DownloadTransportImpl>() }
    single<DownloadFinalizer> { DownloadFinalizerImpl() }
    single<DownloadPublisher> {
        DownloadPublisherImpl(
            backend = createPublishBackend(androidContext()),
            workDir = get<DownloadDirectories>().workRoot(),
        )
    }
    single {
        AndroidWorkEnqueuePort(androidContext())
    }
    single<WorkEnqueuePort> { get<AndroidWorkEnqueuePort>() }
    single {
        AndroidUidtJobPort(androidContext())
    }
    single<UidtJobPort> { get<AndroidUidtJobPort>() }
    single {
        AndroidNotificationPort(androidContext())
    }
    single<DownloadNotificationPort> {
        get<AndroidNotificationPort>()
    }
    single {
        DownloadNotificationController(get())
    }
    single {
        BackgroundDownloadScheduler(
            repository = get(),
            coordinator = { get() },
            work = get(),
            uidt = get(),
            notifications = get(),
            sdk = { Build.VERSION.SDK_INT },
            prefs = get(),
        )
    }
    single<DownloadInteraction> { get<BackgroundDownloadScheduler>() }
    single<DownloadCatalogSource> { SharedExtractorCatalogSource(get()) }
    single {
        DownloadPoTokenLifecycle(
            catalogs = get(),
        )
    }

    // Player
    single { PlayerDataSource.create(androidContext(), get<OkHttpClient>(), get()) }
    single {
        val meter = DefaultBandwidthMeter.getSingletonInstance(androidContext())
        MediaSourceResolver(get<PlayerDataSource>(), startupBandwidth = { meter.bitrateEstimate },
            startupVideoSupport = PlaybackStartup.videoSupport(androidContext()))
    }
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
            titleFetcher = get(),
        ).also { engine ->
            engine.notificationController = get()
        }
    }
    single { MiniPlayerStore(get()) }
    single { CastController(androidContext(), get(), get()) }
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
            haptics = get(),
        )
    }
    viewModelOf(::BrowserViewModel)
    viewModel { ExtensionViewModel(manager = get(), pipSupported = PipSupport.isSupported(androidContext())) }

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
            transport = get<DownloadTransportImpl>(),
            scheduler = get<BackgroundDownloadScheduler>(),
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
        DownloadEngine(
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
        DownloadBatchExecutor(
            engine = get(),
            repository = get(),
            coordinator = get(),
            scheduler = get(),
        )
    }
    single {
        DownloadStartupReconciler(
            repository = get(),
            coordinator = get(),
            scheduler = get(),
            publisher = get(),
            directories = get(),
        )
    }
    single {
        DownloadNotificationWatcher(
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
            haptics = get(),
        )
    }
}
