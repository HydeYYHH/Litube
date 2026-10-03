package com.hhst.youtubelite.ui.about

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.hhst.youtubelite.ui.theme.AppTheme

/** App info screen opened from the page's settings entries. */
class AboutActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                AboutScreen(
                    label = applicationInfo.loadLabel(packageManager).toString(),
                    version = versionName(),
                    onClose = { finish() },
                )
            }
        }
    }

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
    } catch (_: Exception) {
        ""
    }
}
