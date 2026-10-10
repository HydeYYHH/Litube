package com.hhst.youtubelite

import com.hhst.youtubelite.diagnostics.AppLog

import android.app.PictureInPictureParams
import android.app.PendingIntent
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Rational
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.hhst.youtubelite.browser.PageKind
import com.hhst.youtubelite.browser.UrlPolicy
import com.hhst.youtubelite.core.PipSupport
import com.hhst.youtubelite.core.PipAutoEnter
import com.hhst.youtubelite.downloader.core.DownloadShareParser
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

    @Volatile
    private var pipEligibilityLocked = false

    /** Set by BrowserScreen while the player overlay is eligible for auto-PiP. */
    @Volatile
    private var playerActive = false
    @Volatile
    private var playerPlaying = false
    @Volatile
    private var playerWidth = 0
    @Volatile
    private var playerHeight = 0

    /**
     * Instrumentation: hold player PiP eligibility without a live stream so
     * API 31+ [PictureInPictureParams.setAutoEnterEnabled] can be asserted
     * against real DownloadActivity / sheet / share overlays.
     */
    fun setPlayerPipEligible(eligible: Boolean) {
        pipEligibilityLocked = eligible
        playerActive = eligible
        playerPlaying = eligible
        if (eligible && (playerWidth <= 0 || playerHeight <= 0)) {
            playerWidth = 1280
            playerHeight = 720
        }
        syncPipParams()
    }

    /** Share-intent URL consumed once by BrowserScreen. */
    private val sharedUrl = MutableStateFlow<String?>(null)

    /** Playlist share: open the page, then batch the already-loaded snapshot. */
    private val pendingPlaylistDownload = MutableStateFlow<String?>(null)

    /** PiP mode state consumed by BrowserScreen (no recreate on config change). */
    private val inPip = MutableStateFlow(false)

    private var pipPrefListenerRef: ((String) -> Unit)? = null

    private val pipActionsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !isInPictureInPictureMode) return
            when (intent.action) {
                ACTION_PIP_AUDIO -> {
                    if (!prefs.isEnabled(PreferenceKeys.ENABLE_BACKGROUND_PLAY)) return
                    playerViewModel.playAudioOnlyInBackground()
                    syncPipParams()
                    // The process-scoped player and media service outlive this
                    // activity. Finishing removes the pinned window immediately.
                    finish()
                }
                ACTION_PIP_TOGGLE -> playerViewModel.onPlayPause()
                ACTION_PIP_PREVIOUS -> playerViewModel.onPrevious()
                ACTION_PIP_NEXT -> playerViewModel.onNext()
            }
        }
    }

    override fun startActivity(intent: Intent) {
        PipAutoEnter.noteLegacyLaunch()
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
        PipAutoEnter.noteLegacyLaunch()
        super.startActivityForResult(intent, requestCode, options)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        PipAutoEnter.register(this)
        ContextCompat.registerReceiver(this, pipActionsReceiver,
            IntentFilter().apply {
                addAction(ACTION_PIP_AUDIO)
                addAction(ACTION_PIP_TOGGLE)
                addAction(ACTION_PIP_PREVIOUS)
                addAction(ACTION_PIP_NEXT)
            }, ContextCompat.RECEIVER_NOT_EXPORTED)
        if (prefs.isEnabled(PreferenceKeys.ENABLE_BACKGROUND_PLAY)) {
            NotificationPermission.requestIfNeeded(this)
        }
        // Skip on recreate: the launch intent is redelivered after process death.
        if (savedInstanceState == null) {
            handleLaunchIntent(intent)
            if (sharedUrl.value == null && playerViewModel.audioOnlyBackground) {
                sharedUrl.value = playerViewModel.uiState.value.url
            }
        }
        setContent {
            AppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    BrowserScreen(
                        sharedUrl = sharedUrl,
                        pendingPlaylistUrl = pendingPlaylistDownload,
                        inPipFlow = inPip,
                        onSharedUrlConsumed = { sharedUrl.value = null },
                        onPendingPlaylistConsumed = { pendingPlaylistDownload.value = null },
                        onPlayerActiveChanged = { active, playing, width, height ->
                            if (pipEligibilityLocked) return@BrowserScreen
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
            if (key == PreferenceKeys.ENABLE_PIP || key == PreferenceKeys.ENABLE_BACKGROUND_PLAY || key == "*") {
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
        lifecycleScope.launch { playerViewModel.uiState.collect { syncPipParams() } }
    }

    override fun onResume() {
        super.onResume()
        playerViewModel.returnFromAudioOnlyBackground()
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
        if (intent?.getBooleanExtra(DownloadShareParser.EXTRA_PENDING_PLAYLIST, false) == true) {
            val url = extractSharedUrl(intent)
            if (url != null) {
                sharedUrl.value = url
                pendingPlaylistDownload.value = url
            }
            return
        }
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
        if (!PipSupport.isSupported(this) || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return
        if (SystemClock.elapsedRealtime() < PipAutoEnter.legacySuppressUntil) return
        if (PipAutoEnter.isSuppressed()) return
        if (pipEligible()) {
            runCatching { enterPictureInPictureMode(pipParams()) }
        }
    }

    /** PiP enters/exits without a recreate (configChanges); push it to Compose. */
    // The two-arg hook is the framework dispatch path; the one-arg form lacks the config.
    @Suppress("DEPRECATION", "OverridingDeprecatedMember")
    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        }
        inPip.value = isInPictureInPictureMode
        AppLog.event(AppLog.Category.APP, "pip_state", mapOf("active" to isInPictureInPictureMode))
    }

    private fun pipEligible(): Boolean =
        PipSupport.isSupported(this) && playerActive && playerPlaying &&
            !playerViewModel.audioOnlyBackground && prefs.isEnabled(PreferenceKeys.ENABLE_PIP)

    @RequiresApi(Build.VERSION_CODES.O)
    private fun pipParams(): PictureInPictureParams {
        val (n, d) = PlayerUi.pipAspect(playerWidth, playerHeight)
        return PictureInPictureParams.Builder().setAspectRatio(Rational(n, d)).setActions(pipActions()).build()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun pipActions(): List<RemoteAction> {
        fun action(name: String, icon: Int, label: Int): RemoteAction {
            val title = getString(label)
            val intent = Intent(name).setPackage(packageName)
            val pending = PendingIntent.getBroadcast(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            return RemoteAction(Icon.createWithResource(this, icon), title, title, pending)
        }
        val state = playerViewModel.uiState.value
        return buildList {
            if (prefs.isEnabled(PreferenceKeys.ENABLE_BACKGROUND_PLAY)) {
                add(action(ACTION_PIP_AUDIO, R.drawable.ic_headphones, R.string.player_audio_only))
            } else {
                add(action(ACTION_PIP_PREVIOUS, R.drawable.ic_pip_previous, R.string.action_previous)
                    .apply { isEnabled = state.hasPrevious })
            }
            add(if (state.isPlaying) action(ACTION_PIP_TOGGLE, R.drawable.ic_pause, R.string.action_pause)
                else action(ACTION_PIP_TOGGLE, R.drawable.ic_play, R.string.action_play))
            add(action(ACTION_PIP_NEXT, R.drawable.ic_pip_next, R.string.action_next)
                .apply { isEnabled = state.hasNext })
        }
    }

    private fun syncPipParams() {
        if (!PipSupport.isSupported(this)) return
        val (n, d) = PlayerUi.pipAspect(playerWidth, playerHeight)
        PipAutoEnter.apply(
            this,
            autoEnter = PipAutoEnter.shouldAutoEnter(pipEligible(), Build.VERSION.SDK_INT),
            aspect = Rational(n, d),
            actions = pipActions(),
        )
    }

    override fun onDestroy() {
        PipAutoEnter.unregister(this)
        unregisterReceiver(pipActionsReceiver)
        pipPrefListenerRef?.let { prefs.removeOnChangedListener(it) }
        pipPrefListenerRef = null
        inPip.value = false
        super.onDestroy()
    }

    companion object {
        private const val ACTION_PIP_AUDIO = "com.hhst.youtubelite.PIP_AUDIO"
        private const val ACTION_PIP_TOGGLE = "com.hhst.youtubelite.PIP_TOGGLE"
        private const val ACTION_PIP_PREVIOUS = "com.hhst.youtubelite.PIP_PREVIOUS"
        private const val ACTION_PIP_NEXT = "com.hhst.youtubelite.PIP_NEXT"
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
