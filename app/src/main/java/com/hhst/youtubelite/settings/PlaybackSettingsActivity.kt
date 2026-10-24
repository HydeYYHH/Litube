package com.hhst.youtubelite.settings

import android.os.Bundle
import androidx.activity.compose.BackHandler
import com.hhst.youtubelite.ui.design.setAppContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import com.hhst.youtubelite.downloader.ui.DownloadUi
import com.hhst.youtubelite.extension.Extension
import com.hhst.youtubelite.ui.NativeActivity
import com.hhst.youtubelite.ui.extension.ExtensionScreen
import com.hhst.youtubelite.ui.extension.SettingsDataScreen
import com.hhst.youtubelite.ui.theme.AppTheme

class PlaybackSettingsActivity : NativeActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setAppContent {
            var data by rememberSaveable { mutableStateOf(false) }
            BackHandler { if (data) data = false else finish() }
            AppTheme {
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    if (data) SettingsDataScreen(onClose = { data = false }, onReloadPages = { data = false })
                    else ExtensionScreen(onClose = { finish() }, onNavigate = {
                        when (it) {
                            Extension.NAV_DATA -> data = true
                            Extension.NAV_DOWNLOADS -> DownloadUi.openManager(this@PlaybackSettingsActivity)
                        }
                    })
                }
            }
        }
    }
}
