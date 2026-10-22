package com.hhst.youtubelite.shorts

import com.hhst.youtubelite.comments.presentation.bindCommentNotices
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.CreationExtras
import com.hhst.youtubelite.BuildConfig
import com.hhst.youtubelite.channel.ChannelActivity
import com.hhst.youtubelite.comments.data.CommentsScenario
import com.hhst.youtubelite.comments.data.FixtureCommentsRepository
import com.hhst.youtubelite.comments.domain.CommentsOverlay
import com.hhst.youtubelite.comments.domain.CommentAction
import com.hhst.youtubelite.comments.domain.CommentNavigationRequest
import com.hhst.youtubelite.comments.presentation.CommentsPanel
import com.hhst.youtubelite.comments.presentation.CommentsViewModel
import com.hhst.youtubelite.home.domain.FeedMenuAction
import com.hhst.youtubelite.home.domain.FeedMenuKind
import com.hhst.youtubelite.player.PlayerViewModel
import com.hhst.youtubelite.share.ShareGateway
import com.hhst.youtubelite.shorts.data.FixtureShortsRepository
import com.hhst.youtubelite.shorts.data.ShortsScenario
import com.hhst.youtubelite.shorts.domain.ShortsAction
import com.hhst.youtubelite.shorts.domain.ShortsNavigationRequest
import com.hhst.youtubelite.shorts.domain.ShortsOverlay
import com.hhst.youtubelite.shorts.presentation.ShortsScreen
import com.hhst.youtubelite.shorts.presentation.ShortsViewModel
import com.hhst.youtubelite.ui.design.AppIcon
import com.hhst.youtubelite.ui.design.MenuBottomSheet
import com.hhst.youtubelite.ui.design.MenuSheetItem
import kotlinx.coroutines.launch

class ShortsActivity : ComponentActivity() {
    lateinit var shortsViewModel: ShortsViewModel
        private set
    lateinit var commentsViewModel: CommentsViewModel
        private set
    var lastNavigationRequest: ShortsNavigationRequest? = null
        private set

    private var commentsScenario = CommentsScenario.Content

    val shareGateway: ShareGateway by lazy { ShareGateway(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sources = org.koin.java.KoinJavaComponent.getKoin().get<com.hhst.youtubelite.data.media.ContentRepositories>()
        val fixtures = com.hhst.youtubelite.data.media.ContentRepositories.fixtures(intent)
        if (!fixtures) (application as com.hhst.youtubelite.App).startMediaRuntime()
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        val startItemId = intent.getStringExtra(EXTRA_START_ITEM)
        val scenario = if (BuildConfig.DEBUG) ShortsScenario.parse(intent.getStringExtra(EXTRA_SCENARIO)) else ShortsScenario.Content
        commentsScenario = if (BuildConfig.DEBUG) CommentsScenario.parse(intent.getStringExtra(EXTRA_COMMENTS_SCENARIO)) else CommentsScenario.Content
        val commentsVideoId = startItemId ?: "short-1"
        shortsViewModel = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                require(modelClass == ShortsViewModel::class.java)
                return ShortsViewModel(if (fixtures) FixtureShortsRepository(scenario, intent.getStringExtra(EXTRA_CHANNEL_ID)) else sources.shorts(startItemId, intent.getStringExtra(EXTRA_CHANNEL_ID)), extras.createSavedStateHandle(), scenario, startItemId) as T
            }
        })[ShortsViewModel::class.java]
        commentsViewModel = commentsFor(commentsVideoId)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                shortsViewModel.navigationRequests.collect { request ->
                    lastNavigationRequest = request
                    when (request) {
                        is ShortsNavigationRequest.Channel -> startActivity(ChannelActivity.intent(this@ShortsActivity, request.channelId))
                        is ShortsNavigationRequest.Share -> shareGateway.share(request.itemId)
                        else -> Unit
                    }
                }
            }
        }
        setContent {
            val playerViewModel: PlayerViewModel = org.koin.compose.koinInject()
            DisposableEffect(playerViewModel, lifecycle) {
                val observer = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_START -> playerViewModel.onShortsOpened()
                        Lifecycle.Event.ON_STOP -> if (fixtures) playerViewModel.onShortsClosed() else playerViewModel.pauseShort()
                        else -> Unit
                    }
                }
                lifecycle.addObserver(observer)
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) playerViewModel.onShortsOpened()
                onDispose {
                    lifecycle.removeObserver(observer)
                    playerViewModel.onShortsClosed()
                }
            }
            val state by shortsViewModel.uiState.collectAsStateWithLifecycle()
            val commentItem = (state.overlay as? ShortsOverlay.Comments)?.itemId ?: state.currentItemId ?: commentsVideoId
            val activeComments = remember(commentItem) { commentsFor(commentItem) }
            commentsViewModel = activeComments
            val commentsState by activeComments.uiState.collectAsStateWithLifecycle()
            LaunchedEffect(activeComments) {
                lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    activeComments.navigationRequests.collect { request ->
                        if (request is CommentNavigationRequest.OpenAuthor) {
                            startActivity(ChannelActivity.intent(this@ShortsActivity, request.channelId))
                        }
                    }
                }
            }
            val closeComments = { commentsViewModel.onAction(CommentAction.CloseMenu); shortsViewModel.onAction(ShortsAction.CloseComments) }
            BackHandler(enabled = state.overlay != ShortsOverlay.None) {
                when (state.overlay) {
                    is ShortsOverlay.Comments -> {
                        if (commentsState.overlay != CommentsOverlay.None) activeComments.onAction(CommentAction.CloseMenu)
                        else closeComments()
                    }
                    is ShortsOverlay.Menu -> shortsViewModel.onAction(ShortsAction.CloseMenu)
                    ShortsOverlay.None -> Unit
                }
            }
            if (!fixtures) {
                LaunchedEffect(state.currentItemId, lifecycle) {
                    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                        state.currentItemId?.let(playerViewModel::openShort)
                        kotlinx.coroutines.awaitCancellation()
                    }
                }
            }
            ShortsScreen(state, shortsViewModel::onAction, Modifier.fillMaxSize(), videoContent = if (fixtures) null else { _ ->
                Box(Modifier.fillMaxSize().clickable { playerViewModel.onPlayPause() }) {
                    com.hhst.youtubelite.watch.presentation.WatchPlayer(Modifier.fillMaxSize(), { finish() }, pip = true)
                }
            })
            if (state.overlay is ShortsOverlay.Comments) {
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).testTag("comments-panel-host")) {
                    CommentsPanel(
                        state = commentsState,
                        commentCount = commentsState.total,
                        onAction = commentsViewModel::onAction,
                        onClose = closeComments,
                    )
                }
            }
            if (state.overlay is ShortsOverlay.Menu) {
                MenuBottomSheet(
                    items = FeedMenuKind.Short.actions.map { action -> MenuSheetItem(action.icon(), action.label) },
                    onDismiss = { shortsViewModel.onAction(ShortsAction.CloseMenu) },
                    onSelect = { index -> shortsViewModel.onAction(ShortsAction.MenuSelected(FeedMenuKind.Short.actions[index])) },
                    paneTitle = "Shorts actions",
                )
            }
        }
    }

    private val boundCommentModels = mutableSetOf<String>()

    private fun commentsFor(itemId: String): CommentsViewModel = ViewModelProvider(this, object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
            CommentsViewModel(if (com.hhst.youtubelite.data.media.ContentRepositories.fixtures(intent)) FixtureCommentsRepository(commentsScenario) else org.koin.java.KoinJavaComponent.getKoin().get<com.hhst.youtubelite.data.media.ContentRepositories>().comments(), extras.createSavedStateHandle(), itemId) as T
    })["short-comments:$itemId", CommentsViewModel::class.java].also { model ->
        if (boundCommentModels.add(itemId)) bindCommentNotices(model)
    }

    private fun FeedMenuAction.icon() = when (this) {
        FeedMenuAction.NotInterested -> AppIcon.Block
        FeedMenuAction.SendFeedback -> AppIcon.Feedback
        else -> AppIcon.More
    }

    companion object {
        const val EXTRA_SCENARIO = "shorts_scenario"
        const val EXTRA_COMMENTS_SCENARIO = "shorts_comments_scenario"
        const val EXTRA_CHANNEL_ID = "shorts_channel_id"
        const val EXTRA_START_ITEM = "shorts_start_item"

        fun intent(context: Context, startItemId: String? = null, scenario: ShortsScenario? = null, channelId: String? = null) =
            Intent(context, ShortsActivity::class.java)
                .putExtra(EXTRA_START_ITEM, startItemId)
                .putExtra(EXTRA_CHANNEL_ID, channelId)
                .apply { if (scenario != null) putExtra(EXTRA_SCENARIO, scenario.argument) }
    }
}
