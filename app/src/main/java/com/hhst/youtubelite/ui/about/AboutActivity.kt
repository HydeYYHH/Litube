package com.hhst.youtubelite.ui.about

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.hhst.youtubelite.extractor.Extractor
import com.hhst.youtubelite.ui.theme.AppTheme
import org.koin.java.KoinJavaComponent.get

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
                    onCopyDiagnostics = {
                        val extractor = get<Extractor>(Extractor::class.java)
                        getSystemService(ClipboardManager::class.java).setPrimaryClip(
                            ClipData.newPlainText("Extraction diagnostics", extractor.extractionDiagnostics()))
                    },
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
