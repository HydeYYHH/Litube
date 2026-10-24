package com.hhst.youtubelite.post

import com.hhst.youtubelite.comments.presentation.bindCommentNotices
import com.hhst.youtubelite.ui.observeAccountChanges
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import com.hhst.youtubelite.ui.design.setAppContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
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
import com.hhst.youtubelite.home.HomeActivity
import com.hhst.youtubelite.post.domain.PostDestination
import com.hhst.youtubelite.post.data.FixturePostRepository
import com.hhst.youtubelite.post.data.PostScenario
import com.hhst.youtubelite.post.domain.PostDetailAction
import com.hhst.youtubelite.post.domain.PostDetailNavigationRequest
import com.hhst.youtubelite.post.domain.PostDetailOverlay
import com.hhst.youtubelite.post.domain.PostIdentity
import com.hhst.youtubelite.post.data.FixturePostCommentsRepository
import com.hhst.youtubelite.comments.domain.CommentAction
import com.hhst.youtubelite.comments.domain.CommentsOverlay
import com.hhst.youtubelite.comments.domain.CommentTarget
import com.hhst.youtubelite.comments.domain.CommentNavigationRequest
import com.hhst.youtubelite.comments.presentation.CommentsViewModel
import com.hhst.youtubelite.post.presentation.PostDetailScreen
import com.hhst.youtubelite.post.presentation.PostDetailViewModel
import kotlinx.coroutines.launch

class PostDetailActivity : com.hhst.youtubelite.ui.ContentActivity() {
    lateinit var postDetailViewModel: PostDetailViewModel
        private set
    lateinit var postCommentsViewModel: CommentsViewModel
        private set
    val postIdentity: PostIdentity
        get() = PostIdentity(
            intent.getStringExtra(EXTRA_CHANNEL_ID) ?: "studio",
            intent.getStringExtra(EXTRA_POST_ID) ?: FixturePostRepository.HOME_POST_ID,
        )
    var lastNavigationRequest: PostDetailNavigationRequest? = null
        private set
    var lastCommentRequest: CommentNavigationRequest? = null
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sources = org.koin.java.KoinJavaComponent.getKoin().get<com.hhst.youtubelite.data.media.ContentRepositories>()
        val fixtures = com.hhst.youtubelite.data.media.ContentRepositories.fixtures(intent)
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.light(Color.WHITE, Color.WHITE))
        val channelId = intent.getStringExtra(EXTRA_CHANNEL_ID)
        val postId = intent.getStringExtra(EXTRA_POST_ID)
        if ((channelId.isNullOrBlank() || postId.isNullOrBlank()) && !fixtures) {
            // No identity and no fixture seam: rendering a default post would fabricate content.
            finish()
            return
        }
        val identity = PostIdentity(channelId ?: "studio", postId ?: FixturePostRepository.HOME_POST_ID)
        val scenario = if (BuildConfig.DEBUG) PostScenario.parse(intent.getStringExtra(EXTRA_SCENARIO)) else PostScenario.Content
        postDetailViewModel = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                require(modelClass == PostDetailViewModel::class.java)
                return PostDetailViewModel(
                    if (fixtures) FixturePostRepository(scenario) else sources.posts,
                    extras.createSavedStateHandle(),
                    identity,
                    scenario,
                    live = !fixtures,
                ) as T
            }
        })[PostDetailViewModel::class.java]
        postCommentsViewModel = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                require(modelClass == CommentsViewModel::class.java)
                return CommentsViewModel(
                    if (fixtures) FixturePostCommentsRepository() else sources.comments(),
                    extras.createSavedStateHandle(),
                    CommentTarget.Post(identity),
                ) as T
            }
        })[CommentsViewModel::class.java]
        bindCommentNotices(postCommentsViewModel)
        if (!fixtures) observeAccountChanges { postCommentsViewModel.refreshSession() }
        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_OPEN_COMMENTS, false)) {
            postDetailViewModel.onAction(PostDetailAction.Comments)
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                postCommentsViewModel.navigationRequests.collect { request ->
                    lastCommentRequest = request
                    if (request is CommentNavigationRequest.OpenAuthor) launchNavigation(
                        ChannelActivity.intent(this@PostDetailActivity, request.channelId),
                    )
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                postDetailViewModel.navigationRequests.collect { request ->
                    lastNavigationRequest = request
                    when (request) {
                        is PostDetailNavigationRequest.Channel -> launchNavigation(
                            ChannelActivity.intent(this@PostDetailActivity, request.channelId)
                        )
                        is PostDetailNavigationRequest.Destination -> com.hhst.youtubelite.ui.components.MainTabs.intent(
                            this@PostDetailActivity, com.hhst.youtubelite.ui.components.MainTab.valueOf(request.destination.name), false)?.let { launchNavigation(it); finish() }
                        is PostDetailNavigationRequest.Feedback -> if (!fixtures) {
                            com.hhst.youtubelite.post.openPostVoteRoute(this@PostDetailActivity)
                        }
                        // The comments panel is VM overlay state and the Report menu hides in
                        // production, so both are fixture-only typed exits here. No `else`: a new
                        // request type must fail to compile here.
                        is PostDetailNavigationRequest.Comments, is PostDetailNavigationRequest.MenuAction -> Unit
                    }
                }
            }
        }
        setAppContent {
            val state by postDetailViewModel.uiState.collectAsStateWithLifecycle()
            val commentsState by postCommentsViewModel.uiState.collectAsStateWithLifecycle()
            WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true
            val back = {
                if (state.commentsOpen && commentsState.overlay != CommentsOverlay.None) postCommentsViewModel.onAction(CommentAction.CloseMenu)
                else if (state.commentsOpen) postDetailViewModel.onAction(PostDetailAction.CloseComments)
                else if (state.overlay != PostDetailOverlay.None) postDetailViewModel.onAction(PostDetailAction.CloseOverlay)
                else finish()
            }
            BackHandler(onBack = back)
            PostDetailScreen(
                state,
                { action ->
                    when {
                        !fixtures && action is PostDetailAction.OpenImage -> {
                            state.post?.images?.takeIf { it.isNotEmpty() }?.let {
                                com.hhst.youtubelite.gallery.GalleryActivity.open(this@PostDetailActivity, it, action.index)
                            }
                        }
                        // Anonymous posts expose no vote endpoints at all — the web itself routes these
                        // buttons to sign-in (B9 probe). Signed-in votes stay an honest limitation.
                        !fixtures && (action is PostDetailAction.Like || action is PostDetailAction.Dislike) ->
                            openPostVoteRoute(this@PostDetailActivity)
                        else -> postDetailViewModel.onAction(action)
                    }
                },
                back,
                Modifier.statusBarsPadding().navigationBarsPadding(),
                commentsState = commentsState,
                onCommentsAction = postCommentsViewModel::onAction,
            )
        }
    }

    companion object {
        const val EXTRA_OPEN_COMMENTS = "open_comments"
        const val EXTRA_CHANNEL_ID = "channelId"
        const val EXTRA_POST_ID = "postId"
        const val EXTRA_SCENARIO = "scenario"

        fun intent(context: Context, channelId: String, postId: String, scenario: PostScenario? = null): Intent =
            Intent(context, PostDetailActivity::class.java)
                .putExtra(EXTRA_CHANNEL_ID, channelId)
                .putExtra(EXTRA_POST_ID, postId)
                .apply { if (scenario != null) putExtra(EXTRA_SCENARIO, scenario.argument) }
    }
}
