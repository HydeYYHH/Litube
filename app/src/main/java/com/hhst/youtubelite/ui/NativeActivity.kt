package com.hhst.youtubelite.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.hhst.youtubelite.data.media.ContentRepositories
import com.hhst.youtubelite.search.SearchResultsActivity

abstract class NativeActivity : ComponentActivity() {
    private var navigationPip: com.hhst.youtubelite.core.PipAutoEnter.Handle? = null
    private var appearanceListener: (() -> Unit)? = null
    private var appliedLanguage: String? = null

    /** The persisted app language wraps every activity context below API 33. */
    override fun attachBaseContext(newBase: android.content.Context) {
        appliedLanguage = com.hhst.youtubelite.core.AppLocale.tag(newBase)
        super.attachBaseContext(com.hhst.youtubelite.core.AppLocale.wrap(newBase))
    }

    /** Navigates to another in-app screen, carrying this screen's debug fixture scene with it. */
    protected fun launchNavigation(intent: Intent) {
        if (ContentRepositories.fixtures(this.intent)) intent.putExtra(ContentRepositories.EXTRA_FIXTURE_SCENE, true)
        startActivity(intent)
    }

    override fun startActivity(intent: Intent) {
        startActivity(intent, null)
    }

    override fun startActivity(intent: Intent, options: android.os.Bundle?) {
        suppressNavigationPip(intent)
        val internal = intent.component?.packageName == packageName
        val watch = intent.component?.className == com.hhst.youtubelite.watch.WatchActivity::class.java.name
        if (watch && !intent.hasExtra(com.hhst.youtubelite.navigation.WatchTransition.ORIGIN)) {
            val id = intent.getStringExtra(com.hhst.youtubelite.watch.WatchActivity.EXTRA_VIDEO_ID)
            if (id != null) com.hhst.youtubelite.navigation.WatchTransition.prepare(window.decorView, id, intent)
        }
        val instant = internal && (watch || intent.getBooleanExtra(com.hhst.youtubelite.navigation.WatchTransition.INSTANT, false))
        super.startActivity(intent, options ?: if (instant) android.app.ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle() else null)
    }

    private fun suppressNavigationPip(intent: Intent) {
        if (intent.component?.packageName == packageName && navigationPip == null) {
            navigationPip = com.hhst.youtubelite.core.PipAutoEnter.suppressForNavigation(this)
        }
    }

    override fun onResume() {
        super.onResume()
        if (android.os.Build.VERSION.SDK_INT < 33 &&
            appliedLanguage != com.hhst.youtubelite.core.AppLocale.tag(this)) {
            recreate()
            return
        }
        navigationPip?.restore()
        navigationPip = null
        // Live system-bar response to in-app appearance changes (Compose handles content).
        appearanceListener = com.hhst.youtubelite.ui.theme.AppAppearance.onChange { applySystemBarStyle() }
        applySystemBarStyle()
    }

    override fun onPause() {
        appearanceListener?.invoke()
        appearanceListener = null
        super.onPause()
    }

    @Suppress("DEPRECATION")
    override fun onPostResume() {
        super.onPostResume()
        applySystemBarStyle()
    }

    @Suppress("DEPRECATION")
    private fun applySystemBarStyle() {
        val dark = com.hhst.youtubelite.ui.theme.AppAppearance.dark ?:
            (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES)
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = if (dark) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }

    override fun onDestroy() {
        navigationPip?.restore()
        navigationPip = null
        clearVoiceSession()
        super.onDestroy()
    }

    private val voiceSearch = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
                ?.trim()?.takeIf(String::isNotEmpty)?.let { launchNavigation(SearchResultsActivity.intent(this, it)) }
        }
    }

    private var inProcessRecognizer: android.speech.SpeechRecognizer? = null
    private var pendingVoiceIntent: Intent? = null
    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val pending = pendingVoiceIntent
        pendingVoiceIntent = null
        if (granted && pending != null && !isFinishing && !isDestroyed) startInProcessVoiceSearch(pending)
    }

    fun openVoiceSearch() {
        clearVoiceSession()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        try {
            voiceSearch.launch(intent)
            return
        } catch (_: ActivityNotFoundException) {
            startInProcessVoiceSearch(intent)
        }
    }

    private fun startInProcessVoiceSearch(intent: Intent) {
        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(this)) {
            voiceUnavailable()
            return
        }
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            pendingVoiceIntent = intent
            microphonePermission.launch(android.Manifest.permission.RECORD_AUDIO)
            return
        }
        clearVoiceSession()
        try {
            val recognizer = android.speech.SpeechRecognizer.createSpeechRecognizer(this)
            inProcessRecognizer = recognizer
            recognizer.setRecognitionListener(object : android.speech.RecognitionListener {
                override fun onResults(results: android.os.Bundle) {
                    if (inProcessRecognizer !== recognizer) return
                    clearVoiceSession()
                    results.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.trim()?.takeIf(String::isNotEmpty)
                        ?.let { launchNavigation(SearchResultsActivity.intent(this@NativeActivity, it)) }
                        ?: voiceUnavailable()
                }
                override fun onError(error: Int) {
                    if (inProcessRecognizer !== recognizer) return
                    clearVoiceSession()
                    voiceUnavailable()
                }
                override fun onReadyForSpeech(params: android.os.Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: android.os.Bundle?) {}
                override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
            })
            recognizer.startListening(intent)
        } catch (failure: RuntimeException) {
            clearVoiceSession()
            com.hhst.youtubelite.diagnostics.AppLog.event(
                com.hhst.youtubelite.diagnostics.AppLog.Category.APP, "voice_search_unavailable", failure = failure)
            voiceUnavailable()
        }
    }

    override fun onStop() {
        dismissMessage()
        clearVoiceSession()
        super.onStop()
    }

    private fun clearVoiceSession() {
        val recognizer = inProcessRecognizer
        inProcessRecognizer = null
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
    }

    private fun voiceUnavailable() {
        showMessage(getString(com.hhst.youtubelite.R.string.application_not_found))
    }
}
