@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hhst.youtubelite.di

import android.os.Build
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.room.Room
import com.hhst.youtubelite.cast.CastController
import com.hhst.youtubelite.cast.DlnaController
import com.hhst.youtubelite.diagnostics.DiagnosticNetwork
import com.hhst.youtubelite.core.HapticsController
import com.hhst.youtubelite.core.PipSupport
import com.hhst.youtubelite.core.JsonCache
import com.hhst.youtubelite.core.PreferenceJsonCache
import com.hhst.youtubelite.downloader.core.DownloadCoordinator
import com.hhst.youtubelite.downloader.core.DownloadFinalizer
import com.hhst.youtubelite.downloader.core.DownloadInteraction
import com.hhst.youtubelite.downloader.core.DownloadPrefs
import com.hhst.youtubelite.downloader.core.DownloadPublisher
import com.hhst.youtubelite.downloader.core.DownloadResolver
import com.hhst.youtubelite.downloader.core.DownloadTransport
import com.hhst.youtubelite.downloader.core.PreferenceDownloadPrefs
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
import com.hhst.youtubelite.downloader.engine.AndroidNotificationPort
import com.hhst.youtubelite.downloader.engine.DownloadNotificationController
import com.hhst.youtubelite.downloader.engine.DownloadNotificationPort
import com.hhst.youtubelite.downloader.engine.DownloadNotificationWatcher
import com.hhst.youtubelite.downloader.io.DownloadPublisherImpl
import com.hhst.youtubelite.downloader.io.createPublishBackend
import com.hhst.youtubelite.downloader.resolve.DownloadCatalogSource
import com.hhst.youtubelite.downloader.resolve.DownloadPoTokenLifecycle
import com.hhst.youtubelite.downloader.resolve.DownloadResolverImpl
import com.hhst.youtubelite.downloader.resolve.SharedExtractorCatalogSource
import com.hhst.youtubelite.downloader.ui.DownloadViewModel
import com.hhst.youtubelite.core.AndroidWebViewTimerClock
import com.hhst.youtubelite.core.WebViewTimerOccupancy
import com.hhst.youtubelite.downloader.engine.AndroidUidtJobPort
import com.hhst.youtubelite.downloader.engine.AndroidWorkEnqueuePort
import com.hhst.youtubelite.downloader.engine.BackgroundDownloadScheduler
import com.hhst.youtubelite.downloader.engine.DownloadBatchExecutor
import com.hhst.youtubelite.downloader.engine.DownloadStartupReconciler
import com.hhst.youtubelite.downloader.engine.UidtJobPort
import com.hhst.youtubelite.downloader.engine.WorkEnqueuePort
import com.hhst.youtubelite.extension.ExtensionManager
import com.hhst.youtubelite.extension.PrefStore
import com.hhst.youtubelite.extractor.AndroidChallengeSolver
import com.hhst.youtubelite.extractor.BrowserPlayerResponses
import com.hhst.youtubelite.extractor.Cache
import com.hhst.youtubelite.extractor.DiskCache
import com.hhst.youtubelite.extractor.AdaptiveClientOrder
import com.hhst.youtubelite.extractor.PlaybackHost
import com.hhst.youtubelite.extractor.PoTokenMinter
import com.hhst.youtubelite.extractor.SignatureSolver
import com.hhst.youtubelite.extractor.ExtractionDiagnostics
import com.hhst.youtubelite.extractor.Extractor
import com.hhst.youtubelite.extractor.HeavyJsGate
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
import com.hhst.youtubelite.core.PreferenceStore
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.bind
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module

val appModule = module {
    // freshContext runs on the MediaClient pool: it may block on a token refresh.
    single { com.hhst.youtubelite.data.media.MediaClient(get(), com.hhst.youtubelite.data.media.MediaSession::freshContext) }
    single { com.hhst.youtubelite.core.ContentSnapshots(java.io.File(androidContext().filesDir, "content-snapshots")) }
    single {
        val postSessions = com.hhst.youtubelite.watch.chat.ChatWebSessionStore(
            com.hhst.youtubelite.data.media.PreferencesAuthStore(androidContext()))
        com.hhst.youtubelite.data.media.ContentRepositories(get(), get(), get(), get(), postSessions::context)
    }
    single {
        com.hhst.youtubelite.data.media.PlaybackWatchReporting(get(), { get<com.hhst.youtubelite.data.media.ContentRepositories>().localLibrary }).also { reporting ->
            get<com.hhst.youtubelite.extension.ExtensionManager>().addOnChangedListener { key ->
                if (key == com.hhst.youtubelite.extension.PreferenceKeys.PRIVATE_BROWSING || key == "*")
                    reporting.onPrivacyChanged()
            }
        }
    }
    single {
        DiagnosticNetwork.install(OkHttpClient.Builder())
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
    single { PreferenceStore.app(androidContext()) }
    single<JsonCache> { PreferenceJsonCache(PreferenceStore.of(androidContext(), "media-cache")) }
    single { MemCache() }
    single<Cache> {
        LayeredCache(
            mem = get<MemCache>(),
            disk = DiskCache(store = get()),
        )
    }
    single<PrefStore> { PreferenceStore.app(androidContext()) }
    single<DownloadPrefs> { PreferenceDownloadPrefs(get()) }
    single { ExtensionManager(get()) }
    single { HapticsController(androidContext(), get()) }

    single { WebViewTimerOccupancy(AndroidWebViewTimerClock()) }
    single { HeavyJsGate() }
    single { ExtractionDiagnostics() }
    single { YoutubeSessionProvider(androidContext(), get(), get()) }
    single { com.hhst.youtubelite.data.media.SharedWatchReads() }
    single { BrowserPlayerResponses(get()) }
    single { YoutubeMediaRequests(get<YoutubeSessionProvider>(), get()) }
    single { AndroidChallengeSolver(androidContext(), get(), get()) } bind SignatureSolver::class
    single { PoTokenProvider(androidContext(), get(), get()) } bind PoTokenMinter::class
    single { AdaptiveClientOrder() }
    single { YoutubeExtractionHost(get(), get(), get(), get(), get(), get(), get(), get(),
        playback = com.hhst.youtubelite.extractor.MscPlayback(get(), get(),
            com.hhst.youtubelite.data.media.MediaSession::freshContext,
            com.hhst.youtubelite.data.media.MediaSession::rememberVisitor,
            get())) } bind PlaybackHost::class
    single { OEmbedTitleFetcher(get()) }
    single {
        Extractor(
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
            forbidden = ForbiddenRecovery { taskId, generation, identity ->
                get<DownloadResolverImpl>().recoverSource(taskId, generation, identity)
            },
            mediaRequests = get(),
            fallback = ForbiddenRecovery { taskId, generation, identity ->
                get<DownloadResolverImpl>().backupSource(taskId, generation, identity)
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

    single { PlayerDataSource.create(androidContext(), get<OkHttpClient>(), get()) }
    single {
        val meter = DefaultBandwidthMeter.getSingletonInstance(androidContext())
        MediaSourceResolver(get<PlayerDataSource>(), startupBandwidth = { meter.bitrateEstimate },
            startupVideoSupport = PlaybackStartup.videoSupport(androidContext()))
    }
    single { SponsorBlockManager(get(), get()) }
    single { com.hhst.youtubelite.dearrow.DeArrowService(get(), get()) }
    single {
        QueueRepository(get()) {
            val context = com.hhst.youtubelite.data.media.MediaSession.snapshot()
            com.hhst.youtubelite.player.QueueOwner.from(context.signedIn, context.accountId, context.onBehalfOfUser)
        }
    }
    single {
        com.hhst.youtubelite.shorts.data.ShortsMediaPreloader(
            androidContext(), get(), get<PlayerDataSource>(), get<com.hhst.youtubelite.extension.ExtensionManager>(),
            demandFor = { id -> get<PlaybackApi>().prefetchDemand(id) })
    }
    single<PlaybackApi> {
        PlaybackEngine(
            context = androidContext(),
            extractor = get(),
            resolver = get(),
            cache = get(),
            prefs = get(),
            sponsorBlock = get(),
            titleFetcher = get(),
            remoteResumePosition = com.hhst.youtubelite.data.media.MediaResumePosition(get(), get())::load,
        ).also { engine ->
            engine.notificationController = get()
        }
    }
    single { MiniPlayerStore(get()) }
    single { CastController(androidContext(), get(), get()) }
    single { DlnaController(androidContext()) }
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
            dlna = getOrNull(),
            appContext = androidContext(),
            haptics = get(),
            nextVideo = { id -> if (com.hhst.youtubelite.data.media.ContentSource.fixtures) null
                else get<com.hhst.youtubelite.data.media.ContentRepositories>().watch.watch(id).related
                .firstOrNull { it.id != id }?.id },
            reporting = get(),
        )
    }

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
