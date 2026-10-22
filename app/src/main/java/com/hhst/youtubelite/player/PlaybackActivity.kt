package com.hhst.youtubelite.player

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
import android.os.PowerManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Rational
import android.widget.Toast
import androidx.annotation.RequiresApi
import com.hhst.youtubelite.core.PipSupport
import com.hhst.youtubelite.core.PipAutoEnter
import com.hhst.youtubelite.extension.ExtensionManager
import com.hhst.youtubelite.extension.PreferenceKeys
import com.hhst.youtubelite.player.service.NotificationPermission
import com.hhst.youtubelite.player.surface.PlayerUi
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.android.ext.android.inject

import com.hhst.youtubelite.R

/** Lifecycle and system controls for the retained playback engine. */
abstract class PlaybackActivity : com.hhst.youtubelite.ui.NativeActivity() {
    private val prefs: ExtensionManager by inject()
    protected val playerViewModel: PlayerViewModel by inject()

    @Volatile
    private var pipEligibilityLocked = false

    /** Tracks the current playback window for auto-PiP. */
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

    /** PiP mode changes without recreating the playback window. */
    protected val inPip = MutableStateFlow(false)
    private var pipWasActive = false

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
        PlaybackSession.register(playerViewModel)
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
        lifecycleScope.launch { playerViewModel.uiState.collect { state ->
            if (!pipEligibilityLocked) {
                playerActive = state.visible && !state.mini
                playerPlaying = state.isPlaying
                playerWidth = state.videoWidth
                playerHeight = state.videoHeight
            }
            syncPipParams()
        } }
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !isInPictureInPictureMode) pipWasActive = false
        playerViewModel.returnFromAudioOnlyBackground()
    }

    override fun onStop() {
        super.onStop()
        closeStoppedPip()
    }

    private fun closeStoppedPip() {
        // PiP mode can remain set after the window is dismissed.
        if (!pipWasActive || isChangingConfigurations || playerViewModel.audioOnlyBackground) return
        if (!isFinishing && getSystemService(PowerManager::class.java)?.isInteractive == false) return
        pipWasActive = false
        playerViewModel.onPipDismissed()
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
        if (isInPictureInPictureMode) pipWasActive = true
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
        closeStoppedPip()
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
    }
}
