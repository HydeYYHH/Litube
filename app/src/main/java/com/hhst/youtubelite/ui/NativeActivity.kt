package com.hhst.youtubelite.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.hhst.youtubelite.search.SearchResultsActivity

abstract class NativeActivity : ComponentActivity() {
    private var navigationPip: com.hhst.youtubelite.core.PipAutoEnter.Handle? = null

    override fun startActivity(intent: Intent) {
        suppressNavigationPip(intent)
        super.startActivity(intent)
    }

    override fun startActivity(intent: Intent, options: android.os.Bundle?) {
        suppressNavigationPip(intent)
        super.startActivity(intent, options)
    }

    private fun suppressNavigationPip(intent: Intent) {
        if (intent.component?.packageName == packageName && navigationPip == null) {
            navigationPip = com.hhst.youtubelite.core.PipAutoEnter.suppress()
        }
    }

    override fun onResume() {
        super.onResume()
        navigationPip?.restore()
        navigationPip = null
    }

    @Suppress("DEPRECATION")
    override fun onPostResume() {
        super.onPostResume()
        val dark = com.hhst.youtubelite.ui.theme.YoutubeAppearance.dark ?: (
            resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES)
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = if (dark) 0xFF0F0F0F.toInt() else android.graphics.Color.WHITE
    }

    override fun onDestroy() {
        navigationPip?.restore()
        navigationPip = null
        super.onDestroy()
    }

    private val voiceSearch = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
                ?.trim()?.takeIf(String::isNotEmpty)?.let { startActivity(SearchResultsActivity.intent(this, it)) }
        }
    }

    fun openVoiceSearch() {
        try {
            voiceSearch.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, "Search"))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "Voice search is unavailable on this device", Toast.LENGTH_LONG).show()
        }
    }
}
