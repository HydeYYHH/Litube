package com.hhst.youtubelite

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Rational
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.hhst.youtubelite.browser.PageKind
import com.hhst.youtubelite.browser.UrlPolicy
import com.hhst.youtubelite.extension.ExtensionManager
import com.hhst.youtubelite.extension.PreferenceKeys
import com.hhst.youtubelite.player.PlayerViewModel
import com.hhst.youtubelite.player.service.NotificationPermission
import com.hhst.youtubelite.player.surface.PlayerUi
import com.hhst.youtubelite.ui.browser.BrowserScreen
import com.hhst.youtubelite.ui.theme.AppTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.android.ext.android.inject

/** Single-activity Compose host; edge-to-edge system bars, PiP, share intents. */
class MainActivity : ComponentActivity() {

    private val prefs: ExtensionManager by inject()
    private val playerViewModel: PlayerViewModel by inject()

    /** Set by BrowserScreen while the player overlay is eligible for auto-PiP. */
    @Volatile
    private var playerActive = false
    @Volatile
    private var playerPlaying = false
    @Volatile
    private var playerWidth = 0
    @Volatile
    private var playerHeight = 0

    /** Share-intent URL consumed once by BrowserScreen. */
    private val sharedUrl = MutableStateFlow<String?>(null)

    /** PiP mode state consumed by BrowserScreen (no recreate on config change). */
    private val inPip = MutableStateFlow(false)

    private var pipPrefListenerRef: ((String) -> Unit)? = null

    /**
     * App-initiated launches (share chooser, external links) also raise
     * [onUserLeaveHint]; on API 26-30 that would shrink the playing video
     * into PiP behind the other window. Timestamp every launch and skip
     * legacy PiP entry for a short window after it.
     */
    @Volatile
    private var suppressPipUntil = 0L

    override fun startActivity(intent: Intent) {
        suppressPipUntil = SystemClock.elapsedRealtime() + SUPPRESS_PIP_WINDOW_MS
        super.startActivity(intent)
    }

    // Overriding a deprecated member is intentional: this is the only hook
    // that covers every launch path for the PiP suppression window.
    @Suppress("DEPRECATION", "OverridingDeprecatedMember")
    @Deprecated(
        "Deprecated in Java",
        ReplaceWith("super.startActivityForResult(intent, requestCode, options)"),
    )
    override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) {
        suppressPipUntil = SystemClock.elapsedRealtime() + SUPPRESS_PIP_WINDOW_MS
        super.startActivityForResult(intent, requestCode, options)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (prefs.isEnabled(PreferenceKeys.ENABLE_BACKGROUND_PLAY)) {
            NotificationPermission.requestIfNeeded(this)
        }
        // Skip on recreate: the launch intent is redelivered after process death.
        if (savedInstanceState == null) {
            handleLaunchIntent(intent)
        }
        setContent {
            AppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    BrowserScreen(
                        sharedUrl = sharedUrl,
                        inPipFlow = inPip,
                        onSharedUrlConsumed = { sharedUrl.value = null },
                        onPlayerActiveChanged = { active, playing, width, height ->
                            playerActive = active
                            playerPlaying = playing
                            playerWidth = width
                            playerHeight = height
                            syncPipParams()
                        },
                    )
                }
            }
        }

        // syncPipParams otherwise only runs on the next player-state change:
        // toggling ENABLE_PIP mid-playback would still auto-enter on Home
        // until then. resetToDefault broadcasts "*" (reset all keys).
        val pipPrefListener: (String) -> Unit = { key ->
            if (key == PreferenceKeys.ENABLE_PIP || key == "*") {
                runOnUiThread {
                    // Manual-PiP button state (PlayerUiState.pipAvailable) is
                    // otherwise stale until the next video starts.
                    playerViewModel.refreshPipAvailability()
                    syncPipParams()
                }
            }
        }
        prefs.addOnChangedListener(pipPrefListener)
        pipPrefListenerRef = pipPrefListener
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    /**
     * Routes a share / open-with intent into the browser, or toasts when the
     * intent carried no usable link. Plain launches (ACTION_MAIN) pass
     * through silently.
     */
    private fun handleLaunchIntent(intent: Intent?) {
        val url = extractSharedUrl(intent)
        if (url != null) {
            sharedUrl.value = url
        } else if (intent != null &&
            (intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_VIEW)
        ) {
            Toast.makeText(this, R.string.share_no_link_found, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // API 31+ uses PictureInPictureParams.setAutoEnterEnabled.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return
        // Skip app-initiated leaves (share chooser, external links) — see
        // [suppressPipUntil].
        if (SystemClock.elapsedRealtime() < suppressPipUntil) return
        // minSdk 26: PictureInPictureParams exists everywhere (no O check).
        if (pipEligible()) {
            runCatching { enterPictureInPictureMode(pipParams(autoEnter = false)) }
        }
    }

    /** PiP enters/exits without a recreate (configChanges); push it to Compose. */
    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip.value = isInPictureInPictureMode
    }

    private fun pipEligible(): Boolean =
        playerActive && playerPlaying && prefs.isEnabled(PreferenceKeys.ENABLE_PIP)

    private fun pipParams(autoEnter: Boolean): PictureInPictureParams {
        val (n, d) = PlayerUi.pipAspect(playerWidth, playerHeight)
        val builder = PictureInPictureParams.Builder().setAspectRatio(Rational(n, d))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(autoEnter)
        }
        return builder.build()
    }

    private fun syncPipParams() {
        // minSdk 26: setPictureInPictureParams exists everywhere.
        runCatching { setPictureInPictureParams(pipParams(autoEnter = pipEligible())) }
    }

    override fun onDestroy() {
        pipPrefListenerRef?.let { prefs.removeOnChangedListener(it) }
        pipPrefListenerRef = null
        inPip.value = false
        super.onDestroy()
    }

    companion object {
        /** How long app-initiated activity launches suppress legacy PiP entry. */
        private const val SUPPRESS_PIP_WINDOW_MS = 1_500L

        /** Any http(s) URL in shared text; sentence punctuation excluded. */
        private val URL_RE = Regex("""https?://\S+[^\s.,;:!?)]""")

        private fun extractSharedUrl(intent: Intent?): String? {
            if (intent == null) return null
            val candidate = when (intent.action) {
                Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
                Intent.ACTION_VIEW -> intent.dataString
                else -> null
            } ?: return null
            // Shared text often bundles a source link plus the video link;
            // take the first URL the browser can actually route, not just
            // the first URL in the text.
            return URL_RE.findAll(candidate)
                .map { it.value }
                .firstOrNull { UrlPolicy.isAllowedUrl(it) && PageKind.of(it) != "unknown" }
        }
    }
}
