@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hhst.youtubelite.ui.browser

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.OrientationEventListener
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hhst.youtubelite.R
import com.hhst.youtubelite.browser.Bridge
import com.hhst.youtubelite.browser.BrowserHost
import com.hhst.youtubelite.browser.PageKind
import com.hhst.youtubelite.browser.PlayerHooks
import com.hhst.youtubelite.browser.Tab
import com.hhst.youtubelite.browser.WatchPage
import com.hhst.youtubelite.browser.WebViewFactory
import com.hhst.youtubelite.downloader.core.BatchSnapshot
import com.hhst.youtubelite.downloader.core.SnapshotReject
import com.hhst.youtubelite.downloader.share.DownloadShareParser
import com.hhst.youtubelite.downloader.ui.DownloadUi
import com.hhst.youtubelite.downloader.ui.DownloadUiStart
import com.hhst.youtubelite.downloader.webview.DownloadWebBridge
import com.hhst.youtubelite.downloader.webview.WebViewTimerHandle
import com.hhst.youtubelite.downloader.webview.WebViewTimerOccupancy
import com.hhst.youtubelite.downloader.webview.WebViewTimerOwner
import com.hhst.youtubelite.extension.Extension
import com.hhst.youtubelite.extension.ExtensionManager
import com.hhst.youtubelite.extension.PreferenceKeys
import com.hhst.youtubelite.extractor.Extractor
import com.hhst.youtubelite.extractor.VideoId
import com.hhst.youtubelite.net.NetTracer
import com.hhst.youtubelite.player.PlayerViewModel
import com.hhst.youtubelite.player.queue.QueueItem
import com.hhst.youtubelite.player.service.NotificationPermission
import com.hhst.youtubelite.player.surface.MiniPlayerStore
import com.hhst.youtubelite.player.surface.MiniPlayerWindow
import com.hhst.youtubelite.player.surface.PlayerCallbackBridge
import com.hhst.youtubelite.player.surface.PlayerSurface
import com.hhst.youtubelite.player.surface.PlayerSurfaceCallbacks
import com.hhst.youtubelite.player.surface.PlayerUi
import com.hhst.youtubelite.player.surface.PlayerWindowHost
import com.hhst.youtubelite.ui.about.AboutActivity
import com.hhst.youtubelite.ui.extension.ExtensionScreen
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import org.json.JSONObject
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

/** Multi-tab WebView browser with a short fade + slide on tab switch. */
@Composable
fun BrowserScreen(
    viewModel: BrowserViewModel = koinViewModel(),
    playerViewModel: PlayerViewModel = koinInject(),
    extensionManager: ExtensionManager = koinInject(),
    extractor: Extractor = koinInject(),
    miniPlayerStore: MiniPlayerStore = koinInject(),
    webViewTimers: WebViewTimerOccupancy = koinInject(),
    sharedUrl: StateFlow<String?>? = null,
    pendingPlaylistUrl: StateFlow<String?>? = null,
    inPipFlow: StateFlow<Boolean>? = null,
    onSharedUrlConsumed: () -> Unit = {},
    onPendingPlaylistConsumed: () -> Unit = {},
    onPlayerActiveChanged: (active: Boolean, playing: Boolean, width: Int, height: Int) -> Unit = { _, _, _, _ -> },
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val playerState by playerViewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val lifecycleOwner = LocalLifecycleOwner.current

    val hosts = remember { mutableStateMapOf<Long, BrowserHost>() }
    var showExtension by remember { mutableStateOf(false) }
    // PiP flips without a recreate; the activity pushes it through [inPipFlow].
    var inPip by remember { mutableStateOf(inPipFlow?.value == true) }
    LaunchedEffect(inPipFlow) {
        inPipFlow?.collect { inPip = it }
    }
    val audioManager = remember {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    val activeId = uiState.activeId
    val forward = uiState.forward
    val tabs = uiState.tabs
    val onOpenExtension = remember { { showExtension = true } }
    val onOpenDownloads = remember(context) { { DownloadUi.openManager(context) } }
    val onOpenWith = remember(context) {
        { url: String ->
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, url)
            }
            context.startActivity(Intent.createChooser(send, context.getString(R.string.open_with)))
        }
    }
    val onAbout = remember(context) {
        { context.startActivity(Intent(context, AboutActivity::class.java)) }
    }

    // Share / open-with intents: route to a tab; the watch hook starts
    // playback. Clearing after consumption lets the same URL re-share.
    LaunchedEffect(sharedUrl) {
        sharedUrl?.collect { url ->
            if (url != null) {
                viewModel.openTab(url)
                onSharedUrlConsumed()
            }
        }
    }
    var pendingPlaylist by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pendingPlaylistUrl) {
        pendingPlaylistUrl?.collect { url -> pendingPlaylist = url }
    }
    LaunchedEffect(uiState.url, uiState.isLoading, pendingPlaylist, hosts[activeId]) {
        val pending = pendingPlaylist ?: return@LaunchedEffect
        if (uiState.isLoading) return@LaunchedEffect
        val current = uiState.url ?: return@LaunchedEffect
        if (!DownloadShareParser.samePlaylist(current, pending)) return@LaunchedEffect
        val host = hosts[activeId] ?: return@LaunchedEffect
        val bridge = host.downloadBridge ?: return@LaunchedEffect
        repeat(5) {
            delay(400)
            val snapshot = CompletableDeferred<BatchSnapshot?>()
            bridge.collectLoadedSnapshot(host.webView) { snapshot.complete(it) }
            val loaded = snapshot.await() ?: return@repeat
            pendingPlaylist = null
            onPendingPlaylistConsumed()
            when (val start = DownloadUi.showBatchConfirm(context, loaded)) {
                is DownloadUiStart.Rejected -> {
                    val msg = when (start.reason) {
                        is SnapshotReject.TooManyItems ->
                            context.getString(R.string.download_snapshot_too_many)
                        is SnapshotReject.TooLarge ->
                            context.getString(R.string.download_snapshot_too_large)
                    }
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }
                is DownloadUiStart.Started -> Unit
            }
            return@LaunchedEffect
        }
    }

    // Host create/destroy in effects so composition stays free of WebView side effects.
    // The suspended watch tab keeps its host.
    val liveTabIds = remember(tabs, uiState.suspendedWatchId) {
        buildSet {
            tabs.forEach { add(it.id) }
            uiState.suspendedWatchId?.let { add(it) }
        }
    }
    val queueAdded = stringResource(R.string.queue_item_added)
    val queueUnavailable = stringResource(R.string.queue_item_unavailable)
    val onAddToQueue = remember(playerViewModel, context, queueAdded, queueUnavailable) {
        { item: Bridge.QueueItemJson? ->
            if (item == null) {
                Toast.makeText(context, queueUnavailable, Toast.LENGTH_SHORT).show()
            } else {
                playerViewModel.onQueueAdd(
                    QueueItem(
                        videoId = item.videoId.orEmpty(),
                        url = item.url.orEmpty(),
                        title = item.title?.takeIf { it.isNotBlank() } ?: item.videoId.orEmpty(),
                        author = item.author,
                        thumbnailUrl = item.thumbnailUrl,
                    ),
                )
                Toast.makeText(context, queueAdded, Toast.LENGTH_SHORT).show()
            }
        }
    }
    var mediaMenuItem by remember { mutableStateOf<Bridge.QueueItemJson?>(null) }
    val onShowMediaItemMenu = remember {
        { item: Bridge.QueueItemJson -> mediaMenuItem = item }
    }
    // Playlist presence from the page hooks: only a host actually showing the
    // playing video may speak for it. Without this, a shorts tab opened while
    // a watch tab is suspended pushes false and kills the mini-player's
    // playlist auto-advance until the suspended page navigates again.
    val onPlaylistPresence: (Long, Boolean) -> Unit = remember(playerViewModel, viewModel) {
        { fromTab, has ->
            val state = viewModel.uiState.value
            val playingId = VideoId.parse(playerViewModel.uiState.value.url)
            val speaksForPlayback = playingId == null ||
                fromTab == state.suspendedWatchId ||
                state.tabs.any { it.id == fromTab && VideoId.parse(it.url) == playingId }
            if (speaksForPlayback) playerViewModel.onPageHasPlaylist(has)
        }
    }
    LaunchedEffect(liveTabIds) {
        createHosts(
            tabs, hosts, context, viewModel, extensionManager, onOpenExtension,
            onOpenDownloads, onOpenWith, onAbout, extractor,
            playerViewModel, onAddToQueue, onShowMediaItemMenu,
            onPlaylistPresence = onPlaylistPresence,
        )
        delay(TAB_DESTROY_DELAY_MS)
        hosts.keys.filter { it !in liveTabIds }.forEach { id ->
            hosts.remove(id)?.let { destroyHost(it) }
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.loadRequests.collect { (tabId, url) ->
            hosts[tabId]?.webView?.loadUrl(url)
        }
    }

    // History is per WebView; re-read after switch (inactive tabs skip nav callbacks).
    LaunchedEffect(activeId, hosts[activeId]) {
        val webView = hosts[activeId]?.webView ?: return@LaunchedEffect
        viewModel.canGoBack = webView.canGoBack()
        hosts.forEach { (id, host) ->
            if (id == activeId) host.webView.onResume() else host.webView.onPause()
        }
    }

    val latestHosts = rememberUpdatedState(hosts)
    val latestActiveId = rememberUpdatedState(activeId)

    // Preference flips must reach already-rendered pages: the injected
    // scripts listen for this event to re-read prefs without a reload.
    val prefBroadcastHandler = remember { Handler(Looper.getMainLooper()) }
    DisposableEffect(extensionManager) {
        val listener: (String) -> Unit = { key ->
            prefBroadcastHandler.post {
                val quoted = JSONObject.quote(key)
                val js = "window.dispatchEvent(new CustomEvent('preferencesChanged'," +
                    "{detail:{key:$quoted}}));"
                latestHosts.value.values.forEach { host ->
                    host.webView.evaluateJavascript(js, null)
                }
            }
        }
        extensionManager.addOnChangedListener(listener)
        onDispose {
            extensionManager.removeOnChangedListener(listener)
            prefBroadcastHandler.removeCallbacksAndMessages(null)
        }
    }

    DisposableEffect(lifecycleOwner, webViewTimers) {
        var timerHandle: WebViewTimerHandle? = null
        fun acquireTimers() {
            if (timerHandle != null) return
            timerHandle = webViewTimers.acquire(WebViewTimerOwner.BROWSER)
        }
        fun releaseTimers() {
            timerHandle?.release()
            timerHandle = null
        }
        val observer = LifecycleEventObserver { _, event ->
            val active = latestHosts.value[latestActiveId.value]?.webView
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    active?.onResume()
                    acquireTimers()
                }
                Lifecycle.Event.ON_PAUSE -> {
                    val pausedInPip = activity?.isInPictureInPictureMode == true
                    latestHosts.value.values.forEach { it.webView.onPause() }
                    releaseTimers()
                    // "Visible" not "playing": pausing an already-paused player is harmless.
                    val playerVisible = playerViewModel.uiState.value.visible
                    if (playerVisible && !pausedInPip &&
                        !extensionManager.isEnabled(PreferenceKeys.ENABLE_BACKGROUND_PLAY)
                    ) {
                        playerViewModel.pauseForBackground()
                    }
                }
                // Dismissing the PiP window goes pause→stop with no second
                // pause event; without this audio keeps playing invisible.
                Lifecycle.Event.ON_STOP -> {
                    val stoppedInPip = activity?.isInPictureInPictureMode == true
                    val playerVisible = playerViewModel.uiState.value.visible
                    if (playerVisible && !stoppedInPip &&
                        !extensionManager.isEnabled(PreferenceKeys.ENABLE_BACKGROUND_PLAY)
                    ) {
                        playerViewModel.pauseForBackground()
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            acquireTimers()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            releaseTimers()
            hosts.values.forEach { destroyHost(it) }
            hosts.clear()
        }
    }

    val exitHint = stringResource(R.string.back_again_to_exit)
    var lastFinishAt by remember { mutableLongStateOf(0L) }

    val handleBack: () -> Unit = {
        val ps = playerViewModel.uiState.value
        when (PlayerUi.nextBackStep(ps.locked, ps.fullscreen)) {
            PlayerUi.BackStep.Unlock -> playerViewModel.setLocked(false)
            PlayerUi.BackStep.ExitFullscreen -> playerViewModel.setFullscreen(false)
            // Mini-player stays up: Back drives the browser underneath it.
            PlayerUi.BackStep.BrowserBack -> {
                // Resolve the active WebView from the synchronous tab state.
                // A rapid second Back can run before recomposition posts the
                // new activeId; the compose-side id would still point at the
                // just-suspended watch tab, and goBack() would drive its
                // INVISIBLE WebView — whose page hook then replays the
                // previous video and pulls the mini-player back to full size
                // over the wrong tab.
                val webView = latestHosts.value[viewModel.uiState.value.activeId]?.webView
                when (viewModel.onBack(webCanGoBack = webView?.canGoBack() == true)) {
                    BackResult.GoWebBack -> {
                        webView?.goBack()
                        viewModel.canGoBack = webView?.canGoBack() == true
                    }
                    BackResult.Handled -> Unit
                    BackResult.Finish -> {
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastFinishAt < 2000L) {
                            playerViewModel.stopOnExit()
                            activity?.finish()
                        } else {
                            lastFinishAt = now
                            Toast.makeText(context, exitHint, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    // Always consume Back. Same path as the player overlay back button.
    BackHandler(enabled = !showExtension) { handleBack() }

    // Mini-player close drops the suspended watch tab; restore revives it.
    DisposableEffect(playerViewModel, viewModel) {
        playerViewModel.onRestoreWatch = { viewModel.restoreWatchTab(it) }
        playerViewModel.onWatchClosed = { viewModel.dropSuspendedWatch() }
        onDispose {
            playerViewModel.onRestoreWatch = null
            playerViewModel.onWatchClosed = null
        }
    }
    // Watch-page operations for queue/playlist navigation. The watch host is
    // the suspended watch tab when the mini-player owns the surface — the
    // ACTIVE tab may be a completely different page by then, and evaluating
    // there would silently no-op the playlist navigation.
    DisposableEffect(playerViewModel, viewModel, hosts) {
        fun watchWebView(): WebView? {
            val state = viewModel.uiState.value
            val watchId = state.suspendedWatchId ?: state.activeId
            return hosts[watchId]?.webView
        }
        playerViewModel.watchPage = object : WatchPage {
            override fun evaluate(js: String, onResult: (String?) -> Unit) {
                val webView = watchWebView() ?: return onResult(null)
                webView.evaluateJavascript(js) { result -> onResult(result) }
            }

            override fun canGoBack(): Boolean = watchWebView()?.canGoBack() == true

            override fun goBack() {
                val webView = watchWebView() ?: return
                webView.goBack()
                // canGoBack is keyed by the ACTIVE tab; the watch host may be
                // a suspended tab, so its history must not be written there.
            }
        }
        onDispose {
            playerViewModel.watchPage = null
        }
    }
    // Only a showing player can suspend the watch tab.
    val suspendSource = rememberUpdatedState(playerState.visible)
    DisposableEffect(viewModel) {
        viewModel.isPlayerShowing = { suspendSource.value }
        onDispose { viewModel.isPlayerShowing = { false } }
    }
    DisposableEffect(viewModel, playerViewModel) {
        viewModel.onWatchSuspended = { playerViewModel.enterMiniPlayer() }
        viewModel.onWatchOpened = { playerViewModel.onReturnToWatch(it) }
        viewModel.onShortsClosed = { playerViewModel.onShortsClosed() }
        onDispose {
            viewModel.onWatchSuspended = null
            viewModel.onWatchOpened = null
            viewModel.onShortsClosed = null
        }
    }
    // Active-tab URL left a video page with no watch-tab suspension. Shorts
    // is NAV: leaving it docks the current media as mini (onShortsClosed).
    // A still-visible player that reaches this effect left a non-video page
    // without going through those hooks.
    LaunchedEffect(
        uiState.activeId,
        uiState.url,
        uiState.suspendedWatchId,
        playerState.visible,
        playerState.mini,
    ) {
        if (uiState.suspendedWatchId != null) return@LaunchedEffect
        if (VideoId.parse(uiState.url) != null) return@LaunchedEffect
        if (playerState.visible &&
            !playerState.mini &&
            extensionManager.isEnabled(PreferenceKeys.ENABLE_IN_APP_MINI_PLAYER)
        ) {
            playerViewModel.enterMiniPlayer()
        } else {
            playerViewModel.onLeaveWatch()
        }
    }

    val back = rememberUpdatedState(handleBack)
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
            edgeSlider = { volume, percent ->
                playerViewModel.onEdgeSlider(volume, percent)
            },
        )
    }
    val playerCallbacks = remember(playerViewModel, windowHost) {
        PlayerCallbackBridge(playerViewModel, windowHost)
    }
    val onActiveChanged = rememberUpdatedState(onPlayerActiveChanged)

    LaunchedEffect(playerState.fullscreen, playerState.videoWidth, playerState.videoHeight, windowHost) {
        windowHost.applyImmersive(
            playerState.fullscreen,
            playerState.videoWidth,
            playerState.videoHeight,
        )
    }
    LaunchedEffect(
        playerState.visible, playerState.mini, playerState.isPlaying,
        playerState.loading, playerState.casting,
        playerState.videoWidth, playerState.videoHeight, windowHost,
    ) {
        onActiveChanged.value(
            playerState.visible && !playerState.mini && !playerState.casting,
            playerState.isPlaying || playerState.loading,
            playerState.videoWidth,
            playerState.videoHeight,
        )
        if (!playerState.visible) windowHost.restoreBrightness()
    }
    LaunchedEffect(playerState.visible, activity) {
        if (playerState.visible &&
            extensionManager.isEnabled(PreferenceKeys.ENABLE_BACKGROUND_PLAY)
        ) {
            activity?.let { NotificationPermission.requestIfNeeded(it) }
        }
    }
    LaunchedEffect(activity) {
        activity?.let { playerViewModel.initializeCast(it) }
    }
    val pipState = rememberUpdatedState(inPip)
    DisposableEffect(activity, playerViewModel, lifecycleOwner) {
        val act = activity ?: return@DisposableEffect onDispose {}
        val resolver = act.contentResolver
        fun readAutoRotate() =
            Settings.System.getInt(resolver, Settings.System.ACCELEROMETER_ROTATION, 1) == 1
        // Cached via observer: onOrientationChanged fires at sensor rate and a
        // per-event Settings query is a binder IPC each time.
        var autoRotate = readAutoRotate()
        val rotateObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                autoRotate = readAutoRotate()
            }
        }
        resolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.ACCELEROMETER_ROTATION),
            true,
            rotateObserver,
        )
        val listener = object : OrientationEventListener(act) {
            override fun onOrientationChanged(degrees: Int) {
                playerViewModel.onPhysicalOrientation(degrees, autoRotate, pipState.value)
            }
        }
        // The sensor keeps firing in the background; only listen while visible.
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> if (listener.canDetectOrientation()) listener.enable()
                Lifecycle.Event.ON_STOP -> listener.disable()
                else -> Unit
            }
        }
        if (listener.canDetectOrientation()) listener.enable()
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            resolver.unregisterContentObserver(rotateObserver)
            listener.disable()
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val layoutDir = LocalLayoutDirection.current
        val leftInsetDp = with(density) {
            WindowInsets.safeDrawing.getLeft(this, layoutDir).toDp().value.toInt()
        }
        val rightInsetDp = with(density) {
            WindowInsets.safeDrawing.getRight(this, layoutDir).toDp().value.toInt()
        }
        val topInsetDp = with(density) { WindowInsets.safeDrawing.getTop(this).toDp().value.toInt() }
        val bottomInsetDp = with(density) { WindowInsets.safeDrawing.getBottom(this).toDp().value.toInt() }
        val availableWidthDp = (maxWidth.value.toInt() - leftInsetDp - rightInsetDp).coerceAtLeast(1)
        val availableHeightDp = (maxHeight.value.toInt() - topInsetDp - bottomInsetDp).coerceAtLeast(1)
        val layoutMini = playerState.visible && !playerState.mini &&
            !playerState.fullscreen && !inPip && !PageKind.isShorts(playerState.url) &&
            PlayerUi.useLandscapeMiniPlayer(availableWidthDp, availableHeightDp, playerState.pageHeightDp)
        SideEffect { playerViewModel.setCompactPlayer(layoutMini) }
        val layoutMiniState = rememberUpdatedState(layoutMini)
        val surfaceCallbacks = remember(playerCallbacks, playerViewModel) {
            object : PlayerSurfaceCallbacks by playerCallbacks {
                override fun onMiniRestore() {
                    // The embedded slot cannot fit here; expand into fullscreen.
                    if (layoutMiniState.value) playerViewModel.onFullscreenToggle()
                    else playerCallbacks.onMiniRestore()
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            AnimatedContent(
                targetState = activeId,
                transitionSpec = {
                    val offset = { width: Int -> width / 14 }
                    if (forward) {
                        (
                            fadeIn(animationSpec = tween(TAB_FADE_MS)) +
                                slideInHorizontally(tween(TAB_SLIDE_MS), offset)
                            ) togetherWith (
                            fadeOut(animationSpec = tween(TAB_FADE_MS)) +
                                slideOutHorizontally(tween(TAB_SLIDE_MS)) { -offset(it) }
                            )
                    } else {
                        (
                            fadeIn(animationSpec = tween(TAB_FADE_MS)) +
                                slideInHorizontally(tween(TAB_SLIDE_MS)) { -offset(it) }
                            ) togetherWith (
                            fadeOut(animationSpec = tween(TAB_FADE_MS)) +
                                slideOutHorizontally(tween(TAB_SLIDE_MS), offset)
                            )
                    }
                },
                label = "tabSwitch",
                modifier = Modifier.fillMaxSize(),
            ) { tabId ->
                val host = hosts[tabId]
                if (host != null) {
                    AndroidView(
                        factory = {
                            (host.container.parent as? ViewGroup)?.removeView(host.container)
                            host.container
                        },
                        modifier = Modifier.fillMaxSize(),
                        update = { container ->
                            container.isRefreshing =
                                tabId == uiState.activeId && uiState.isRefreshing
                        },
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize())
                }
            }

            LoadingBar(
                progress = uiState.progress,
                visible = uiState.isLoading,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }

        // Native player overlays the page player. Mini-player docks bottom-end.
        if (playerState.visible) {
            val navigationBottomDp = with(density) {
                WindowInsets.navigationBars.getBottom(this).toDp().value.toInt()
            }
            val isMini = (playerState.mini || layoutMini) && !inPip && !playerState.fullscreen
            // One shared surface invocation: mini/embedded differ only in the
            // modifier and the docking window around it.
            val playerSurface: @Composable (Modifier) -> Unit = { surfaceModifier ->
                PlayerSurface(
                    state = if (layoutMini) playerState.copy(mini = true, locked = false) else playerState,
                    callbacks = surfaceCallbacks,
                    onAttachSurface = playerViewModel::attachSurface,
                    onDetachSurface = playerViewModel::detachSurface,
                    zoneEnabled = playerViewModel::gestureEnabled,
                    pip = inPip,
                    onRefreshCastState = playerViewModel::refreshCastState,
                    positionState = playerViewModel.positionState,
                    bufferedPositionState = playerViewModel.bufferedPositionState,
                    gestureState = playerViewModel.gestureState,
                    hintState = playerViewModel.hintState,
                    subtitleCuesState = playerViewModel.subtitleCuesState,
                    modifier = surfaceModifier,
                    managedByHost = true,
                )
            }
            MiniPlayerWindow(
                    screenWidthDp = availableWidthDp,
                    bottomInsetDp = navigationBottomDp,
                    store = miniPlayerStore,
                    showStroke = playerState.controlsVisible,
                    onBackgroundTap = playerViewModel::onToggleControls,
                    onDismiss = playerViewModel::onMiniClose,
                    mini = isMini,
                    fillsWindow = inPip || playerState.fullscreen,
                    embeddedTopDp = PlayerUi.playerTopOffsetDp(false, playerState.pageTopDp, topInsetDp),
                    embeddedHeightDp = PlayerUi.embeddedHeightDp(playerState.pageHeightDp, availableWidthDp),
                    topInsetDp = topInsetDp,
                    modifier = if (inPip || playerState.fullscreen) Modifier else Modifier.padding(start = leftInsetDp.dp, end = rightInsetDp.dp),
                ) {
                    playerSurface(Modifier.fillMaxSize())
                }
        }

        // The PiP window must show the video: the extension screen is an
        // opaque full-size sheet that would otherwise cover it.
        if (showExtension && !inPip) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing),
            ) {
                ExtensionScreen(
                    onClose = { showExtension = false },
                    onNavigate = { id ->
                        if (id == Extension.NAV_DOWNLOADS) {
                            showExtension = false
                            DownloadUi.openManager(context)
                        }
                    },
                )
            }
        }

        mediaMenuItem?.let { item ->
            MediaItemMenuDialog(
                item = item,
                onQueue = {
                    onAddToQueue(item)
                    mediaMenuItem = null
                },
                onDismiss = { mediaMenuItem = null },
            )
        }
    }
}

private fun createHosts(
    tabs: List<Tab>,
    hosts: SnapshotStateMap<Long, BrowserHost>,
    context: Context,
    viewModel: BrowserViewModel,
    extensionManager: ExtensionManager,
    onOpenExtension: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenWith: (String) -> Unit,
    onAbout: () -> Unit,
    extractor: Extractor,
    playerHooks: PlayerHooks,
    onAddToQueue: ((Bridge.QueueItemJson?) -> Unit)? = null,
    onShowMediaItemMenu: ((Bridge.QueueItemJson) -> Unit)? = null,
    onPlaylistPresence: ((Long, Boolean) -> Unit)? = null,
) {
    for (tab in tabs) {
        if (tab.id in hosts) continue
        val tabId = tab.id
        hosts[tabId] = WebViewFactory.create(
            context = context,
            callbacks = viewModel.callbacksFor(tabId),
            extensionManager = extensionManager,
            onOpenExtension = onOpenExtension,
            onOpenDownloads = onOpenDownloads,
            onOpenWith = onOpenWith,
            onAbout = onAbout,
            onRefresh = { webView ->
                viewModel.onRefreshStarted(tabId)
                webView.reload()
            },
            extractor = extractor,
            playerHooks = playerHooks,
            tabId = tabId,
            onAddToQueue = onAddToQueue,
            onShowMediaItemMenu = onShowMediaItemMenu,
            onPlaylistPresence = onPlaylistPresence?.let { callback ->
                { has: Boolean -> callback(tabId, has) }
            },
        ).also { it.webView.loadUrl(tab.url) }
    }
}

private fun destroyHost(host: BrowserHost) {
    host.container.isRefreshing = false
    val webView: WebView = host.webView
    host.downloadBridge?.detach(webView)
    webView.stopLoading()
    webView.removeJavascriptInterface(Bridge.NAME)
    webView.removeJavascriptInterface(NetTracer.JS_NAME)
    webView.removeJavascriptInterface(DownloadWebBridge.FALLBACK_NAME)
    webView.loadUrl("about:blank")
    (webView.parent as? ViewGroup)?.removeView(webView)
    (host.container.parent as? ViewGroup)?.removeView(host.container)
    webView.destroy()
}

private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

private const val TAB_FADE_MS = 180
private const val TAB_SLIDE_MS = 220
private val TAB_DESTROY_DELAY_MS = maxOf(TAB_FADE_MS, TAB_SLIDE_MS) + 40L
