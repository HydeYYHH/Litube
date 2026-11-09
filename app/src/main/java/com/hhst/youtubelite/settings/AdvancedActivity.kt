package com.hhst.youtubelite.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.BackHandler
import com.hhst.youtubelite.ui.design.setAppContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.hhst.youtubelite.downloader.ui.DownloadUi
import com.hhst.youtubelite.extension.ExtensionManager
import com.hhst.youtubelite.settings.presentation.AdvancedScreen
import com.hhst.youtubelite.settings.presentation.HapticsSettings
import com.hhst.youtubelite.ui.extension.SettingsDataScreen
import org.koin.android.ext.android.inject

/** Native Advanced settings host; preferences write through the shared manager. */
class AdvancedActivity : com.hhst.youtubelite.ui.NativeActivity() {
    private val extensionManager: ExtensionManager by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setAppContent {
            var data by remember { mutableStateOf(false) }
            var preferences by remember { mutableStateOf(extensionManager.allPreferences()) }
            DisposableEffect(extensionManager) {
                val listener: (String) -> Unit = { preferences = extensionManager.allPreferences() }
                extensionManager.addOnChangedListener(listener)
                onDispose { extensionManager.removeOnChangedListener(listener) }
            }
            BackHandler { if (data) data = false else finish() }
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                if (data) SettingsDataScreen(onClose = { data = false }, onReloadPages = { data = false })
                else AdvancedScreen(
                    preferences = preferences,
                    onToggle = { key, enabled -> extensionManager.setEnabled(key, enabled) },
                    onGestureChange = { extensionManager.importPreferences(it, null) },
                    onBack = { finish() },
                    onOpenData = { data = true },
                    onOpenDownloads = { DownloadUi.openManager(this@AdvancedActivity) },
                    haptics = remember {
                        HapticsSettings(extensionManager.hapticStrength()) { extensionManager.setHapticStrength(it) }
                    },
                )
            }
        }
    }

    companion object {
        fun intent(context: Context) = Intent(context, AdvancedActivity::class.java)
    }
}
