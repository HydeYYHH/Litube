@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hhst.youtubelite.watch.presentation

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import android.view.SurfaceView
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hhst.youtubelite.player.PlayerViewModel
import com.hhst.youtubelite.player.surface.PlayerCallbackBridge
import com.hhst.youtubelite.player.surface.PlayerSurface
import com.hhst.youtubelite.player.surface.PlayerWindowEffects
import com.hhst.youtubelite.player.surface.PlayerWindowHost
import org.koin.compose.koinInject

@Composable
internal fun WatchPlayer(modifier: Modifier, onBack: () -> Unit, pip: Boolean = false,
    presentationState: com.hhst.youtubelite.player.PlayerUiState? = null,
    chromeAlpha: () -> Float = { 1f },
    showPipLoadingIndicator: Boolean = true) {
    val playerViewModel: PlayerViewModel = koinInject()
    val livePlayerState by playerViewModel.uiState.collectAsStateWithLifecycle()
    val playerState = livePlayerState.withWatchPresentation(presentationState)
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    val back = rememberUpdatedState(onBack)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val surface = remember { mutableStateOf<SurfaceView?>(null) }
    val frameReady = playerState.firstFrameRendered &&
        surface.value?.let(playerViewModel::hasRenderedFrame) == true
    val handoff = com.hhst.youtubelite.navigation.WatchTransition.videoFrame
        ?.takeIf { it.videoId == playerState.videoId && it.source.get() !== surface.value }
    DisposableEffect(lifecycle, playerViewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    surface.value?.let(playerViewModel::attachSurface)
                }
                // Reordering a watch host before its previous stop completes
                // can resume it without a new ON_START. Reclaim output here too.
                Lifecycle.Event.ON_RESUME -> surface.value?.let {
                    playerViewModel.attachSurface(it)
                }
                Lifecycle.Event.ON_STOP -> surface.value?.let(playerViewModel::detachSurface)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            surface.value?.let(playerViewModel::detachSurface)
            surface.value = null
        }
    }
    val windowHost = remember(activity, audioManager, playerViewModel) {
        PlayerWindowHost(
            activity = activity,
            audioManager = audioManager,
            back = { back.value() },
            pipAvailable = { playerViewModel.uiState.value.pipAvailable },
            sharePayload = {
                val snapshot = playerViewModel.uiState.value
                snapshot.url?.let { it to snapshot.title }
            },
            videoSize = {
                val snapshot = playerViewModel.uiState.value
                snapshot.videoWidth to snapshot.videoHeight
            },
            castLinkUrl = { playerViewModel.currentCastLink() },
            edgeSlider = { volume, percent -> playerViewModel.onEdgeSlider(volume, percent) },
        )
    }
    val callbacks = remember(playerViewModel, windowHost) {
        PlayerCallbackBridge(playerViewModel, windowHost)
    }
    val immersive = playerState.fullscreen && playerState.visible && !playerState.mini && !pip
    PlayerWindowEffects(
        activity, windowHost, immersive, playerState.videoWidth, playerState.videoHeight,
        keepOrientation = playerState.fullscreenKeepsOrientation && playerState.visible && !playerState.mini && !pip,
        restoreEntryOrientation = playerState.fullscreenRestoresEntryOrientation,
    )
    LaunchedEffect(playerViewModel) { playerViewModel.initializeCast() }
    com.hhst.youtubelite.ui.theme.AppTheme {
        Box(modifier.background(Color.Black)) {
            val resumed by lifecycle.currentStateFlow.collectAsStateWithLifecycle()
            if (playerState.failureDialogVisible && playerState.failureReport != null &&
                resumed.isAtLeast(Lifecycle.State.RESUMED) &&
                !(android.os.Build.VERSION.SDK_INT >= 24 && activity?.isInPictureInPictureMode == true)) {
                com.hhst.youtubelite.player.surface.PlaybackFailureDialog(
                    requireNotNull(playerState.failureReport), playerViewModel::dismissPlaybackFailure, playerViewModel::onRetry)
            }
            PlayerSurface(
                state = playerState,
                callbacks = callbacks,
                onAttachSurface = {
                    surface.value = it
                    if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) playerViewModel.attachSurface(it)
                },
                onDetachSurface = {
                    playerViewModel.detachSurface(it)
                    if (surface.value === it) surface.value = null
                },
                zoneEnabled = playerViewModel::gestureEnabled,
                pip = pip,
                showPipLoadingIndicator = showPipLoadingIndicator,
                onRefreshCastState = playerViewModel::refreshCastState,
                positionState = playerViewModel.positionState,
                bufferedPositionState = playerViewModel.bufferedPositionState,
                gestureState = playerViewModel.gestureState,
                hintState = playerViewModel.hintState,
                subtitleCuesState = playerViewModel.subtitleCuesState,
                modifier = Modifier.fillMaxSize(),
                managedByHost = true,
                chromeAlpha = chromeAlpha,
                // Only a captured frame of this video can cover its replacement surface.
                transitionFrame = rememberWatchTransitionFrame(playerState.videoId, handoff?.image, frameReady),
            )
        }
    }
}

@Composable
internal fun rememberWatchTransitionFrame(
    videoId: String?,
    handoffFrame: android.graphics.Bitmap?,
    frameReady: Boolean,
): android.graphics.Bitmap? {
    var handoffRevealed by remember(videoId, handoffFrame) { mutableStateOf(false) }
    SideEffect {
        if (frameReady && handoffFrame != null) handoffRevealed = true
    }
    // Do not resurrect a consumed frame on buffering or holder recreation.
    return handoffFrame?.takeUnless { handoffRevealed || frameReady }
}

private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
