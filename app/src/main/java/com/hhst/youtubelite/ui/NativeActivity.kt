package com.hhst.youtubelite.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.hhst.youtubelite.data.media.ContentRepositories
import com.hhst.youtubelite.search.SearchResultsActivity
import com.hhst.youtubelite.R

abstract class NativeActivity : ComponentActivity() {
    companion object {
        internal const val EXTRA_TAB_NAVIGATION = "navigation_tab"
        internal const val EXTRA_TAB_BACKWARD = "navigation_tab_backward"
        internal fun tabAnimations(backwards: Boolean): Pair<Int, Int> = if (backwards)
            R.anim.navigation_tab_backward_enter to R.anim.navigation_tab_backward_exit
        else R.anim.navigation_tab_forward_enter to R.anim.navigation_tab_forward_exit
    }

    private enum class NavigationAnimation { Detail, Tab, Instant }
    private var navigationAnimation = NavigationAnimation.Detail
    private var navigationBackwards = false
    private var navigationPip: com.hhst.youtubelite.core.PipAutoEnter.Handle? = null
    private var appearanceListener: (() -> Unit)? = null
    private var appliedLanguage: String? = null

    /** The persisted app language wraps every activity context below API 33. */
    override fun attachBaseContext(newBase: android.content.Context) {
        appliedLanguage = com.hhst.youtubelite.core.AppLocale.tag(newBase)
        super.attachBaseContext(com.hhst.youtubelite.core.AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyWindowBackground(darkAppearance())
        navigationAnimation = NavigationAnimation.entries.firstOrNull {
            it.name == savedInstanceState?.getString("navigation_animation")
        } ?: animationFor(intent)
        navigationBackwards = savedInstanceState?.getBoolean(EXTRA_TAB_BACKWARD) ?: intent.getBooleanExtra(EXTRA_TAB_BACKWARD, false)
        applyCloseTransition()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("navigation_animation", navigationAnimation.name)
        outState.putBoolean(EXTRA_TAB_BACKWARD, navigationBackwards)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        navigationAnimation = animationFor(intent)
        navigationBackwards = intent.getBooleanExtra(EXTRA_TAB_BACKWARD, false)
        applyCloseTransition()
    }

    private fun animationFor(intent: Intent): NavigationAnimation = when {
        intent.getBooleanExtra(com.hhst.youtubelite.navigation.WatchTransition.INSTANT, false) -> NavigationAnimation.Instant
        intent.getBooleanExtra(EXTRA_TAB_NAVIGATION, false) -> NavigationAnimation.Tab
        else -> NavigationAnimation.Detail
    }

    private fun motionEnabled(): Boolean = com.hhst.youtubelite.ui.design.readMotionScale(contentResolver) > 0f

    private fun closeAnimation(): Pair<Int, Int>? {
        // Watch owns the player/mini handoff. Leaving the task keeps Android's
        // launcher transition, including its predictive back preview.
        if (this is com.hhst.youtubelite.watch.WatchActivity || isTaskRoot) return null
        if (!motionEnabled() || navigationAnimation == NavigationAnimation.Instant) return 0 to 0
        return if (navigationAnimation == NavigationAnimation.Tab) tabAnimations(!navigationBackwards)
        else R.anim.navigation_detail_return to R.anim.navigation_detail_close
    }

    private fun applyCloseTransition() {
        if (Build.VERSION.SDK_INT < 34 || this is com.hhst.youtubelite.watch.WatchActivity) return
        val animation = closeAnimation()
        if (animation == null) clearOverrideActivityTransition(OVERRIDE_TRANSITION_CLOSE)
        else overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, animation.first, animation.second)
    }

    @Suppress("DEPRECATION")
    override fun finish() {
        val animation = if (Build.VERSION.SDK_INT < 34) closeAnimation() else null
        applyCloseTransition()
        super.finish()
        if (animation != null) overridePendingTransition(animation.first, animation.second)
    }

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
        val sheet = intent.component?.className == com.hhst.youtubelite.downloader.ui.DownloadSheetActivity::class.java.name
        if (watch && this is com.hhst.youtubelite.watch.WatchActivity) {
            // Selecting another recommendation keeps the same watch host and
            // its browse destination; the watch page is never its own backdrop.
            intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        } else if (watch && !intent.hasExtra(com.hhst.youtubelite.navigation.WatchTransition.ORIGIN)) {
            val id = intent.getStringExtra(com.hhst.youtubelite.watch.WatchActivity.EXTRA_VIDEO_ID)
            if (id != null) com.hhst.youtubelite.navigation.WatchTransition.prepare(window.decorView, id, intent)
        }
        val animation = if (!internal || options != null) null else {
            val mode = animationFor(intent)
            val resources = when {
                // The translucent download host already animates its sheet.
                watch || sheet || mode == NavigationAnimation.Instant || !motionEnabled() -> 0 to 0
                mode == NavigationAnimation.Tab -> tabAnimations(intent.getBooleanExtra(EXTRA_TAB_BACKWARD, false))
                else -> R.anim.navigation_detail_enter to R.anim.navigation_detail_exit
            }
            android.app.ActivityOptions.makeCustomAnimation(this, resources.first, resources.second).toBundle()
        }
        super.startActivity(intent, options ?: animation)
    }

    private fun suppressNavigationPip(intent: Intent) {
        if (intent.component?.packageName == packageName && navigationPip == null) {
            navigationPip = com.hhst.youtubelite.core.PipAutoEnter.suppressForNavigation(this)
        }
    }

    override fun onResume() {
        super.onResume()
        applyCloseTransition()
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
    protected open fun applySystemBarStyle() {
        val dark = darkAppearance()
        applyWindowBackground(dark)
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = if (dark) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }

    private fun darkAppearance(): Boolean = com.hhst.youtubelite.ui.theme.AppAppearance.dark ?:
        (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES)

    private fun applyWindowBackground(dark: Boolean) {
        // Playback owns its artwork/backdrop handoff. Translucent and floating
        // hosts must keep exposing the window beneath their panels.
        if (this is com.hhst.youtubelite.player.PlaybackActivity) return
        val attribute = android.util.TypedValue()
        val translucent = theme.resolveAttribute(android.R.attr.windowIsTranslucent, attribute, true) && attribute.data != 0
        val floating = theme.resolveAttribute(android.R.attr.windowIsFloating, attribute, true) && attribute.data != 0
        if (!translucent && !floating) window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(
            if (dark) android.graphics.Color.BLACK else android.graphics.Color.WHITE))
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
