package com.hhst.youtubelite

import android.app.Application
import com.hhst.youtubelite.di.appModule
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

/** Process entry: MMKV, Koin, and poToken WebView warm-up. */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        MMKV.initialize(this)
        startKoin {
            androidLogger(Level.ERROR)
            androidContext(this@App)
            modules(appModule)
        }
        val poToken = get<PoTokenProvider>()
        poToken.initialize()
        Promise.DEFAULT_SCOPE.launch { poToken.warmUp() }
        // SimpleCache opens its SQLite index on first touch; keep that off
        // the main thread.
        Promise.DEFAULT_SCOPE.launch {
            PlayerDataSource.warmUp(this@App)
        }
    }
}
