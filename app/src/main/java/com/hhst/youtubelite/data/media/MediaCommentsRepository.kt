package com.hhst.youtubelite.data.media

import com.hhst.youtubelite.comments.domain.CommentActions
import com.hhst.youtubelite.comments.domain.CommentAuthor
import com.hhst.youtubelite.comments.domain.CommentLink
import com.hhst.youtubelite.comments.domain.CommentMenuAction
import com.hhst.youtubelite.comments.domain.CommentMutation
import com.hhst.youtubelite.comments.domain.CommentNavigationRequest
import com.hhst.youtubelite.comments.domain.CommentReply
import com.hhst.youtubelite.comments.domain.CommentTarget
import com.hhst.youtubelite.comments.domain.CommentTextRun
import com.hhst.youtubelite.comments.domain.CommentThread
import com.hhst.youtubelite.comments.domain.CommentsPage
import com.hhst.youtubelite.comments.domain.CommentsRepository
import com.hhst.youtubelite.comments.domain.RepliesPage
import com.hhst.youtubelite.comments.domain.safeCommentUrl
import com.hhst.youtubelite.comments.domain.CommentSort as AppSort
import com.hhst.youtubelite.post.domain.PostAuthorizationRequiredException
import com.hhst.youtubelite.watch.domain.SignInRequiredException
import com.hhst.youtubelite.watch.domain.UnconfirmedActionException
import com.hhst.youtubelite.watch.domain.VideoRating
import com.liskovsoft.youtubeapi.videoinfo.V2.Comment
import com.liskovsoft.youtubeapi.videoinfo.V2.CommentPage
import com.liskovsoft.youtubeapi.videoinfo.V2.CommentVote
import com.liskovsoft.youtubeapi.videoinfo.V2.HttpClient
import com.liskovsoft.youtubeapi.videoinfo.V2.ReadResult
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestContext
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestFailure
import com.liskovsoft.youtubeapi.videoinfo.V2.createComment
import com.liskovsoft.youtubeapi.videoinfo.V2.createVideoComment
import com.liskovsoft.youtubeapi.videoinfo.V2.deleteComment
import com.liskovsoft.youtubeapi.videoinfo.V2.loadCommentReplies
import com.liskovsoft.youtubeapi.videoinfo.V2.loadComments
import com.liskovsoft.youtubeapi.videoinfo.V2.loadPost
import com.liskovsoft.youtubeapi.videoinfo.V2.loadPostComments
import com.liskovsoft.youtubeapi.videoinfo.V2.loadWatch
import com.liskovsoft.youtubeapi.videoinfo.V2.rateComment
import com.liskovsoft.youtubeapi.videoinfo.V2.replyComment
import com.liskovsoft.youtubeapi.videoinfo.V2.replyVideoComment
import kotlinx.coroutines.CancellationException

class MediaCommentsRepository @JvmOverloads constructor(private val client: MediaClient,
    private val shared: SharedWatchReads? = null,
    private val webContext: (RequestContext) -> RequestContext? = { it.takeIf { it.webSession?.signedIn == true } },
) : CommentsRepository, CommentActions {
    override val supportedMenuActions = setOf(CommentMenuAction.Reply, CommentMenuAction.Delete)
    private data class CommentKey(val target: CommentTarget, val id: String)
    private class Entry(var comment: Comment? = null, var readSequence: Long = 0, var mutationSequence: Long = 0,
        var parentId: String? = null)
    private class TargetActions(var createKey: String? = null, var authorizationRequired: Boolean = false,
        var readSequence: Long = 0)
    private class SessionActions(val generation: Long) {
        var sequence = 0L
        val comments = mutableMapOf<CommentKey, Entry>()
        val targets = mutableMapOf<CommentTarget, TargetActions>()
    }
    private data class ReadTicket(val session: SessionActions, val sequence: Long)
    private val actionLock = Any()
    private var actionSession: SessionActions? = null

    private fun beginRead(generation: Long): ReadTicket = synchronized(actionLock) {
        // Generation is monotonic for this repository's lifetime; late workers stay detached.
        if (actionSession?.let { generation < it.generation } == true)
            return@synchronized ReadTicket(SessionActions(generation), 1)
        val session = actionSession?.takeIf { it.generation == generation }
            ?: SessionActions(generation).also { actionSession = it }
        ReadTicket(session, ++session.sequence)
    }

    private fun rememberRead(ticket: ReadTicket, target: CommentTarget, comment: Comment,
        repeatedParent: Boolean = false, parentId: String? = null) = synchronized(actionLock) {
        if (actionSession !== ticket.session) return@synchronized
        val entry = ticket.session.comments.getOrPut(CommentKey(target, requireNotNull(comment.id))) { Entry() }
        if (entry.mutationSequence <= ticket.sequence && entry.readSequence <= ticket.sequence) {
            val previous = entry.comment
            // A repeated parent is often only an action renderer. Preserve the main list's
            // coherent viewer/count snapshot and enrich capabilities without reviving deletion.
            if (repeatedParent && previous == null && entry.mutationSequence != 0L) return@synchronized
            entry.comment = if (repeatedParent && previous != null) previous.copy(
                likeKey = comment.likeKey ?: previous.likeKey, unlikeKey = comment.unlikeKey ?: previous.unlikeKey,
                dislikeKey = comment.dislikeKey ?: previous.dislikeKey, undislikeKey = comment.undislikeKey ?: previous.undislikeKey,
                deleteKey = comment.deleteKey ?: previous.deleteKey, replyCreateKey = comment.replyCreateKey ?: previous.replyCreateKey,
                replyKey = comment.replyKey ?: previous.replyKey, detailKey = comment.detailKey ?: previous.detailKey,
            ) else comment
            entry.parentId = parentId ?: comment.parentId ?: entry.parentId
            entry.readSequence = ticket.sequence
        }
    }

    private fun rememberOpening(ticket: ReadTicket, target: CommentTarget, createKey: String?, authorizationRequired: Boolean?) =
        synchronized(actionLock) {
            if (actionSession !== ticket.session) return@synchronized
            val actions = ticket.session.targets.getOrPut(target) { TargetActions() }
            if (actions.readSequence > ticket.sequence) return@synchronized
            actions.readSequence = ticket.sequence
            if (createKey != null) actions.createKey = createKey
            if (authorizationRequired != null) actions.authorizationRequired = authorizationRequired
        }

    private fun rememberMutation(session: SessionActions, target: CommentTarget, id: String, comment: Comment?) = synchronized(actionLock) {
        if (actionSession !== session) return@synchronized
        val entry = session.comments.getOrPut(CommentKey(target, id)) { Entry() }
        // Keep a deleted entry's revision so a page already in flight cannot revive its capabilities.
        entry.comment = comment
        entry.mutationSequence = ++session.sequence
    }

    override suspend fun comments(target: CommentTarget, sort: AppSort, cursor: String?): CommentsPage = client.readIdempotent { context, http ->
        val ticket = beginRead(context.generation)
        var authorizationRequired: Boolean? = null
        val postContext = if (target is CommentTarget.Post) webContext(context) ?: context.anonymous() else context
        fun loadPage(key: String) = if (target is CommentTarget.Post) loadPostComments(key, postContext, http).requireValue()
            else loadCommentReplies(key, context, http).requireValue()
        var sortSelectedFromOpening = false
        var page = when {
            cursor != null -> loadPage(cursor)
            target is CommentTarget.Video -> {
                val opened = (shared?.read(target.id, context, http, fresh = false) { id, ctx, transport ->
                    loadWatch(id, ctx, http = transport)
                } ?: loadWatch(target.id, context, http = http)).requireValue()
                // Watch may already expose the requested lane. Do not fetch Top just
                // to discover a Newest continuation that the opening response supplied.
                val requested = if (sort == AppSort.Newest) opened.commentSortOptions.firstOrNull {
                    it.title?.contains("newest", true) == true
                } else null
                sortSelectedFromOpening = requested != null
                loadComments(target.id, context, http = http,
                    opened = if (requested == null) opened else opened.copy(commentsKey = requested.key)).requireValue()
            }
            target is CommentTarget.Post -> {
                val opened = loadPost(target.identity.postId, postContext, http).requireValue()
                authorizationRequired = opened.posts.any { it.postId == target.identity.postId && it.authorizationRequired }
                val key = opened.commentsKey
                if (key == null) CommentPage(emptyList(), null, null) else loadPage(key)
            }
            else -> error("Unknown comment target")
        }
        var sortFallback = false
        val createKey = page.createKey
        if (cursor == null && sort == AppSort.Newest && !sortSelectedFromOpening) {
            val option = page.sortOptions.firstOrNull { it.title?.contains("newest", true) == true }
            if (option == null) {
                // Some videos only offer the Top lane; degrade honestly instead of failing the panel.
                sortFallback = true
            } else if (!option.selected) {
                page = loadPage(option.key).copy(total = page.total)
            }
        }
        rememberOpening(ticket, target, page.createKey ?: createKey, authorizationRequired)
        CommentsPage(page.total.orEmpty(), page.comments.mapNotNull { comment ->
            val id = comment.id ?: return@mapNotNull null
            rememberRead(ticket, target, comment)
            comment.thread()
        }, page.nextKey, turnedOff = page.turnedOff, sortFallback = sortFallback)
    }

    /** One replies page per call; the panel walks the continuation explicitly. */
    override suspend fun repliesPage(target: CommentTarget, thread: CommentThread, cursor: String?): RepliesPage {
        val key = cursor ?: thread.replyKey ?: synchronized(actionLock) {
            actionSession?.comments?.get(CommentKey(target, thread.id))?.comment?.replyKey
        } ?: return RepliesPage(emptyList(), null)
        return client.read { context, http ->
            val ticket = beginRead(context.generation)
            val page = if (target is CommentTarget.Post) loadPostComments(key, webContext(context) ?: context.anonymous(), http).requireValue()
                else loadCommentReplies(key, context, http).requireValue()
            RepliesPage(page.comments.mapNotNull { comment ->
                val id = comment.id ?: return@mapNotNull null
                rememberRead(ticket, target, comment, repeatedParent = id == thread.id,
                    parentId = thread.id.takeUnless { id == it })
                // TV reply continuations repeat the active parent before the child comments.
                if (id == thread.id) return@mapNotNull null
                CommentReply(id, comment.author(), comment.published.orEmpty(), comment.text.orEmpty(),
                    comment.likeCount.orEmpty(), comment.authorId.orEmpty(), !comment.deleteKey.isNullOrBlank(), runs = comment.textRuns(), rating = comment.rating())
            }, page.nextKey)
        }
    }
    override suspend fun perform(request: CommentNavigationRequest): CommentMutation {
        return try {
            when (request) {
                is CommentNavigationRequest.LikeComment -> return rate(request.target, request.commentId, request.rating, request.previous)
                is CommentNavigationRequest.DislikeComment -> return rate(request.target, request.commentId, request.rating, request.previous)
                else -> Unit
            }
            client.write { native, http ->
                val owner = synchronized(actionLock) { actionSession?.takeIf { it.generation == native.generation } }
                val context = writeContext(native, request.target, owner)
                if (owner == null || owner.generation != context.generation)
                    throw UnconfirmedActionException()
                fun source(id: String) = synchronized(actionLock) {
                    owner.takeIf { actionSession === it }?.comments?.get(CommentKey(requireNotNull(request.target), id))?.comment
                }
                    ?: throw UnconfirmedActionException()
                val reply = when (request) {
                    is CommentNavigationRequest.SubmitComment -> {
                        val key = synchronized(actionLock) { owner.targets[request.target]?.createKey }
                        when (val target = request.target) {
                            is CommentTarget.Video -> createVideoComment(target.id, request.body, key, context, http)
                            is CommentTarget.Post -> createComment(request.body, key, context, http)
                        }
                    }
                    is CommentNavigationRequest.SubmitReply -> {
                        val comment = source(request.threadId)
                        when (val target = request.target) {
                            is CommentTarget.Video -> replyVideoComment(target.id, request.threadId, request.body, comment.replyCreateKey, context, http)
                            is CommentTarget.Post -> replyComment(request.body, comment.replyCreateKey, context, http)
                        }
                    }
                    is CommentNavigationRequest.DeleteComment -> deleteComment(source(request.commentId), context, http)
                    else -> throw UnsupportedOperationException()
                }.requireValue()
                reply.action.requireConfirmation()
                shared?.clear()
                val changed = reply.comment
                val changedId = changed?.id
                if (changedId != null) rememberMutation(owner, requireNotNull(request.target), changedId, changed)
                if (request is CommentNavigationRequest.DeleteComment) {
                    rememberMutation(owner, request.target, request.commentId, null)
                }
                CommentMutation(changed?.takeIf { !it.id.isNullOrBlank() }?.thread())
            }
        } catch (error: MediaException) {
            if (error.failure.kind == RequestFailure.AUTH) {
                if (error.failure.code == "session_changed") throw CancellationException("Session changed")
                if (request.target is CommentTarget.Post) throw PostAuthorizationRequiredException()
                throw SignInRequiredException()
            }
            throw error
        }
    }

    private fun writeContext(native: RequestContext, target: CommentTarget?, owner: SessionActions?): RequestContext {
        val context = if (target is CommentTarget.Post) {
            if (!native.signedIn) throw SignInRequiredException()
            if (synchronized(actionLock) { owner?.targets?.get(target)?.authorizationRequired == true })
                throw PostAuthorizationRequiredException()
            webContext(native) ?: throw PostAuthorizationRequiredException()
        } else native
        if (!context.signedIn) throw SignInRequiredException()
        return context
    }

    private suspend fun rate(target: CommentTarget, id: String, rating: VideoRating, previous: VideoRating): CommentMutation {
        val expected = when (rating) { VideoRating.Like -> "LIKE"; VideoRating.Dislike -> "DISLIKE"; VideoRating.None -> "INDIFFERENT" }
        val targetKey = when (target) { is CommentTarget.Video -> "video:${target.id}"; is CommentTarget.Post -> "post:${target.identity.postId}" }
        var owner: SessionActions? = null
        var original: Comment? = null
        var parentId: String? = null
        var fresh: Comment? = null
        var first = true
        val confirmed = client.desiredStateWrites.setState<Comment?>("comment:$targetKey:$id", "rating", expected,
            retryUnknownState = false,
            write = { native, http ->
                val retry = !first
                val source = if (first) {
                    first = false
                    synchronized(actionLock) {
                        owner = actionSession?.takeIf { it.generation == native.generation }
                        val entry = owner?.comments?.get(CommentKey(target, id))
                        original = entry?.comment
                        parentId = entry?.parentId ?: original?.parentId
                        original
                    }
                } else fresh
                val context = writeContext(native, target, owner)
                if (owner == null || source == null) throw UnconfirmedActionException()
                val currentPrevious = if (retry && source.viewerRating == "DISLIKE") VideoRating.Dislike
                    else if (retry) VideoRating.Like else previous
                val reply = rateComment(source, vote(rating, currentPrevious), context, http).requireValue()
                DesiredStateAttempt(reply.action, reply.comment ?: source)
            },
            observe = { native, http ->
                val context = writeContext(native, target, owner)
                val response = freshCommentPage(target, requireNotNull(original), parentId, context, http)
                if (!response.ok) ReadResult(error = response.error) else {
                    val observed = response.value!!.comments.firstOrNull { it.id == id }
                    ReadResult(DesiredStateRead(observed?.viewerRating, observed))
                }
            },
            onObserved = { fresh = it },
            onConfirmed = { changed ->
                rememberMutation(requireNotNull(owner), target, id, requireNotNull(changed))
                shared?.clear()
            })
        return CommentMutation(requireNotNull(confirmed).thread())
    }

    /** Recovery reads bypass shared opening caches and only return capabilities for a current target. */
    private fun freshCommentPage(target: CommentTarget, original: Comment, parentId: String?, context: RequestContext,
        http: HttpClient): ReadResult<CommentPage> {
        fun detail(key: String) = if (target is CommentTarget.Post) loadPostComments(key, context, http)
            else loadCommentReplies(key, context, http)
        original.detailKey?.takeIf(String::isNotBlank)?.let { return detail(it) }
        val page = when (target) {
            is CommentTarget.Video -> loadComments(target.id, context, http = http)
            is CommentTarget.Post -> {
                val opened = loadPost(target.identity.postId, context, http)
                if (!opened.ok) return ReadResult(error = opened.error)
                val key = opened.value!!.commentsKey
                if (key == null) ReadResult(CommentPage(emptyList(), null, null)) else detail(key)
            }
        }
        if (!page.ok || page.value!!.comments.any { it.id == original.id } || parentId == null) return page
        val parent = page.value!!.comments.firstOrNull { it.id == parentId } ?: return page
        return (parent.detailKey ?: parent.replyKey)?.let(::detail) ?: page
    }

    private fun vote(rating: VideoRating, previous: VideoRating) = when (rating) {
        VideoRating.Like -> CommentVote.LIKE
        VideoRating.Dislike -> CommentVote.DISLIKE
        VideoRating.None -> if (previous == VideoRating.Dislike) CommentVote.UNDISLIKE else CommentVote.UNLIKE
    }

    private fun Comment.author() = CommentAuthor(author.orEmpty(), "", authorPhoto.orEmpty())

    private fun Comment.rating() = when {
        liked -> VideoRating.Like
        disliked -> VideoRating.Dislike
        else -> VideoRating.None
    }

    private fun Comment.thread() = CommentThread(requireNotNull(id), author(), published.orEmpty(), text.orEmpty(),
        likeCount.orEmpty(), replyCount?.filter(Char::isDigit)?.toIntOrNull() ?: 0,
        authorChannelId = authorId.orEmpty(), ownedByViewer = !deleteKey.isNullOrBlank(), replyKey = replyKey,
        runs = textRuns(), rating = rating())

    private fun Comment.textRuns() = runs.map { run ->
        val link = when {
            run.videoId?.matches(Regex("[A-Za-z0-9_-]{11}")) == true -> CommentLink.Video(run.videoId.orEmpty(), run.seconds?.toLong()?.takeIf { it >= 0 })
            run.channelId?.matches(Regex("UC[A-Za-z0-9_-]{22}")) == true -> CommentLink.Channel(run.channelId.orEmpty())
            else -> run.url?.let(::safeCommentUrl)?.let(CommentLink::External)
        }
        CommentTextRun(run.text, link, run.bold, run.italic, run.strikethrough)
    }
}
