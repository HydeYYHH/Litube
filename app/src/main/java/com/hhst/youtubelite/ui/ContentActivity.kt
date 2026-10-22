package com.hhst.youtubelite.ui

import android.os.Bundle
import android.view.ViewGroup
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hhst.youtubelite.data.media.ContentRepositories
import com.hhst.youtubelite.player.PlaybackSession
import com.hhst.youtubelite.player.surface.MiniPlayerStore
import com.hhst.youtubelite.player.surface.MiniPlayerWindow
import com.hhst.youtubelite.watch.presentation.WatchPlayer
import org.koin.compose.koinInject

abstract class ContentActivity : NativeActivity() {
    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        if (ContentRepositories.fixtures(intent) && !playbackTest()) return
        findViewById<ViewGroup>(android.R.id.content).addView(ComposeView(this).apply {
            setContent {
                var resumed by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
                DisposableEffect(lifecycle) {
                    val observer = LifecycleEventObserver { _, _ -> resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
                    lifecycle.addObserver(observer)
                    onDispose { lifecycle.removeObserver(observer) }
                }
                val player by PlaybackSession.player.collectAsStateWithLifecycle()
                if (resumed) player?.let { vm ->
                    val state by vm.uiState.collectAsStateWithLifecycle()
                    if (state.visible && state.mini) {
                        val store: MiniPlayerStore = koinInject()
                        com.hhst.youtubelite.ui.theme.AppTheme {
                            BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                                MiniPlayerWindow(maxWidth.value.toInt(), 56, store, state.controlsVisible,
                                    vm::onToggleControls, onDismiss = vm::onMiniClose) {
                                    WatchPlayer(Modifier.fillMaxSize(), vm::onMiniRestore)
                                }
                            }
                        }
                    }
                }
            }
        }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun playbackTest() = com.hhst.youtubelite.BuildConfig.DEBUG && intent.getBooleanExtra("playback_test", false)

    override fun onResume() {
        super.onResume()
        if (!ContentRepositories.fixtures(intent) || playbackTest()) PlaybackSession.player.value?.onBrowseOpened()
    }
}
