package com.hhst.youtubelite.channel

import androidx.compose.ui.res.stringResource
import com.hhst.youtubelite.R
import com.hhst.youtubelite.ui.design.labelResource
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import com.hhst.youtubelite.navigation.openContentLink
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import com.hhst.youtubelite.ui.design.setAppContent
import androidx.activity.compose.BackHandler
import com.hhst.youtubelite.channel.domain.ChannelAction
import com.hhst.youtubelite.channel.domain.ChannelOverlay
import com.hhst.youtubelite.share.ShareGateway
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.CreationExtras
import com.hhst.youtubelite.BuildConfig
import com.hhst.youtubelite.channel.data.ChannelScenario
import com.hhst.youtubelite.channel.data.FixtureChannelRepository
import com.hhst.youtubelite.channel.domain.ChannelDestination
import com.hhst.youtubelite.home.HomeActivity
import com.hhst.youtubelite.post.PostDetailActivity
import com.hhst.youtubelite.channel.domain.ChannelNavigationRequest
import com.hhst.youtubelite.channel.presentation.ChannelScreen
import com.hhst.youtubelite.channel.presentation.ChannelViewModel
import com.hhst.youtubelite.watch.WatchActivity
import kotlinx.coroutines.launch
import com.hhst.youtubelite.ui.renderNotice
import com.hhst.youtubelite.ui.observeAccountChanges

class ChannelActivity : com.hhst.youtubelite.ui.ContentActivity() {
    private val postCards = com.hhst.youtubelite.post.PostCardActionHost(this)
    lateinit var channelViewModel: ChannelViewModel
        private set
    var lastNavigationRequest: ChannelNavigationRequest? = null
        private set

    private val verificationLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        // Cancelled verification keeps the banner; a completed one grants one retry.
        if (result.resultCode == RESULT_OK) channelViewModel.verificationCompleted(
            result.data?.getStringExtra(com.hhst.youtubelite.core.TrafficVerificationActivity.EXTRA_CHALLENGE))
    }
    val shareGateway: ShareGateway by lazy { ShareGateway(this) }

    private val itemMenuState = androidx.compose.runtime.mutableStateOf<com.hhst.youtubelite.ui.MediaActionItem?>(null)
    private var itemMenu: com.hhst.youtubelite.ui.MediaActionItem?
        get() = itemMenuState.value
        set(value) { itemMenuState.value = value }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sources = org.koin.java.KoinJavaComponent.getKoin().get<com.hhst.youtubelite.data.media.ContentRepositories>()
        val fixtures = com.hhst.youtubelite.data.media.ContentRepositories.fixtures(intent)
        enableEdgeToEdge(SystemBarStyle.light(Color.WHITE, Color.WHITE), SystemBarStyle.light(Color.WHITE, Color.WHITE))
        val channelId = intent.getStringExtra(EXTRA_CHANNEL_ID)
        if (channelId.isNullOrBlank() && !fixtures) {
            // No identity and no fixture seam: rendering a default channel would fabricate content.
            finish()
            return
        }
        val resolvedChannelId = channelId ?: FixtureChannelRepository.profile().channelId
        val scenario = if (BuildConfig.DEBUG) ChannelScenario.parse(intent.getStringExtra(EXTRA_SCENARIO)) else ChannelScenario.Content
        channelViewModel = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                require(modelClass == ChannelViewModel::class.java)
                return ChannelViewModel(if (fixtures) FixtureChannelRepository(scenario) else sources.channel, extras.createSavedStateHandle(), resolvedChannelId, scenario, if (fixtures) null else sources.videoActions) as T
            }
        })[ChannelViewModel::class.java]
        if (!fixtures) {
            postCards.bind()
            channelViewModel.updateSignedIn(com.hhst.youtubelite.data.media.MediaSession.snapshot().signedIn)
            observeAccountChanges {
                channelViewModel.updateSignedIn(com.hhst.youtubelite.data.media.MediaSession.snapshot().signedIn)
                channelViewModel.refreshSession()
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                channelViewModel.actionNotices.collect { notice ->
                    renderNotice(notice)
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                channelViewModel.navigationRequests.collect { request ->
                    lastNavigationRequest = request
                    when (request) {
                        is ChannelNavigationRequest.Short -> launchNavigation(
                            com.hhst.youtubelite.shorts.ShortsActivity.intent(this@ChannelActivity, request.shortId, channelId = request.channelId))
                        is ChannelNavigationRequest.PostComments -> launchNavigation(
                            PostDetailActivity.intent(this@ChannelActivity, request.identity.channelId, request.identity.postId)
                                .putExtra(PostDetailActivity.EXTRA_OPEN_COMMENTS, true))
                        is ChannelNavigationRequest.Watch -> launchNavigation(WatchActivity.intent(this@ChannelActivity, request.videoId))
                        is ChannelNavigationRequest.Playlist -> launchNavigation(com.hhst.youtubelite.playlist.PlaylistActivity.intent(this@ChannelActivity, request.playlistId))
                        is ChannelNavigationRequest.SharePlaylist -> shareGateway.sharePlaylist(request.playlistId)
                        is ChannelNavigationRequest.Post -> launchNavigation(
                            PostDetailActivity.intent(this@ChannelActivity, request.identity.channelId, request.identity.postId)
                        )
                        is ChannelNavigationRequest.Destination -> com.hhst.youtubelite.ui.components.MainTabs.intent(
                            this@ChannelActivity, com.hhst.youtubelite.ui.components.MainTab.valueOf(request.destination.name), channelViewModel.uiState.value.signedIn)?.let { launchNavigation(it); finish() }
                        is ChannelNavigationRequest.LiveStream -> launchNavigation(WatchActivity.intent(this@ChannelActivity, request.videoId))
                        is ChannelNavigationRequest.Share -> shareGateway.shareChannel(resolvedChannelId)
                        is ChannelNavigationRequest.OpenLink -> openContentLink(request.url)
                        is ChannelNavigationRequest.OpenVerification -> verificationLauncher.launch(
                            android.content.Intent(this@ChannelActivity, com.hhst.youtubelite.core.TrafficVerificationActivity::class.java)
                                .putExtra(com.hhst.youtubelite.core.TrafficVerificationActivity.EXTRA_CHALLENGE, request.challengeUrl))
                        is ChannelNavigationRequest.PostFeedback -> if (!fixtures) {
                            postCards.rate(request.identity, request.liked)
                        }
                        is ChannelNavigationRequest.ItemMenu -> if (!fixtures) {
                            val ready = channelViewModel.uiState.value.content as? com.hhst.youtubelite.channel.domain.ChannelContent.Ready
                            val video = ready?.page?.let { page ->
                                (page.videos + page.homeShelves.flatMap { it.videos }).firstOrNull { it.id == request.videoId }
                            }
                            if (video != null) itemMenu = com.hhst.youtubelite.ui.MediaActionItem(
                                video.id, video.title, ready.page.profile.name, video.artwork)
                        }
                        // Typed exits for fixture instrumentation; the ViewModel already applied
                        // their local effects (tab switch, About overlay), the production
                        // section/short/post menu entries are hidden (no server tokens), and
                        // subscribe writes run in-VM — so the host intentionally does nothing.
                        // No `else` branch: a new request type must fail to compile here.
                        is ChannelNavigationRequest.ChannelSearch,
                        is ChannelNavigationRequest.FullDescription,
                        is ChannelNavigationRequest.SectionMenu,
                        is ChannelNavigationRequest.ShortMenu,
                        is ChannelNavigationRequest.PostMenu,
                        is ChannelNavigationRequest.Subscribe -> Unit
                    }
                }
            }
        }
        setAppContent {
            val state by channelViewModel.uiState.collectAsStateWithLifecycle()
            val back = { if (state.overlay != ChannelOverlay.None) channelViewModel.onAction(ChannelAction.CloseOverlay) else finish() }
            BackHandler(onBack = back)
            ChannelScreen(state, channelViewModel::onAction, onBack = { onBackPressedDispatcher.onBackPressed() },
                postInteractions = if (fixtures) null else postCards.interactions(),
                modifier = Modifier.fillMaxSize().background(com.hhst.youtubelite.ui.design.DesignTokens.Background)
                    .windowInsetsPadding(WindowInsets.safeDrawing).imePadding())
            itemMenu?.let { item ->
                val executor = androidx.compose.runtime.remember { com.hhst.youtubelite.ui.ActivityMediaActionExecutor(this@ChannelActivity) }
                val items = com.hhst.youtubelite.ui.MediaActionKind.entries.map { kind ->
                    com.hhst.youtubelite.ui.design.MenuSheetItem(icon = when (kind) {
                        com.hhst.youtubelite.ui.MediaActionKind.PlayNext -> com.hhst.youtubelite.ui.design.AppIcon.Play
                        com.hhst.youtubelite.ui.MediaActionKind.Queue -> com.hhst.youtubelite.ui.design.AppIcon.QueueAdd
                        com.hhst.youtubelite.ui.MediaActionKind.Download -> com.hhst.youtubelite.ui.design.AppIcon.Download
                        com.hhst.youtubelite.ui.MediaActionKind.OpenWith -> com.hhst.youtubelite.ui.design.AppIcon.OpenWith
                        com.hhst.youtubelite.ui.MediaActionKind.Share -> com.hhst.youtubelite.ui.design.AppIcon.Share
                        com.hhst.youtubelite.ui.MediaActionKind.CopyLink -> null
                    }, label = stringResource(kind.labelResource), tag = "media-action-${kind.name}",
                        iconResource = if (kind == com.hhst.youtubelite.ui.MediaActionKind.CopyLink) com.hhst.youtubelite.R.drawable.ic_copy else null)
                }
                com.hhst.youtubelite.ui.design.MenuBottomSheet(items,
                    onDismiss = { itemMenu = null },
                    onSelect = { index ->
                        executor.execute(item, com.hhst.youtubelite.ui.MediaActionKind.entries[index])
                        itemMenu = null
                    })
            }
        }
    }

    companion object {
        const val EXTRA_CHANNEL_ID = "channel_id"
        const val EXTRA_SCENARIO = "channel_scenario"

        fun intent(context: Context, channelId: String, scenario: ChannelScenario? = null) =
            Intent(context, ChannelActivity::class.java)
                .putExtra(EXTRA_CHANNEL_ID, channelId)
                .apply { if (scenario != null) putExtra(EXTRA_SCENARIO, scenario.argument) }
    }
}
