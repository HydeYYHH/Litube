package com.hhst.youtubelite.settings

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Build
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import com.hhst.youtubelite.ui.design.setAppContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.hhst.youtubelite.extension.ExtensionManager
import com.hhst.youtubelite.settings.domain.SettingsPage
import com.hhst.youtubelite.settings.presentation.SettingsScreen
import com.hhst.youtubelite.ui.about.AboutActivity
import org.koin.android.ext.android.inject

class SettingsActivity : com.hhst.youtubelite.ui.NativeActivity() {
    private val extensionManager: ExtensionManager by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.light(Color.WHITE, Color.WHITE), SystemBarStyle.light(Color.WHITE, Color.WHITE))
        val initial = SettingsPage.parse(intent.getStringExtra(EXTRA_PAGE))
        setAppContent {
            var page by rememberSaveable { mutableStateOf(initial.argument) }
            var preferences by remember { mutableStateOf(extensionManager.allPreferences()) }
            val context = LocalContext.current
            var notifications by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        notifications = NotificationManagerCompat.from(context).areNotificationsEnabled()
                    }
                }
                // The Advanced host and backup imports write through the same manager; follow it.
                val preferenceListener: (String) -> Unit = { preferences = extensionManager.allPreferences() }
                extensionManager.addOnChangedListener(preferenceListener)
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                    extensionManager.removeOnChangedListener(preferenceListener)
                }
            }
            val current = SettingsPage.parse(page)
            BackHandler(enabled = current != SettingsPage.Home) { page = SettingsPage.Home.argument }
            SettingsScreen(
                page = current,
                preferences = preferences,
                notificationsEnabled = notifications,
                onNavigate = { page = it.argument },
                onToggle = { key, enabled ->
                    // The change listener above refreshes the map; this writes, never mirrors.
                    extensionManager.setEnabled(key, enabled)
                },
                onAbout = { startActivity(Intent(this@SettingsActivity, AboutActivity::class.java)) },
                onAdvanced = { startActivity(Intent(this@SettingsActivity, AdvancedActivity::class.java)) },
                onSelectTheme = { mode ->
                    extensionManager.setThemeMode(mode)
                    com.hhst.youtubelite.ui.theme.AppAppearance.update(mode)
                },
                onSelectLanguage = { tag ->
                    val handledBySystem = com.hhst.youtubelite.core.AppLocale.apply(this@SettingsActivity, tag)
                    if (!handledBySystem) recreate()
                },
                onOpenNotificationSettings = {
                    val settingsIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    } else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                    startActivity(settingsIntent)
                },
                onBack = {
                    if (current == SettingsPage.Home) finish() else page = SettingsPage.Home.argument
                },
                modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
            )
        }
    }

    companion object {
        const val EXTRA_PAGE = "settings_page"

        fun intent(context: Context, page: SettingsPage = SettingsPage.Home) =
            Intent(context, SettingsActivity::class.java).putExtra(EXTRA_PAGE, page.argument)
    }
}
