package com.hhst.youtubelite

import android.app.Application
import android.webkit.WebSettings
import androidx.work.Configuration
import androidx.work.WorkManager
import com.hhst.youtubelite.core.Constants
import com.hhst.youtubelite.di.appModule
import com.hhst.youtubelite.diagnostics.AppLog
import com.hhst.youtubelite.downloader.engine.DownloadNetworkRestore
import com.hhst.youtubelite.downloader.engine.DownloadStartupReconciler
import com.hhst.youtubelite.downloader.engine.KoinDownloadWorkerFactory
import com.hhst.youtubelite.downloader.engine.DownloadNotificationWatcher
import com.hhst.youtubelite.extractor.EjsRuntimeProcess
import com.hhst.youtubelite.extractor.Promise
import com.hhst.youtubelite.player.datasource.PlayerDataSource
import kotlinx.coroutines.launch
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.logger.Level

/** Lightweight process entry. Playback/download startup is explicitly opt-in. */
class App : Application(), Configuration.Provider {

    @Volatile var mediaRuntimeStarted = false
        private set

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(KoinDownloadWorkerFactory()).build()

    override fun onCreate() {
        super.onCreate()
        AppLog.initialize(this)
        if (EjsRuntimeProcess.initialize(this)) return
        com.hhst.youtubelite.data.media.MediaSession.initialize(com.hhst.youtubelite.data.media.PreferencesAuthStore(this)) {
            get<com.hhst.youtubelite.extension.ExtensionManager>()
                .isEnabled(com.hhst.youtubelite.extension.PreferenceKeys.PRIVATE_BROWSING)
        }
        startKoin {
            androidLogger(Level.ERROR)
            androidContext(this@App)
            modules(appModule)
        }
        registerActivityLifecycleCallbacks(com.hhst.youtubelite.player.PlaybackLifecycle())
        // Publish the persisted appearance before the first activity composes.
        get<com.hhst.youtubelite.extension.ExtensionManager>().let { prefs ->
            com.hhst.youtubelite.ui.theme.AppAppearance.update(prefs.themeMode())
        }
    }

    /** Initializes playback and download services when their entry points are opened. */
    @Synchronized fun startMediaRuntime() {
        if (mediaRuntimeStarted) return
        Constants.genuineUserAgent = WebSettings.getDefaultUserAgent(this)
        // Drop the previous persistent stream URLs/tokens once; completed downloads stay untouched.
        com.hhst.youtubelite.core.PreferenceStore.app(this).let { kv ->
            if (!kv.getBool("youtube-engine-v1-migrated", false)) {
                kv.keys().filter { it.startsWith("extractor:stream:") || it == "extractor:client_order" }
                    .forEach(kv::remove)
                kv.putBool("youtube-engine-v1-migrated", true)
            }
        }
        WorkManager.getInstance(this)
        Promise.DEFAULT_SCOPE.launch {
            PlayerDataSource.precache(this@App)
        }
        Promise.DEFAULT_SCOPE.launch {
            get<DownloadStartupReconciler>().reconcile()
        }
        get<DownloadNotificationWatcher>().start()
        DownloadNetworkRestore.start(this)
        mediaRuntimeStarted = true
    }
}
