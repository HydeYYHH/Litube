package com.hhst.youtubelite.data.media

import com.hhst.youtubelite.comments.domain.*
import com.hhst.youtubelite.comments.domain.CommentSort as AppSort
import com.liskovsoft.youtubeapi.videoinfo.V2.*
import java.util.concurrent.ConcurrentHashMap

class MediaCommentsRepository(private val client: MediaClient) : CommentsRepository, CommentActions {
    private val actionComments = ConcurrentHashMap<String, Pair<Long, Comment>>()
    private val createKeys = ConcurrentHashMap<CommentTarget, Pair<Long, String>>()
    private var generation: Long? = null
    private val replyKeys = ConcurrentHashMap<String, String>()
    override suspend fun comments(target: CommentTarget, sort: AppSort, cursor: String?): CommentsPage = client.read { context, http ->
        if (generation != context.generation) {
            actionComments.clear(); createKeys.clear(); replyKeys.clear()
            generation = context.generation
        }
        var page = when {
            cursor != null -> loadCommentReplies(cursor, context, http).requireValue()
            target is CommentTarget.Video -> loadComments(target.id, context, http = http).requireValue()
            target is CommentTarget.Post -> {
                val root = MediaPostRepository.readPost(target.identity.postId, http)
                val key = commentKey(root.toString())
                if (key == null) CommentPage(emptyList(), null, null) else loadCommentReplies(key, context, http).requireValue()
            }
            else -> error("Unknown comment target")
        }
        if (cursor == null && sort == AppSort.Newest) {
            val option = page.sortOptions.firstOrNull { it.title?.contains("newest", true) == true }
                ?: throw MediaException(RequestError(RequestFailure.UNSUPPORTED, "comment_sort_unavailable"))
            if (!option.selected) page = loadCommentReplies(option.key, context, http).requireValue().copy(total = page.total)
        }
        page.createKey?.let { createKeys[target] = context.generation to it }
        CommentsPage(page.total.orEmpty(), page.comments.mapNotNull { comment ->
            val id = comment.id ?: return@mapNotNull null
            actionComments["$target:$id"] = context.generation to comment
            comment.replyKey?.let { replyKeys["$target:$id"] = it }
            CommentThread(id, comment.author(), comment.published.orEmpty(), comment.text.orEmpty(), comment.likeCount.orEmpty(),
                comment.replyCount?.filter(Char::isDigit)?.toIntOrNull() ?: 0,
                authorChannelId = comment.authorId.orEmpty(), ownedByViewer = !comment.deleteKey.isNullOrBlank(), replyKey = comment.replyKey)
        }, page.nextKey, turnedOff = page.turnedOff)
    }

    override suspend fun replies(target: CommentTarget, thread: CommentThread): List<CommentReply> {
        val key = thread.replyKey ?: replyKeys["$target:${thread.id}"] ?: return emptyList()
        return client.read { context, http ->
            val comments = mutableListOf<Comment>()
            var cursor: String? = key
            val visited = mutableSetOf<String>()
            while (cursor != null) {
                if (!visited.add(cursor) || comments.size >= 1000)
                    throw MediaException(RequestError(RequestFailure.UNSUPPORTED, "reply_limit"))
                val page = loadCommentReplies(cursor, context, http).requireValue()
                comments.addAll(page.comments)
                cursor = page.nextKey
            }
            comments.distinctBy { it.id }.mapNotNull { comment ->
                val id = comment.id ?: return@mapNotNull null
                actionComments["$target:$id"] = context.generation to comment
                CommentReply(id, comment.author(), comment.published.orEmpty(), comment.text.orEmpty(),
                    comment.likeCount.orEmpty(), comment.authorId.orEmpty(), !comment.deleteKey.isNullOrBlank())
            }
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
                    is CommentNavigationRequest.SubmitComment -> createComment(request.body, createKeys[request.target]?.takeIf { it.first == context.generation }?.second, context, http)
                    is CommentNavigationRequest.SubmitReply -> replyComment(request.body, source(request.threadId).replyCreateKey, context, http)
                    is CommentNavigationRequest.DeleteComment -> deleteComment(source(request.commentId), context, http)
                    is CommentNavigationRequest.LikeComment -> likeComment(source(request.commentId), context, http)
                    is CommentNavigationRequest.DislikeComment -> dislikeComment(source(request.commentId), context, http)
                    else -> throw UnsupportedOperationException()
                }.requireValue()
                if (!reply.action.confirmed) throw com.hhst.youtubelite.watch.domain.UnconfirmedActionException()
            }
        } catch (error: MediaException) {
            if (error.failure.kind == RequestFailure.AUTH) throw com.hhst.youtubelite.watch.domain.SignInRequiredException()
            throw error
        }
    }

    private fun Comment.author() = CommentAuthor(author.orEmpty(), "", authorPhoto.orEmpty())
}
