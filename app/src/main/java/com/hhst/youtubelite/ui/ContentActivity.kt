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
    private var miniWindowReady = false
    private var miniEntryBounds by mutableStateOf<androidx.compose.ui.geometry.Rect?>(null)
    private var miniEntryVersion by mutableIntStateOf(0)
    private var miniPreDraw: android.view.ViewTreeObserver.OnPreDrawListener? = null
    private var miniComposition: ComposeView? = null
    private var miniHostFocused by mutableStateOf(false)
    private var resumedDuringMiniRestore = false

    override fun onStart() {
        val installMini = !ContentRepositories.fixtures(intent) || playbackTest()
        // Publish the handoff before retained lifecycle collectors can create the mini.
        if (installMini) {
            miniWindowReady = false
            miniEntryBounds = com.hhst.youtubelite.navigation.WatchTransition.takeMiniEntryBounds()
            if (miniEntryBounds != null) miniEntryVersion++
        }
        super.onStart()
        if (!installMini) return
        awaitMiniWindow()
    }

    private fun awaitMiniWindow() {
        val decor = window.decorView
        miniPreDraw?.let { decor.viewTreeObserver.removeOnPreDrawListener(it) }
        val deadline = android.os.SystemClock.uptimeMillis() + 500
        val listener = object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                val state = PlaybackSession.player.value?.uiState?.value
                val ready = state?.visible != true || !state.mini || miniWindowReady ||
                    android.os.SystemClock.uptimeMillis() >= deadline
                if (ready) {
                    decor.viewTreeObserver.removeOnPreDrawListener(this)
                    if (miniPreDraw === this) miniPreDraw = null
                } else {
                    // A retained Compose host can defer layout until dispatchDraw,
                    // which this gate is withholding.
                    miniComposition?.getChildAt(0)?.requestLayout()
                    decor.postInvalidateOnAnimation()
                }
                return ready
            }
        }
        miniPreDraw = listener
        decor.viewTreeObserver.addOnPreDrawListener(listener)
    }

    override fun onStop() {
        miniPreDraw?.let { window.decorView.viewTreeObserver.removeOnPreDrawListener(it) }
        miniPreDraw = null
        super.onStop()
        com.hhst.youtubelite.navigation.WatchTransition.clearMiniRestore(this)
        resumedDuringMiniRestore = false
    }

    override fun onDestroy() {
        com.hhst.youtubelite.navigation.WatchTransition.clearMiniRestore(this)
        super.onDestroy()
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        if (ContentRepositories.fixtures(intent) && !playbackTest()) return
        val overlay = object : android.widget.FrameLayout(this) {
            private val picturePaint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
            private val blackPaint = android.graphics.Paint().apply { color = android.graphics.Color.BLACK }
            private val clip = android.graphics.Path()
            private val position = IntArray(2)
            override fun dispatchDraw(canvas: android.graphics.Canvas) {
                // Omit the mini from browse snapshots without changing view visibility:
                // hiding a SurfaceView destroys its video output.
                if (com.hhst.youtubelite.navigation.WatchTransition.capturingBackdrop) return
                super.dispatchDraw(canvas)
                val cover = com.hhst.youtubelite.navigation.WatchTransition.miniRestoreFrame(this@ContentActivity) ?: return
                val state = PlaybackSession.player.value?.uiState?.value
                if (state?.visible != true || state.videoId != cover.frame.videoId || PlaybackSession.inPip.value) return
                // openVideo leaves mini mode before the new window is drawn;
                // paint its last picture without retaining a decoder.
                getLocationInWindow(position)
                val saved = canvas.save()
                canvas.translate(-position[0].toFloat(), -position[1].toFloat())
                val bounds = cover.bounds.run { android.graphics.RectF(left, top, right, bottom) }
                val radius = com.hhst.youtubelite.player.surface.MiniPlayerLayout.CORNER_DP * resources.displayMetrics.density
                clip.reset()
                clip.addRoundRect(bounds, radius, radius, android.graphics.Path.Direction.CW)
                canvas.clipPath(clip)
                canvas.drawRect(bounds, blackPaint)
                val video = cover.videoBounds.run { android.graphics.RectF(left, top, right, bottom) }
                canvas.drawBitmap(cover.frame.image, null, video, picturePaint)
                canvas.restoreToCount(saved)
            }
        }
        overlay.addView(ComposeView(this).apply {
            miniComposition = this
            setContent {
                var started by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
                DisposableEffect(lifecycle) {
                    val observer = LifecycleEventObserver { _, _ -> started = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
                    lifecycle.addObserver(observer)
                    onDispose { lifecycle.removeObserver(observer) }
                }
                val player by PlaybackSession.player.collectAsStateWithLifecycle()
                val pip by PlaybackSession.inPip.collectAsStateWithLifecycle()
                if (started) player?.let { vm ->
                    val state by vm.uiState.collectAsStateWithLifecycle()
                    val cover = com.hhst.youtubelite.navigation.WatchTransition.miniRestoreFrame(this@ContentActivity)
                    val previousCover = remember {
                        arrayOf<com.hhst.youtubelite.navigation.WatchTransition.MiniRestoreFrame?>(null)
                    }
                    SideEffect {
                        if (cover != null && (!state.visible || state.videoId != cover.frame.videoId || pip))
                            com.hhst.youtubelite.navigation.WatchTransition.clearMiniRestore(this@ContentActivity)
                        // This picture is on the native overlay's display list;
                        // Compose invalidation alone cannot redraw it.
                        if (cover != null || previousCover[0] != null)
                            (miniComposition?.parent as? android.view.View)?.invalidate()
                        previousCover[0] = cover
                    }
                    if (state.visible && state.mini && !pip) {
                        val store: MiniPlayerStore = koinInject()
                        com.hhst.youtubelite.ui.theme.AppTheme {
                            BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                                MiniPlayerWindow(maxWidth.value.toInt(), 56, store, state.controlsVisible,
                                    vm::onToggleControls, onDismiss = vm::onMiniClose,
                                    initialBounds = com.hhst.youtubelite.navigation.WatchTransition.miniBounds,
                                    animateFromBounds = miniEntryBounds,
                                    entryAnimationReady = miniHostFocused,
                                    entryKey = miniEntryVersion,
                                    onReadyForDisplay = {
                                        miniWindowReady = true
                                        if (resumedDuringMiniRestore) {
                                            com.hhst.youtubelite.navigation.WatchTransition.clearMiniRestore(this@ContentActivity)
                                            resumedDuringMiniRestore = false
                                        }
                                    }) {
                                    WatchPlayer(Modifier.fillMaxSize(), vm::onMiniRestore)
                                }
                            }
                        }
                    }
                }
            }
        }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        findViewById<ViewGroup>(android.R.id.content).addView(overlay,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun playbackTest() = com.hhst.youtubelite.BuildConfig.DEBUG && intent.getBooleanExtra("playback_test", false)

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        miniHostFocused = hasFocus
    }

    override fun onResume() {
        // A quick Back can resume browse before it ever stops, so there is no onStart for this handoff.
        if (!ContentRepositories.fixtures(intent) || playbackTest()) {
            // A cancelled entrance can return before this source stops; keep the
            // static picture until the returning mini is laid out.
            com.hhst.youtubelite.navigation.WatchTransition.miniRestoreFrame(this)?.let { cover ->
                val player = PlaybackSession.player.value
                val originalSurface = cover.frame.source.get()
                if (player?.uiState?.value?.mini == true && originalSurface != null && player.hasRenderedFrame(originalSurface)) {
                    // Target cancelled before taking playback; the original mini
                    // already has its live picture underneath.
                    com.hhst.youtubelite.navigation.WatchTransition.clearMiniRestore(this)
                } else {
                    resumedDuringMiniRestore = true
                    miniWindowReady = false
                    awaitMiniWindow()
                }
            }
            com.hhst.youtubelite.navigation.WatchTransition.takeMiniEntryBounds()?.let {
                miniWindowReady = false
                miniEntryBounds = it
                miniEntryVersion++
                awaitMiniWindow()
            }
        }
        super.onResume()
        com.hhst.youtubelite.navigation.WatchTransition.browseResumed(this)
        if (!ContentRepositories.fixtures(intent) || playbackTest()) {
            // Entering PiP can itself resume browse; the pinned activity still owns playback until it exits PiP.
            if (!PlaybackSession.inPip.value) PlaybackSession.player.value?.onBrowseOpened()
        }
    }
}
