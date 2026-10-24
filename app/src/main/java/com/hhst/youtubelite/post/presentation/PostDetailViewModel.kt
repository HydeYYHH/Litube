package com.hhst.youtubelite.post.presentation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hhst.youtubelite.post.data.PostScenario
import com.hhst.youtubelite.post.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
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
    private var loadJob: Job? = null
    private var generation = 0

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
            PostDetailAction.Like -> navigation.trySend(PostDetailNavigationRequest.Feedback(_state.value.identity, true))
            PostDetailAction.Dislike -> navigation.trySend(PostDetailNavigationRequest.Feedback(_state.value.identity, false))
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

    private fun load() {
        loadJob?.cancel()
        val requestGeneration = ++generation
        _state.update { it.copy(content = PostDetailContent.Loading) }
        loadJob = viewModelScope.launch {
            try {
                val post = repository.post(_state.value.identity)
                if (requestGeneration != generation) return@launch
                _state.update {
                    it.copy(content = post?.let(PostDetailContent::Ready) ?: PostDetailContent.Failed)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (requestGeneration == generation) _state.update { it.copy(content = PostDetailContent.Failed) }
            }
        }
    }

    override fun onCleared() {
        navigation.close()
    }
}
