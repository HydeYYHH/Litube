package com.hhst.youtubelite.data.media

import com.hhst.youtubelite.comments.domain.*
import com.hhst.youtubelite.comments.domain.CommentSort as AppSort
import com.liskovsoft.youtubeapi.videoinfo.V2.*
import java.util.concurrent.ConcurrentHashMap

class MediaCommentsRepository(private val client: MediaClient) : CommentsRepository, CommentActions {
    override val supportedMenuActions = setOf(CommentMenuAction.Reply, CommentMenuAction.Delete)
    private val actionComments = ConcurrentHashMap<String, Pair<Long, Comment>>()
    private val createKeys = ConcurrentHashMap<CommentTarget, Pair<Long, String>>()
    private var generation: Long? = null
    private val replyKeys = ConcurrentHashMap<String, String>()
    override suspend fun comments(target: CommentTarget, sort: AppSort, cursor: String?): CommentsPage = client.readIdempotent { context, http ->
        if (generation != context.generation) {
            actionComments.clear(); createKeys.clear(); replyKeys.clear()
            generation = context.generation
        }
        var page = when {
            cursor != null -> loadCommentReplies(cursor, context, http).requireValue()
            target is CommentTarget.Video -> loadComments(target.id, context, http = http).requireValue()
            target is CommentTarget.Post -> {
                val key = loadPost(target.identity.postId, http).requireValue().commentsKey
                if (key == null) CommentPage(emptyList(), null, null) else loadCommentReplies(key, context, http).requireValue()
            }
            else -> error("Unknown comment target")
        }
        var sortFallback = false
        val createKey = page.createKey
        if (cursor == null && sort == AppSort.Newest) {
            val option = page.sortOptions.firstOrNull { it.title?.contains("newest", true) == true }
            if (option == null) {
                // Some videos only offer the Top lane; degrade honestly instead of failing the panel.
                sortFallback = true
            } else if (!option.selected) {
                page = loadCommentReplies(option.key, context, http).requireValue().copy(total = page.total)
            }
        }
        (page.createKey ?: createKey)?.let { createKeys[target] = context.generation to it }
        CommentsPage(page.total.orEmpty(), page.comments.mapNotNull { comment ->
            val id = comment.id ?: return@mapNotNull null
            actionComments["$target:$id"] = context.generation to comment
            comment.replyKey?.let { replyKeys["$target:$id"] = it }
            CommentThread(id, comment.author(), comment.published.orEmpty(), comment.text.orEmpty(), comment.likeCount.orEmpty(),
                comment.replyCount?.filter(Char::isDigit)?.toIntOrNull() ?: 0,
                authorChannelId = comment.authorId.orEmpty(), ownedByViewer = !comment.deleteKey.isNullOrBlank(), replyKey = comment.replyKey,
                runs = comment.textRuns())
        }, page.nextKey, turnedOff = page.turnedOff, sortFallback = sortFallback)
    }

    /** One replies page per call; the panel walks the continuation explicitly. */
    override suspend fun repliesPage(target: CommentTarget, thread: CommentThread, cursor: String?): RepliesPage {
        val key = cursor ?: thread.replyKey ?: replyKeys["$target:${thread.id}"] ?: return RepliesPage(emptyList(), null)
        return client.read { context, http ->
            val page = loadCommentReplies(key, context, http).requireValue()
            RepliesPage(page.comments.mapNotNull { comment ->
                val id = comment.id ?: return@mapNotNull null
                actionComments["$target:$id"] = context.generation to comment
                CommentReply(id, comment.author(), comment.published.orEmpty(), comment.text.orEmpty(),
                    comment.likeCount.orEmpty(), comment.authorId.orEmpty(), !comment.deleteKey.isNullOrBlank(), runs = comment.textRuns())
            }, page.nextKey)
        }
    }
    override suspend fun perform(request: CommentNavigationRequest) {
        try {
            client.read { context, http ->
                if (!context.signedIn) throw com.hhst.youtubelite.watch.domain.SignInRequiredException()
                if (generation != context.generation) throw com.hhst.youtubelite.watch.domain.UnconfirmedActionException()
                fun source(id: String) = actionComments["${request.target}:$id"]?.takeIf { it.first == context.generation }?.second
                    ?: throw com.hhst.youtubelite.watch.domain.UnconfirmedActionException()
                val reply = when (request) {
                    is CommentNavigationRequest.SubmitComment -> {
                        val key = createKeys[request.target]?.takeIf { it.first == context.generation }?.second
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
                    is CommentNavigationRequest.LikeComment -> likeComment(source(request.commentId), context, http)
                    is CommentNavigationRequest.DislikeComment -> dislikeComment(source(request.commentId), context, http)
                    else -> throw UnsupportedOperationException()
                }.requireValue()
                reply.action.requireConfirmation()
            }
        } catch (error: MediaException) {
            if (error.failure.kind == RequestFailure.AUTH) throw com.hhst.youtubelite.watch.domain.SignInRequiredException()
            throw error
        }
    }

    private fun Comment.author() = CommentAuthor(author.orEmpty(), "", authorPhoto.orEmpty())

    private fun Comment.textRuns() = runs.map { run ->
        val link = when {
            run.videoId?.matches(Regex("[A-Za-z0-9_-]{11}")) == true -> CommentLink.Video(run.videoId.orEmpty(), run.seconds?.toLong()?.takeIf { it >= 0 })
            run.channelId?.matches(Regex("UC[A-Za-z0-9_-]{22}")) == true -> CommentLink.Channel(run.channelId.orEmpty())
            else -> run.url?.let(::safeCommentUrl)?.let(CommentLink::External)
        }
        CommentTextRun(run.text, link, run.bold, run.italic, run.strikethrough)
    }
}
