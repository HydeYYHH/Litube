package com.hhst.youtubelite.comments.presentation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hhst.youtubelite.comments.domain.CommentAction
import com.hhst.youtubelite.comments.domain.CommentActions
import com.hhst.youtubelite.comments.domain.CommentMenuAction
import com.hhst.youtubelite.comments.domain.CommentMutation
import com.hhst.youtubelite.comments.domain.CommentNavigationRequest
import com.hhst.youtubelite.comments.domain.CommentReply
import com.hhst.youtubelite.comments.domain.CommentSort
import com.hhst.youtubelite.comments.domain.CommentTarget
import com.hhst.youtubelite.comments.domain.CommentThread
import com.hhst.youtubelite.comments.domain.CommentsContent
import com.hhst.youtubelite.comments.domain.CommentsOverlay
import com.hhst.youtubelite.comments.domain.CommentsPage
import com.hhst.youtubelite.comments.domain.CommentsRepository
import com.hhst.youtubelite.comments.domain.CommentsUiState
import com.hhst.youtubelite.comments.domain.adjustedCommentCount
import com.hhst.youtubelite.core.AppNotice
import com.hhst.youtubelite.diagnostics.writeFailureNotice
import com.hhst.youtubelite.post.domain.PostAuthorizationRequiredException
import com.hhst.youtubelite.post.domain.PostIdentity
import com.hhst.youtubelite.watch.domain.SignInRequiredException
import com.hhst.youtubelite.watch.domain.UnconfirmedActionException
import com.hhst.youtubelite.watch.domain.VideoRating
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class CommentsViewModel(
    private val repository: CommentsRepository,
    private val savedState: SavedStateHandle,
    initialTarget: CommentTarget,
    loadOnStart: Boolean = true,
) : ViewModel() {
    constructor(repository: CommentsRepository, savedState: SavedStateHandle, videoId: String, loadOnStart: Boolean = true) :
        this(repository, savedState, CommentTarget.Video(videoId), loadOnStart)
    private var loadsEnabled = loadOnStart

    fun startLoading() {
        if (loadsEnabled) return
        loadsEnabled = true
        load()
    }
    private var target: CommentTarget = when (savedState.get<String>("commentsTargetKind")) {
        "post" -> CommentTarget.Post(PostIdentity(
            savedState.get<String>("commentsTargetChannel").orEmpty(), savedState.get<String>("commentsTargetId").orEmpty()))
        "video" -> CommentTarget.Video(savedState.get<String>("commentsTargetId").orEmpty())
        else -> savedState.get<String>(KEY_VIDEO)?.let { CommentTarget.Video(it) } ?: initialTarget
    }
    private val sort = runCatching {
        CommentSort.valueOf(savedState.get<String>(KEY_SORT) ?: CommentSort.Top.name)
    }.getOrDefault(CommentSort.Top)
    private val expanded = savedState.get<ArrayList<String>>(KEY_EXPANDED)?.toSet() ?: emptySet()
    private var videoId = (target as? CommentTarget.Video)?.id.orEmpty()
    private val restoredOverlay: CommentsOverlay = when (savedState.get<String>(KEY_OVERLAY)) {
        "sort" -> CommentsOverlay.SortMenu
        "thread" -> savedState.get<String>(KEY_THREAD)?.let { CommentsOverlay.ThreadMenu(it) }
        "reply" -> {
            val thread = savedState.get<String>(KEY_THREAD)
            val reply = savedState.get<String>(KEY_REPLY)
            if (thread != null && reply != null) CommentsOverlay.ReplyMenu(thread, reply) else null
        }
        "composer" -> CommentsOverlay.Composer(
            threadId = savedState.get<String>(KEY_THREAD),
            replyId = savedState.get<String>(KEY_REPLY),
            editing = savedState.get<Boolean>(KEY_EDITING) == true,
        )
        "delete" -> savedState.get<String>(KEY_THREAD)?.let {
            CommentsOverlay.DeleteConfirm(it, savedState.get<String>(KEY_REPLY))
        }
        else -> null
    } ?: CommentsOverlay.None
    private val draft = savedState.get<String>(KEY_DRAFT).orEmpty()

    private val _state = MutableStateFlow(
        CommentsUiState(
            videoId = this.videoId, target = target, sort = sort, expanded = expanded,
            overlay = restoredOverlay,
            draft = draft.takeIf { restoredOverlay is CommentsOverlay.Composer }.orEmpty(),
            supportedMenuActions = (repository as? CommentActions)?.supportedMenuActions ?: CommentMenuAction.entries.toSet(),
        ),
    )
    val uiState = _state.asStateFlow()

    private val navigation = Channel<CommentNavigationRequest>(Channel.BUFFERED)
    val navigationRequests = navigation.receiveAsFlow()

    private val notices = Channel<AppNotice>(Channel.BUFFERED)
    val actionNotices = notices.receiveAsFlow()
    private val authorizations = Channel<Unit>(Channel.BUFFERED)
    val authorizationRequests = authorizations.receiveAsFlow()
    private var actionJob: Job? = null
    private val actions = repository as? CommentActions

    private var loadJob: Job? = null
    private var pageJob: Job? = null
    private var generation = 0
    /** Identity epoch: bumped on every session change, captured by in-flight writes. */
    private var sessionGeneration = 0L
    private var authorizationRefreshFailed = false
    private val replyJobs = mutableMapOf<String, Job>()
    /** Cursors requested in the current load cycle; a repeat means the server loops. */
    private val consumedCursors = mutableSetOf<String>()
    /** Pause automatic pagination when distinct cursors keep returning no new comments. */
    private var emptyAppends = 0
    /** Threads whose first replies page was requested in this load cycle. */
    private val repliesStarted = mutableSetOf<String>()
    private val replyCursors = mutableMapOf<String, MutableSet<String>>()
    /** The first read was cancelled while the panel was closed; the next open restarts it. */
    private var firstLoadCancelled = false

    init {
        when (val content = this.target) {
            is CommentTarget.Video -> {
                savedState["commentsTargetKind"] = "video"
                savedState["commentsTargetId"] = content.id
            }
            is CommentTarget.Post -> {
                savedState["commentsTargetKind"] = "post"
                savedState["commentsTargetId"] = content.identity.postId
                savedState["commentsTargetChannel"] = content.identity.channelId
            }
        }
        savedState[KEY_VIDEO] = this.videoId
        savedState[KEY_SORT] = sort.name
        savedState[KEY_EXPANDED] = ArrayList(expanded)
        persistOverlay(restoredOverlay)
        load()
    }

    fun selectVideo(id: String) {
        if (target == CommentTarget.Video(id)) return
        actionJob?.cancel()
        target = CommentTarget.Video(id)
        videoId = id
        savedState["commentsTargetKind"] = "video"
        savedState["commentsTargetId"] = id
        savedState[KEY_VIDEO] = id
        savedState[KEY_EXPANDED] = arrayListOf<String>()
        savedState[KEY_SORT] = CommentSort.Top.name
        savedState[KEY_DRAFT] = ""
        savedState[KEY_LOADED] = 0
        _state.value = CommentsUiState(videoId = id, target = target,
            supportedMenuActions = actions?.supportedMenuActions ?: CommentMenuAction.entries.toSet())
        persistOverlay(CommentsOverlay.None)
        load()
    }

    fun refreshSession() {
        sessionGeneration++
        actionJob?.cancel()
        savedState[KEY_DRAFT] = ""
        savedState[KEY_LOADED] = 0
        overlay(CommentsOverlay.None)
        _state.update { it.copy(actionPending = false, pendingAction = null, draft = "") }
        load()
    }

    /** Web authorization does not change the account or discard the pending comment draft. */
    fun refreshAuthorization() {
        sessionGeneration++
        actionJob?.cancel()
        _state.update { it.copy(actionPending = false, pendingAction = null) }
        val retained = _state.value.content as? CommentsContent.Ready
        if (retained == null) { load(); return }
        // Restore all loaded pages before publishing; a first-page replacement would clamp
        // a deep viewport to the top even though the LazyListState itself survives.
        loadJob?.cancel()
        pageJob?.cancel()
        replyJobs.values.forEach(Job::cancel)
        replyJobs.clear()
        repliesStarted.clear()
        replyCursors.clear()
        consumedCursors.clear()
        emptyAppends = 0
        authorizationRefreshFailed = false
        val requestGeneration = ++generation
        val requestedSort = _state.value.sort
        _state.update { it.copy(refreshing = true, content = retained.copy(loadingMore = false)) }
        loadJob = viewModelScope.launch {
            try {
                val threads = linkedMapOf<String, CommentThread>()
                val visited = mutableSetOf<String>()
                var cursor: String? = null
                var first: CommentsPage? = null
                var emptyPages = 0
                do {
                    val page = repository.comments(target, requestedSort, cursor)
                    currentCoroutineContext().ensureActive()
                    if (requestGeneration != generation) return@launch
                    if (first == null) first = page
                    val previousSize = threads.size
                    page.threads.forEach { threads[it.id] = it }
                    emptyPages = if (threads.size > previousSize) 0 else emptyPages + 1
                    cursor?.let(consumedCursors::add)
                    cursor = page.nextCursor?.takeIf { visited.add(it) }
                } while (cursor != null && threads.size < retained.threads.size && emptyPages < MAX_EMPTY_APPENDS)
                val opening = requireNotNull(first)
                val old = retained.threads.associateBy { it.id }
                val content = when {
                    opening.turnedOff -> CommentsContent.Disabled
                    threads.isEmpty() && cursor == null -> CommentsContent.Empty
                    else -> CommentsContent.Ready(threads.values.map { fresh ->
                        old[fresh.id]?.let { previous -> fresh.copy(replies = previous.replies,
                            repliesNextCursor = previous.repliesNextCursor, repliesFailed = previous.repliesFailed) } ?: fresh
                    }, cursor)
                }
                savedState[KEY_LOADED] = threads.size
                if (opening.sortFallback && requestedSort == CommentSort.Newest) {
                    savedState[KEY_SORT] = CommentSort.Top.name
                    _state.update { it.copy(sort = CommentSort.Top) }
                    notices.send(AppNotice.Message("Newest comments aren't offered here. Showing top comments."))
                }
                _state.update { it.copy(content = content, total = if (content == CommentsContent.Empty) "0" else opening.total) }
                _state.value.expanded.forEach { requestReplies(it) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (requestGeneration == generation) {
                    authorizationRefreshFailed = true
                    notices.send(writeFailureNotice("comments_refresh", error))
                }
            } finally {
                if (requestGeneration == generation) _state.update { it.copy(refreshing = false) }
            }
        }
    }

    fun onAction(action: CommentAction) {
        startLoading()
        if (_state.value.actionPending && action !is CommentAction.ToggleReplies && action !is CommentAction.LoadMoreReplies &&
            action != CommentAction.LoadMore && action !is CommentAction.OpenAuthor && action !is CommentAction.OpenLink) return
        when (action) {
            CommentAction.Retry -> {
                val content = _state.value.content
                if (content is CommentsContent.Ready && content.loadMoreFailed) loadMore() else load()
            }
            CommentAction.LoadMore -> loadMore()
            is CommentAction.SelectSort -> {
                overlay(CommentsOverlay.None)
                selectSort(action.sort)
            }
            CommentAction.OpenSortMenu -> overlay(CommentsOverlay.SortMenu)
            CommentAction.CloseMenu -> when (_state.value.overlay) {
                is CommentsOverlay.Composer -> closeComposer()
                is CommentsOverlay.DeleteConfirm -> overlay(CommentsOverlay.None)
                else -> overlay(CommentsOverlay.None)
            }
            is CommentAction.MenuAction -> selectMenuAction(action.action)
            is CommentAction.ToggleReplies -> toggleReplies(action.commentId)
            is CommentAction.LoadMoreReplies -> loadMoreReplies(action.commentId)
            CommentAction.Compose -> openComposer(null, null, editing = false)
            is CommentAction.DraftChanged -> {
                if (_state.value.overlay !is CommentsOverlay.Composer || _state.value.actionPending) return
                savedState[KEY_DRAFT] = action.text
                _state.update { it.copy(draft = action.text) }
            }
            CommentAction.SubmitDraft -> submitDraft()
            CommentAction.DismissComposer -> closeComposer()
            is CommentAction.RequestDelete -> {
                if (!owned(action.threadId, action.commentId)) return
                overlay(CommentsOverlay.DeleteConfirm(
                    action.threadId, action.commentId.takeUnless { it == action.threadId },
                ))
            }
            CommentAction.ConfirmDelete -> confirmDelete()
            CommentAction.CancelDelete -> overlay(CommentsOverlay.None)
            is CommentAction.LikeThread -> rate(action.commentId, VideoRating.Like)
            is CommentAction.DislikeThread -> rate(action.commentId, VideoRating.Dislike)
            is CommentAction.ReportThread -> dispatch(
                CommentNavigationRequest.ReportComment(target, action.commentId, action.commentId),
            )
            is CommentAction.OpenThreadMenu -> {
                overlay(CommentsOverlay.ThreadMenu(action.commentId))
                navigation.trySend(CommentNavigationRequest.OpenCommentMenu(target, action.commentId))
            }
            is CommentAction.OpenAuthor -> navigation.trySend(CommentNavigationRequest.OpenAuthor(action.channelId))
            is CommentAction.OpenLink -> navigation.trySend(CommentNavigationRequest.OpenLink(action.link))
            is CommentAction.Reply -> openComposer(
                action.threadId,
                action.commentId.takeUnless { it == action.threadId },
                editing = false,
            )
            is CommentAction.LikeReply -> rate(action.replyId, VideoRating.Like)
            is CommentAction.DislikeReply -> rate(action.replyId, VideoRating.Dislike)
            is CommentAction.ReportReply -> dispatch(
                CommentNavigationRequest.ReportComment(target, action.commentId, action.replyId),
            )
            is CommentAction.OpenReplyMenu -> {
                overlay(CommentsOverlay.ReplyMenu(action.commentId, action.replyId))
                navigation.trySend(CommentNavigationRequest.OpenReplyMenu(target, action.commentId, action.replyId))
            }
        }
    }

    /** Owner-only rows open a local composer or confirmation and never mutate the fixture. */
    private fun selectMenuAction(action: CommentMenuAction) {
        val current = uiState.value.overlay
        val threadId: String
        val replyId: String?
        val owned: Boolean
        val body: String
        when (current) {
            is CommentsOverlay.ThreadMenu -> {
                val thread = findThread(current.threadId) ?: return
                threadId = thread.id
                replyId = null
                owned = thread.ownedByViewer
                body = thread.body
            }
            is CommentsOverlay.ReplyMenu -> {
                val thread = findThread(current.threadId) ?: return
                val reply = thread.replies.firstOrNull { it.id == current.replyId } ?: return
                threadId = thread.id
                replyId = reply.id
                owned = reply.ownedByViewer
                body = reply.body
            }
            else -> return
        }
        if (action.ownerOnly && !owned) return
        when (action) {
            CommentMenuAction.Reply -> openComposer(threadId, replyId, editing = false)
            CommentMenuAction.Report -> {
                overlay(CommentsOverlay.None)
                dispatch(
                    CommentNavigationRequest.ReportComment(target, threadId, replyId ?: threadId),
                )
            }
            CommentMenuAction.Edit -> openComposer(threadId, replyId, editing = true, initial = body)
            CommentMenuAction.Delete -> {
                overlay(CommentsOverlay.None)
                overlay(CommentsOverlay.DeleteConfirm(threadId, replyId))
            }
        }
    }

    private fun findThread(threadId: String): CommentThread? =
        (_state.value.content as? CommentsContent.Ready)?.threads?.firstOrNull { it.id == threadId }

    private fun owned(threadId: String, commentId: String): Boolean {
        val thread = findThread(threadId) ?: return false
        if (commentId == threadId) return thread.ownedByViewer
        return thread.replies.firstOrNull { it.id == commentId }?.ownedByViewer == true
    }

    private fun openComposer(
        threadId: String?,
        replyId: String?,
        editing: Boolean,
        initial: String = "",
    ) {
        savedState[KEY_DRAFT] = initial
        _state.update { it.copy(draft = initial) }
        overlay(CommentsOverlay.Composer(threadId, replyId, editing))
    }

    private fun closeComposer() {
        if (_state.value.actionPending) return
        savedState[KEY_DRAFT] = ""
        _state.update { it.copy(draft = "") }
        overlay(CommentsOverlay.None)
    }

    private fun submitDraft() {
        val composer = _state.value.overlay as? CommentsOverlay.Composer ?: return
        val body = _state.value.draft.trim()
        if (body.isEmpty()) return
        val threadId = composer.threadId
        val commentId = composer.replyId ?: threadId
        if (threadId == null || commentId == null) {
            dispatch(CommentNavigationRequest.SubmitComment(target, body))
        } else if (composer.editing) {
            dispatch(CommentNavigationRequest.EditComment(target, threadId, commentId, body))
        } else {
            dispatch(CommentNavigationRequest.SubmitReply(target, threadId, commentId, body))
        }
        if (actions == null) closeComposer()
    }

    private fun confirmDelete() {
        val confirm = _state.value.overlay as? CommentsOverlay.DeleteConfirm ?: return
        dispatch(
            CommentNavigationRequest.DeleteComment(
                target, confirm.threadId, confirm.replyId ?: confirm.threadId,
            ),
        )
        if (actions == null) overlay(CommentsOverlay.None)
    }

    private fun rate(id: String, selected: VideoRating) {
        val threads = (_state.value.content as? CommentsContent.Ready)?.threads ?: return
        val previous = threads.firstNotNullOfOrNull { thread ->
            if (thread.id == id) thread.rating else thread.replies.firstOrNull { it.id == id }?.rating
        } ?: return
        val desired = if (previous == selected) VideoRating.None else selected
        dispatch(if (selected == VideoRating.Like) CommentNavigationRequest.LikeComment(target, id, desired, previous)
            else CommentNavigationRequest.DislikeComment(target, id, desired, previous))
    }

    private fun dispatch(request: CommentNavigationRequest) {
        val service = actions ?: run { navigation.trySend(request); return }
        if (_state.value.actionPending) return
        if (authorizationRefreshFailed && target is CommentTarget.Post) refreshAuthorization()
        val requestedTarget = target
        val closesEditor = request is CommentNavigationRequest.SubmitComment ||
            request is CommentNavigationRequest.SubmitReply || request is CommentNavigationRequest.EditComment ||
            request is CommentNavigationRequest.DeleteComment
        _state.update { it.copy(actionPending = true, pendingAction = request) }
        // The write is bound to the identity it started under: a result (or the
        // cancellation unwind) from a switched session must not clear the new
        // session's pending state or publish into it.
        val requestSession = sessionGeneration
        val opening = loadJob
        fun stale() = sessionGeneration != requestSession || target != requestedTarget
        actionJob = viewModelScope.launch {
            try {
                // Post action tokens come from the authorized opening read. A tap immediately
                // after returning from authorization must wait for that read to finish.
                if (requestedTarget is CommentTarget.Post) opening?.join()
                currentCoroutineContext().ensureActive()
                if (stale()) return@launch
                if (authorizationRefreshFailed) throw UnconfirmedActionException()
                val mutation = service.perform(request)
                currentCoroutineContext().ensureActive()
                if (stale()) return@launch
                applyMutation(request, mutation)
                _state.update { it.copy(actionPending = false, pendingAction = null) }
                if (closesEditor) closeComposer()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: PostAuthorizationRequiredException) {
                if (!stale()) authorizations.send(Unit)
            }
            catch (_: SignInRequiredException) { if (!stale()) notices.send(AppNotice.SignIn) }
            catch (_: UnsupportedOperationException) { if (!stale()) notices.send(AppNotice.Message("This action is not available.")) }
            catch (error: Exception) {
                if (!stale()) notices.send(writeFailureNotice(
                    "comment_${request::class.simpleName.orEmpty()}", error))
            }
            finally { if (!stale()) _state.update { it.copy(actionPending = false, pendingAction = null) } }
        }
    }

    /** Apply the confirmed change to the current list, preserving pagination and replies loaded during the write. */
    private fun applyMutation(request: CommentNavigationRequest, mutation: CommentMutation) {
        val changed = mutation.comment
        _state.update { state ->
            val ready = state.content as? CommentsContent.Ready
            var threads = ready?.threads.orEmpty()
            var expanded = state.expanded
            var total = state.total
            fun updateComment(id: String, transform: (VideoRating, String) -> Pair<VideoRating, String>) {
                threads = threads.map { thread ->
                    if (thread.id == id) {
                        val (rating, likes) = transform(thread.rating, thread.likes)
                        thread.copy(rating = rating, likes = likes)
                    } else if (thread.replies.any { it.id == id }) thread.copy(replies = thread.replies.map { reply ->
                        if (reply.id != id) reply else {
                            val (rating, likes) = transform(reply.rating, reply.likes)
                            reply.copy(rating = rating, likes = likes)
                        }
                    }) else thread
                }
            }
            when (request) {
                is CommentNavigationRequest.LikeComment, is CommentNavigationRequest.DislikeComment -> {
                    val id = when (request) {
                        is CommentNavigationRequest.LikeComment -> request.commentId
                        is CommentNavigationRequest.DislikeComment -> request.commentId
                        else -> error("Not a rating")
                    }
                    val desired = when (request) {
                        is CommentNavigationRequest.LikeComment -> request.rating
                        is CommentNavigationRequest.DislikeComment -> request.rating
                        else -> error("Not a rating")
                    }
                    updateComment(id) { previous, likes ->
                        val rating = changed?.takeIf { it.id == id }?.rating
                            ?: desired
                        val count = changed?.takeIf { it.id == id }?.likes ?: adjustedCommentCount(likes,
                            (if (rating == VideoRating.Like) 1 else 0) - (if (previous == VideoRating.Like) 1 else 0))
                        rating to count
                    }
                }
                is CommentNavigationRequest.DeleteComment -> {
                    if (request.threadId == request.commentId) {
                        threads = threads.filterNot { it.id == request.commentId }
                        expanded -= request.threadId
                        total = adjustedCommentCount(total, -1)
                    } else threads = threads.map { thread ->
                        if (thread.id != request.threadId) thread else thread.copy(
                            replies = thread.replies.filterNot { it.id == request.commentId }, replyCount = (thread.replyCount - 1).coerceAtLeast(0))
                    }
                }
                is CommentNavigationRequest.EditComment -> {
                    threads = threads.map { thread ->
                        if (thread.id != request.threadId) thread
                        else if (request.commentId == thread.id) thread.copy(body = changed?.body ?: request.body, runs = changed?.runs.orEmpty())
                        else thread.copy(replies = thread.replies.map {
                            if (it.id == request.commentId) it.copy(body = changed?.body ?: request.body, runs = changed?.runs.orEmpty()) else it
                        })
                    }
                }
                is CommentNavigationRequest.SubmitComment -> if (changed != null) {
                    threads = listOf(changed) + threads.filterNot { it.id == changed.id }
                    total = adjustedCommentCount(total, 1)
                }
                is CommentNavigationRequest.SubmitReply -> {
                    threads = threads.map { thread ->
                        if (thread.id != request.threadId) thread else thread.copy(replyCount = thread.replyCount + 1,
                            replies = if (changed == null) thread.replies else (thread.replies + CommentReply(changed.id,
                                changed.author, changed.age, changed.body, changed.likes, changed.authorChannelId,
                                changed.ownedByViewer, changed.runs, changed.rating)).distinctBy { it.id })
                    }
                    if (changed != null) expanded += request.threadId
                }
                else -> Unit // Reporting does not change the visible conversation.
            }
            val content = when {
                threads.isNotEmpty() -> (ready ?: CommentsContent.Ready(emptyList())).copy(threads = threads)
                ready != null && ready.nextCursor != null -> ready.copy(threads = threads)
                ready != null -> CommentsContent.Empty
                else -> state.content
            }
            state.copy(content = content, expanded = expanded, total = total)
        }
        val state = _state.value
        savedState[KEY_EXPANDED] = ArrayList(state.expanded)
        savedState[KEY_LOADED] = (state.content as? CommentsContent.Ready)?.threads?.size ?: 0
        if (changed == null && (request is CommentNavigationRequest.SubmitComment || request is CommentNavigationRequest.SubmitReply))
            notices.trySend(AppNotice.Message("Comment posted."))
        if (request is CommentNavigationRequest.ReportComment) notices.trySend(AppNotice.Message("Comment reported."))
    }

    private fun overlay(next: CommentsOverlay) {
        _state.update { it.copy(overlay = next) }
        persistOverlay(next)
    }

    private fun persistOverlay(overlay: CommentsOverlay) {
        when (overlay) {
            CommentsOverlay.SortMenu -> {
                savedState[KEY_OVERLAY] = "sort"
                clearOverlayIds()
            }
            is CommentsOverlay.ThreadMenu -> {
                savedState[KEY_OVERLAY] = "thread"
                savedState[KEY_THREAD] = overlay.threadId
                savedState.remove<String>(KEY_REPLY)
            }
            is CommentsOverlay.ReplyMenu -> {
                savedState[KEY_OVERLAY] = "reply"
                savedState[KEY_THREAD] = overlay.threadId
                savedState[KEY_REPLY] = overlay.replyId
            }
            is CommentsOverlay.Composer -> {
                savedState[KEY_OVERLAY] = "composer"
                if (overlay.threadId == null) savedState.remove<String>(KEY_THREAD)
                else savedState[KEY_THREAD] = overlay.threadId
                if (overlay.replyId == null) savedState.remove<String>(KEY_REPLY)
                else savedState[KEY_REPLY] = overlay.replyId
                savedState[KEY_EDITING] = overlay.editing
            }
            is CommentsOverlay.DeleteConfirm -> {
                savedState[KEY_OVERLAY] = "delete"
                savedState[KEY_THREAD] = overlay.threadId
                if (overlay.replyId == null) savedState.remove<String>(KEY_REPLY)
                else savedState[KEY_REPLY] = overlay.replyId
            }
            CommentsOverlay.None -> {
                savedState[KEY_OVERLAY] = "none"
                clearOverlayIds()
            }
        }
    }

    private fun clearOverlayIds() {
        savedState.remove<String>(KEY_THREAD)
        savedState.remove<String>(KEY_REPLY)
        savedState.remove<Boolean>(KEY_EDITING)
    }

    private fun selectSort(next: CommentSort) {
        if (next == _state.value.sort) return
        savedState[KEY_SORT] = next.name
        savedState[KEY_LOADED] = 0
        _state.update { it.copy(sort = next) }
        load()
    }

    private fun toggleReplies(commentId: String) {
        val current = _state.value.expanded
        val next = if (commentId in current && findThread(commentId)?.repliesFailed != true) current - commentId else current + commentId
        savedState[KEY_EXPANDED] = ArrayList(next)
        _state.update { it.copy(expanded = next) }
        requestReplies(commentId, next)
    }

    /** Expansion auto-fetches only the first replies page; further pages are explicit. */
    private fun requestReplies(commentId: String, next: Set<String> = _state.value.expanded) {
        val thread = findThread(commentId) ?: return
        if (commentId !in next || replyJobs[commentId]?.isActive == true) return
        if (thread.repliesFailed) {
            // Retry exactly the page that failed, not the whole thread.
            fetchReplies(commentId, thread.repliesNextCursor)
            return
        }
        if (thread.replies.isNotEmpty() || commentId in repliesStarted || thread.replyKey == null) return
        repliesStarted += commentId
        fetchReplies(commentId, null)
    }

    private fun loadMoreReplies(commentId: String) {
        val thread = findThread(commentId) ?: return
        if (commentId !in _state.value.expanded) return
        val cursor = thread.repliesNextCursor ?: return
        if (replyJobs[commentId]?.isActive == true) return
        fetchReplies(commentId, cursor)
    }

    private fun fetchReplies(commentId: String, cursor: String?) {
        val thread = findThread(commentId) ?: return
        val requestGeneration = generation
        updateThread(commentId) { it.copy(repliesLoading = true, repliesFailed = false) }
        replyJobs[commentId] = viewModelScope.launch {
            val page = try {
                repository.repliesPage(target, thread, cursor)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (requestGeneration == generation) {
                    updateThread(commentId) { it.copy(repliesLoading = false, repliesFailed = true) }
                }
                return@launch
            }
            currentCoroutineContext().ensureActive()
            if (requestGeneration != generation) return@launch
            // A repeated or self-pointing cursor ends the thread instead of looping.
            val visited = replyCursors.getOrPut(commentId) { mutableSetOf() }
            (cursor ?: thread.replyKey)?.let(visited::add)
            val nextCursor = page.nextCursor?.takeUnless { it == cursor || !visited.add(it) }
            updateThread(commentId) { current ->
                current.copy(
                    replies = (current.replies + page.replies).distinctBy { it.id },
                    repliesNextCursor = nextCursor,
                    repliesLoading = false,
                    repliesFailed = false,
                )
            }
        }
    }

    private fun updateThread(id: String, transform: (CommentThread) -> CommentThread) {
        _state.update { state ->
            val content = state.content as? CommentsContent.Ready ?: return@update state
            state.copy(content = content.copy(threads = content.threads.map { if (it.id == id) transform(it) else it }))
        }
    }

    private fun load() {
        if (!loadsEnabled) return
        authorizationRefreshFailed = false
        replyJobs.values.forEach(Job::cancel)
        replyJobs.clear()
        repliesStarted.clear()
        replyCursors.clear()
        consumedCursors.clear()
        emptyAppends = 0
        loadJob?.cancel()
        pageJob?.cancel()
        val requestGeneration = ++generation
        val requestedSort = _state.value.sort
        firstLoadCancelled = false
        // Read the restore target before the first page overwrites it with its own size.
        val restoreTarget = savedState.get<Int>(KEY_LOADED) ?: 0
        _state.update { it.copy(content = CommentsContent.Loading, refreshing = false) }
        loadJob = viewModelScope.launch {
            // The first page publishes immediately; restore then walks forward one
            // page at a time, each published as it arrives, never blocking the first paint.
            if (!fetchPage(null, requestedSort, requestGeneration, firstPage = true)) return@launch
            while (requestGeneration == generation) {
                val ready = _state.value.content as? CommentsContent.Ready ?: break
                if (ready.threads.size >= restoreTarget) break
                val cursor = ready.nextCursor ?: break
                if (!fetchPage(cursor, requestedSort, requestGeneration, firstPage = false)) break
            }
        }
    }

    /** Returns false when the caller's walk must stop: a failure (state already carries it) or a stale generation. */
    private suspend fun fetchPage(cursor: String?, sort: CommentSort, requestGeneration: Int, firstPage: Boolean): Boolean {
        if (requestGeneration != generation) return false
        if (cursor != null && cursor in consumedCursors) {
            // A→B→A cursor loop: close the list instead of refetching forever.
            _state.update { state ->
                (state.content as? CommentsContent.Ready)?.let { state.copy(content = it.copy(nextCursor = null)) } ?: state
            }
            return false
        }
        val page = try {
            repository.comments(target, sort, cursor)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (requestGeneration != generation) return false
            _state.update { state ->
                val ready = state.content as? CommentsContent.Ready
                if (ready != null) state.copy(content = ready.copy(loadingMore = false, loadMoreFailed = true))
                else state.copy(content = CommentsContent.Failed)
            }
            return false
        }
        currentCoroutineContext().ensureActive()
        if (requestGeneration != generation) return false
        cursor?.let(consumedCursors::add)
        if (firstPage && page.sortFallback && sort == CommentSort.Newest) {
            savedState[KEY_SORT] = CommentSort.Top.name
            _state.update { it.copy(sort = CommentSort.Top) }
            notices.send(AppNotice.Message("Newest comments aren't offered here. Showing top comments."))
        }
        if (firstPage) {
            val content = when {
                page.turnedOff -> CommentsContent.Disabled
                page.threads.isEmpty() && page.nextCursor == null -> CommentsContent.Empty
                else -> CommentsContent.Ready(page.threads.distinctBy { it.id }, page.nextCursor)
            }
            savedState[KEY_LOADED] = page.threads.size
            _state.update { it.copy(content = content, total = if (content == CommentsContent.Empty) "0" else page.total) }
            _state.value.expanded.forEach { requestReplies(it) }
        } else {
            val ready = _state.value.content as? CommentsContent.Ready ?: return false
            val threads = (ready.threads + page.threads).distinctBy { it.id }
            val nextCursor = page.nextCursor?.takeUnless { it in consumedCursors }
            emptyAppends = if (threads.size > ready.threads.size) 0 else emptyAppends + 1
            val paused = nextCursor != null && emptyAppends >= MAX_EMPTY_APPENDS
            savedState[KEY_LOADED] = threads.size
            _state.update { state ->
                val current = state.content as? CommentsContent.Ready ?: return@update state
                state.copy(
                    total = if (threads.isEmpty() && nextCursor == null) "0" else page.total.ifBlank { state.total },
                    content = if (threads.isEmpty() && nextCursor == null) CommentsContent.Empty else current.copy(
                        threads = threads,
                        nextCursor = nextCursor,
                        loadingMore = false,
                        loadMoreFailed = paused,
                    ),
                )
            }
            _state.value.expanded.forEach { requestReplies(it) }
            if (paused) return false
        }
        return true
    }

    private fun loadMore() {
        val ready = _state.value.content as? CommentsContent.Ready ?: return
        if (ready.loadingMore || ready.nextCursor == null || loadJob?.isActive == true || pageJob?.isActive == true) return
        val requestGeneration = generation
        val requestedSort = _state.value.sort
        _state.update { it.copy(content = ready.copy(loadingMore = true, loadMoreFailed = false)) }
        pageJob = viewModelScope.launch {
            fetchPage(ready.nextCursor, requestedSort, requestGeneration, firstPage = false)
        }
    }

    /** Panel closed or the target switched: in-flight reads stop; loaded content stays. */
    fun cancelTransient() {
        if (_state.value.refreshing) authorizationRefreshFailed = true
        val inFlightReplies = replyJobs.filterValues { it.isActive }.keys.toSet()
        loadJob?.cancel()
        pageJob?.cancel()
        replyJobs.values.forEach(Job::cancel)
        replyJobs.clear()
        repliesStarted -= inFlightReplies
        _state.update { state ->
            val ready = state.content as? CommentsContent.Ready ?: return@update state
            state.copy(
                refreshing = false, content = ready.copy(loadingMore = false, threads = ready.threads.map {
                    if (it.repliesLoading) it.copy(repliesLoading = false) else it
                }),
            )
        }
        if (_state.value.content == CommentsContent.Loading) firstLoadCancelled = true
    }

    fun ensureLoaded() {
        if (authorizationRefreshFailed && loadJob?.isActive != true) {
            refreshAuthorization()
        } else if (firstLoadCancelled && loadJob?.isActive != true && _state.value.content == CommentsContent.Loading) {
            load()
        } else {
            // Closing cancels a first reply read without collapsing its thread.
            // Resume that read on reopening; settled/failed pages remain untouched.
            _state.value.expanded.filter { findThread(it)?.repliesFailed != true }.forEach { requestReplies(it) }
        }
    }

    fun dispose() {
        cancelTransient()
        viewModelScope.cancel()
        navigation.close()
        notices.close()
    }

    override fun onCleared() = dispose()

    private companion object {
        const val MAX_EMPTY_APPENDS = 3
        const val KEY_VIDEO = "commentsVideoId"
        const val KEY_SORT = "commentsSort"
        const val KEY_EXPANDED = "commentsExpanded"
        const val KEY_LOADED = "commentsLoadedCount"
        const val KEY_OVERLAY = "commentsOverlay"
        const val KEY_THREAD = "commentsMenuThread"
        const val KEY_REPLY = "commentsMenuReply"
        const val KEY_EDITING = "commentsEditing"
        const val KEY_DRAFT = "commentsDraft"
    }
}
