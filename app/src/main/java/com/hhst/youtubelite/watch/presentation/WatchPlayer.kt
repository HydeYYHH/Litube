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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
internal fun WatchPlayer(modifier: Modifier, onBack: () -> Unit, pip: Boolean = false) {
    val playerViewModel: PlayerViewModel = koinInject()
    val playerState by playerViewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    val back = rememberUpdatedState(onBack)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val surface = remember { mutableStateOf<SurfaceView?>(null) }
    DisposableEffect(lifecycle, playerViewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> surface.value?.let(playerViewModel::attachSurface)
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
    val immersive = playerState.fullscreen && playerState.visible && !playerState.mini
    PlayerWindowEffects(
        activity, windowHost, immersive, playerState.videoWidth, playerState.videoHeight,
    )
    LaunchedEffect(playerViewModel) { playerViewModel.initializeCast() }
    Box(modifier.background(Color.Black)) {
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
            onRefreshCastState = playerViewModel::refreshCastState,
            positionState = playerViewModel.positionState,
            bufferedPositionState = playerViewModel.bufferedPositionState,
            gestureState = playerViewModel.gestureState,
            hintState = playerViewModel.hintState,
            subtitleCuesState = playerViewModel.subtitleCuesState,
            modifier = Modifier.fillMaxSize(),
            managedByHost = true,
        )
    }
}

private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
