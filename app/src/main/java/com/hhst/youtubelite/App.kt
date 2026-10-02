package com.hhst.youtubelite

import android.app.Application
import androidx.work.Configuration
import androidx.work.WorkManager
import com.hhst.youtubelite.core.Constants
import com.hhst.youtubelite.di.appModule
import com.hhst.youtubelite.downloader.notify.DownloadNotificationWatcher
import com.hhst.youtubelite.downloader.ui.DownloadUi
import com.hhst.youtubelite.downloader.work.DownloadStartupReconciler
import com.hhst.youtubelite.downloader.work.KoinDownloadWorkerFactory
import com.hhst.youtubelite.extractor.PoTokenProvider
import com.hhst.youtubelite.extractor.Promise
import com.hhst.youtubelite.player.datasource.PlayerDataSource
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.launch
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.logger.Level

/** Process entry: MMKV, Koin, WorkManager, and poToken WebView setup. */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        Constants.genuineUserAgent = android.webkit.WebSettings.getDefaultUserAgent(this)
        MMKV.initialize(this)
        startKoin {
            androidLogger(Level.ERROR)
            androidContext(this@App)
            modules(appModule)
        }
        WorkManager.initialize(
            this,
            Configuration.Builder()
                .setWorkerFactory(KoinDownloadWorkerFactory())
                .build(),
        )
        val poToken = get<PoTokenProvider>()
        poToken.initialize()
        Promise.DEFAULT_SCOPE.launch { poToken.precache() }
        Promise.DEFAULT_SCOPE.launch {
            PlayerDataSource.precache(this@App)
        }
        Promise.DEFAULT_SCOPE.launch {
            get<DownloadStartupReconciler>().reconcile()
        }
        get<DownloadNotificationWatcher>().start()
        DownloadNetworkRestore.start(this)
    }
}
