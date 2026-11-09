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

abstract class PlaybackActivity : com.hhst.youtubelite.ui.NativeActivity() {
    private val prefs: ExtensionManager by inject()
    protected val playerViewModel: PlayerViewModel by inject()

    @Volatile
    private var pipEligibilityLocked = false

    @Volatile
    private var playerActive = false
    @Volatile
    private var playerPlaying = false
    @Volatile
    private var playerWidth = 0
    @Volatile
    private var playerHeight = 0

    /**
     * Instrumentation hook: holds PiP eligibility without a live stream so API 31+
     * [PictureInPictureParams.setAutoEnterEnabled] can be asserted against real overlays.
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

    protected val inPip = MutableStateFlow(false)
    private var pipWasActive = false

    private var pipPrefListenerRef: ((String) -> Unit)? = null
    private val pipActionIntents = mutableMapOf<String, PendingIntent>()

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
        // Session-owned progress reporting: the loop samples this playback as long
        // as the process keeps it, not just while an activity is started.
        val reportingPlayer = playerViewModel
        org.koin.java.KoinJavaComponent.getKoin()
            .get<com.hhst.youtubelite.data.media.PlaybackWatchReporting>().attach {
                val state = reportingPlayer.uiState.value
                com.hhst.youtubelite.data.media.PlaybackSample(state.videoId, reportingPlayer.positionState.value,
                    state.durationMs, state.isPlaying, state.discontinuityEpoch)
            }
        PipAutoEnter.register(this)
        ContextCompat.registerReceiver(this, pipActionsReceiver,
            IntentFilter().apply {
                addAction(ACTION_PIP_AUDIO)
                addAction(ACTION_PIP_TOGGLE)
                addAction(ACTION_PIP_PREVIOUS)
                addAction(ACTION_PIP_NEXT)
            }, ContextCompat.RECEIVER_NOT_EXPORTED)
        if (prefs.isEnabled(PreferenceKeys.ENABLE_BACKGROUND_PLAY) &&
            !com.hhst.youtubelite.data.media.ContentSource.fixtures) {
            // Offline fixture hosts never play, so they never post the notification.
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
                // Buffering with a live play intent still owns a PiP entry: a
                // video still resolving its stream must not lose auto-PiP
                // because isPlaying has not flipped yet.
                playerPlaying = state.isPlaying || state.loading
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
        PlaybackSession.updatePipHost(this, isInPictureInPictureMode)
        if (isInPictureInPictureMode) {
            pipWasActive = true
            playerViewModel.onPipEntered()
        }
        AppLog.event(AppLog.Category.APP, "pip_state", mapOf("active" to isInPictureInPictureMode))
    }

    private fun pipEligible(): Boolean =
        PipSupport.isSupported(this) && playerActive && playerPlaying &&
            !playerViewModel.audioOnlyBackground && prefs.isEnabled(PreferenceKeys.ENABLE_PIP) &&
            playerViewModel.currentMediaKind != MediaKind.SHORTS

    @RequiresApi(Build.VERSION_CODES.O)
    private fun pipParams(): PictureInPictureParams {
        val (n, d) = PlayerUi.pipAspect(playerWidth, playerHeight)
        return PictureInPictureParams.Builder().setAspectRatio(Rational(n, d)).setActions(pipActions()).build()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun pipActions(): List<RemoteAction> {
        fun action(name: String, icon: Int, label: Int): RemoteAction {
            val title = getString(label)
            val pending = pipActionIntents.getOrPut(name) {
                val intent = Intent(name).setPackage(packageName)
                PendingIntent.getBroadcast(this, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            }
            return RemoteAction(Icon.createWithResource(this, icon), title, title, pending)
        }
        val state = playerViewModel.uiState.value
        val playPauseAction = PlayerUi.playPauseAction(state)
        return buildList {
            if (prefs.isEnabled(PreferenceKeys.ENABLE_BACKGROUND_PLAY)) {
                add(action(ACTION_PIP_AUDIO, R.drawable.ic_headphones, R.string.player_audio_only))
            } else {
                add(action(ACTION_PIP_PREVIOUS, R.drawable.ic_pip_previous, R.string.action_previous)
                    .apply { isEnabled = state.hasPrevious })
            }
            add(action(ACTION_PIP_TOGGLE, playPauseAction.playerIcon, playPauseAction.label))
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
        PlaybackSession.updatePipHost(this, false)
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
