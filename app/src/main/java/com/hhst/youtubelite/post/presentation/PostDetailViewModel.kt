package com.hhst.youtubelite.post.presentation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hhst.youtubelite.core.AppNotice
import com.hhst.youtubelite.diagnostics.writeFailureNotice
import com.hhst.youtubelite.post.data.PostScenario
import com.hhst.youtubelite.post.domain.PostActions
import com.hhst.youtubelite.post.domain.PostAuthorizationRequiredException
import com.hhst.youtubelite.post.domain.PostDetail
import com.hhst.youtubelite.post.domain.PostDetailAction
import com.hhst.youtubelite.post.domain.PostDetailContent
import com.hhst.youtubelite.post.domain.PostDetailNavigationRequest
import com.hhst.youtubelite.post.domain.PostDetailOverlay
import com.hhst.youtubelite.post.domain.PostDetailUiState
import com.hhst.youtubelite.post.domain.PostIdentity
import com.hhst.youtubelite.post.domain.PostRepository
import com.hhst.youtubelite.watch.domain.SignInRequiredException
import com.hhst.youtubelite.watch.domain.UnconfirmedActionException
import com.hhst.youtubelite.watch.domain.VideoRating
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class PostDetailViewModel(
    private val repository: PostRepository,
    private val savedState: SavedStateHandle,
    identity: PostIdentity,
    scenario: PostScenario = PostScenario.Content,
    live: Boolean = false,
) : ViewModel() {
    private val _state = MutableStateFlow(
        PostDetailUiState(
            identity = identity,
            live = live,
            content = when (scenario) {
                PostScenario.Error, PostScenario.Empty -> PostDetailContent.Failed
                PostScenario.Content, PostScenario.Loading -> PostDetailContent.Loading
            },
            selectedImage = savedState.get<Int>("selectedImage") ?: 0,
            overlay = when {
                savedState.get<Int>("imageOpen") != null ->
                    PostDetailOverlay.Image(savedState.get<Int>("imageOpen")!!.coerceAtLeast(0))
                savedState.get<Boolean>("menu") == true -> PostDetailOverlay.Menu
                else -> PostDetailOverlay.None
            },
            commentsOpen = savedState.get<Boolean>("commentsOpen") == true,
        )
    )
    val uiState = _state.asStateFlow()
    private val navigation = Channel<PostDetailNavigationRequest>(Channel.BUFFERED)
    val navigationRequests = navigation.receiveAsFlow()
    private val notices = Channel<AppNotice>(Channel.BUFFERED)
    val actionNotices = notices.receiveAsFlow()
    private val authorizations = Channel<Unit>(Channel.BUFFERED)
    val authorizationRequests = authorizations.receiveAsFlow()
    private val actions = repository as? PostActions
    private var actionJob: Job? = null
    private var actionGeneration = 0
    private var loadJob: Job? = null
    private var generation = 0
    private var refreshFailed = false

    fun updateSignedIn(signedIn: Boolean) {
        if (_state.value.signedIn == signedIn) return
        actionGeneration++
        actionJob?.cancel()
        _state.update { it.copy(signedIn = signedIn, actionPending = false, pendingAction = null) }
    }

    fun refreshSession(signedIn: Boolean) {
        actionGeneration++
        actionJob?.cancel()
        _state.update { it.copy(signedIn = signedIn, actionPending = false, pendingAction = null) }
        load()
    }

    /** Refresh WEB commands without removing the post, image selection or scroll container. */
    fun refreshAuthorization(signedIn: Boolean) {
        actionGeneration++
        actionJob?.cancel()
        _state.update { it.copy(signedIn = signedIn, actionPending = false, pendingAction = null) }
        load(preserveContent = true)
    }

    init {
        if (scenario == PostScenario.Content) load()
    }

    fun onAction(action: PostDetailAction) {
        when (action) {
            PostDetailAction.Retry -> load()
            is PostDetailAction.SelectDestination -> navigation.trySend(PostDetailNavigationRequest.Destination(action.destination))
            is PostDetailAction.SelectImage -> {
                savedState["selectedImage"] = action.index
                if (_state.value.overlay is PostDetailOverlay.Image) savedState["imageOpen"] = action.index
                _state.update { it.copy(selectedImage = action.index,
                    overlay = if (it.overlay is PostDetailOverlay.Image) PostDetailOverlay.Image(action.index) else it.overlay) }
            }
            is PostDetailAction.OpenImage -> {
                savedState["imageOpen"] = action.index
                savedState["selectedImage"] = action.index
                _state.update { it.copy(selectedImage = action.index, overlay = PostDetailOverlay.Image(action.index)) }
            }
            PostDetailAction.Like -> if (actions != null) rate(action, VideoRating.Like)
                else navigation.trySend(PostDetailNavigationRequest.Feedback(_state.value.identity, true))
            PostDetailAction.Dislike -> if (actions != null) rate(action, VideoRating.Dislike)
                else navigation.trySend(PostDetailNavigationRequest.Feedback(_state.value.identity, false))
            is PostDetailAction.Vote -> {
                val identity = _state.value.identity
                mutate(action) { it.vote(identity, action.option, action.selected) }
            }
            PostDetailAction.Comments -> {
                savedState["commentsOpen"] = true
                _state.update { it.copy(commentsOpen = true) }
                navigation.trySend(PostDetailNavigationRequest.Comments(_state.value.identity))
            }
            PostDetailAction.CloseComments -> {
                savedState["commentsOpen"] = false
                _state.update { it.copy(commentsOpen = false) }
            }
            PostDetailAction.OpenMenu -> {
                savedState["menu"] = true
                _state.update { it.copy(overlay = PostDetailOverlay.Menu) }
            }
            PostDetailAction.CloseOverlay -> {
                savedState["menu"] = false
                savedState.remove<Int>("imageOpen")
                _state.update { it.copy(overlay = PostDetailOverlay.None) }
            }
            is PostDetailAction.MenuSelected -> {
                savedState["menu"] = false
                _state.update { it.copy(overlay = PostDetailOverlay.None) }
                navigation.trySend(PostDetailNavigationRequest.MenuAction(_state.value.identity, action.action))
            }
            PostDetailAction.OpenChannel -> navigation.trySend(
                PostDetailNavigationRequest.Channel(_state.value.identity.channelId)
            )
        }
    }

    private fun rate(action: PostDetailAction, selected: VideoRating) {
        val post = _state.value.post ?: return
        val previous = post.rating
        val desired = if (previous == selected) VideoRating.None else selected
        mutate(action) { it.rate(post.identity, desired, previous) }
    }

    private fun mutate(pending: PostDetailAction, operation: suspend (PostActions) -> PostDetail) {
        val service = actions ?: return
        if (_state.value.actionPending || _state.value.post == null) return
        if (refreshFailed) load(preserveContent = true)
        val epoch = actionGeneration
        val opening = loadJob
        _state.update { it.copy(actionPending = true, pendingAction = pending) }
        actionJob = viewModelScope.launch {
            try {
                opening?.join()
                currentCoroutineContext().ensureActive()
                if (epoch != actionGeneration) return@launch
                if (refreshFailed) throw UnconfirmedActionException()
                val post = operation(service)
                currentCoroutineContext().ensureActive()
                if (epoch == actionGeneration) _state.update { it.copy(content = PostDetailContent.Ready(post), identity = post.identity) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: PostAuthorizationRequiredException) { if (epoch == actionGeneration) authorizations.send(Unit) }
            catch (_: SignInRequiredException) {
                if (epoch == actionGeneration) notices.send(AppNotice.SignIn)
            } catch (error: Exception) {
                if (epoch == actionGeneration) notices.send(writeFailureNotice("post_interaction", error))
            } finally {
                if (epoch == actionGeneration) _state.update { it.copy(actionPending = false, pendingAction = null) }
            }
        }
    }

    private fun load(preserveContent: Boolean = false) {
        loadJob?.cancel()
        val requestGeneration = ++generation
        val retained = preserveContent && _state.value.post != null
        refreshFailed = false
        _state.update { it.copy(content = if (retained) it.content else PostDetailContent.Loading, refreshing = retained) }
        loadJob = viewModelScope.launch {
            try {
                val post = repository.post(_state.value.identity)
                currentCoroutineContext().ensureActive()
                if (requestGeneration != generation) return@launch
                if (retained && post == null) throw UnconfirmedActionException()
                _state.update {
                    it.copy(identity = post?.identity ?: it.identity,
                        content = post?.let(PostDetailContent::Ready) ?: PostDetailContent.Failed)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (requestGeneration == generation) {
                    refreshFailed = retained
                    if (retained) notices.send(writeFailureNotice("post_refresh", error))
                    else _state.update { it.copy(content = PostDetailContent.Failed) }
                }
            } finally {
                if (requestGeneration == generation) _state.update { it.copy(refreshing = false) }
            }
        }
    }

    override fun onCleared() {
        navigation.close()
        notices.close()
        authorizations.close()
    }
}
