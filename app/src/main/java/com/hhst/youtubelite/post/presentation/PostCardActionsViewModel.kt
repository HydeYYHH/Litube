package com.hhst.youtubelite.post.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hhst.youtubelite.core.AppNotice
import com.hhst.youtubelite.diagnostics.writeFailureNotice
import com.hhst.youtubelite.post.domain.PostActions
import com.hhst.youtubelite.post.domain.PostAuthorizationRequiredException
import com.hhst.youtubelite.post.domain.PostDetail
import com.hhst.youtubelite.post.domain.PostDetailAction
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

data class PostCardInteraction(val post: PostDetail? = null, val pendingAction: PostDetailAction? = null)

data class PostCardInteractions(
    val posts: Map<String, PostCardInteraction>,
    val perform: (PostIdentity, PostDetailAction) -> Unit,
    val openImages: ((List<String>, Int) -> Unit)? = null,
)

/** Card writes keep their originating list and update only the affected post. */
class PostCardActionsViewModel(private val repository: PostRepository) : ViewModel() {
    private val _state = MutableStateFlow<Map<String, PostCardInteraction>>(emptyMap())
    val uiState = _state.asStateFlow()
    private val notices = Channel<AppNotice>(Channel.BUFFERED)
    val actionNotices = notices.receiveAsFlow()
    private val authorizations = Channel<Unit>(Channel.BUFFERED)
    val authorizationRequests = authorizations.receiveAsFlow()
    private val jobs = mutableMapOf<String, Job>()
    private val refreshRequired = mutableSetOf<String>()
    private var epoch = 0L

    fun reset() {
        epoch++
        jobs.values.forEach(Job::cancel)
        jobs.clear()
        refreshRequired.clear()
        _state.value = emptyMap()
    }

    fun onAction(identity: PostIdentity, action: PostDetailAction) {
        val service = repository as? PostActions ?: return
        if (action != PostDetailAction.Like && action != PostDetailAction.Dislike && action !is PostDetailAction.Vote) return
        val id = identity.postId
        if (_state.value[id]?.pendingAction != null) return
        val previous = _state.value[id]?.post?.rating ?: VideoRating.None
        val requestEpoch = epoch
        _state.update { it + (id to (it[id] ?: PostCardInteraction()).copy(pendingAction = action)) }
        jobs[id] = viewModelScope.launch {
            try {
                // Authorization never replays a write. The next explicit tap refreshes
                // WEB commands, then submits once from this same card.
                if (id in refreshRequired) {
                    repository.post(identity) ?: throw UnconfirmedActionException()
                    currentCoroutineContext().ensureActive()
                    if (requestEpoch != epoch) return@launch
                    refreshRequired.remove(id)
                }
                val post = when (action) {
                    PostDetailAction.Like -> service.rate(identity,
                        if (previous == VideoRating.Like) VideoRating.None else VideoRating.Like, previous)
                    PostDetailAction.Dislike -> service.rate(identity,
                        if (previous == VideoRating.Dislike) VideoRating.None else VideoRating.Dislike, previous)
                    is PostDetailAction.Vote -> service.vote(identity, action.option, action.selected)
                    else -> return@launch
                }
                currentCoroutineContext().ensureActive()
                if (requestEpoch == epoch) _state.update { it + (id to PostCardInteraction(post, action)) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: PostAuthorizationRequiredException) {
                if (requestEpoch == epoch) { refreshRequired += id; authorizations.send(Unit) }
            } catch (_: SignInRequiredException) {
                if (requestEpoch == epoch) notices.send(AppNotice.SignIn)
            } catch (error: Exception) {
                if (requestEpoch == epoch) notices.send(writeFailureNotice("post_card_interaction", error))
            } finally {
                if (requestEpoch == epoch) {
                    _state.update { it + (id to (it[id] ?: PostCardInteraction()).copy(pendingAction = null)) }
                    jobs.remove(id)
                }
            }
        }
    }

    override fun onCleared() { notices.close(); authorizations.close() }
}
