package com.hhst.youtubelite.data.media

import com.hhst.youtubelite.comments.domain.adjustedCommentCount
import com.hhst.youtubelite.post.domain.ChannelPostsPage
import com.hhst.youtubelite.post.domain.PostActions
import com.hhst.youtubelite.post.domain.PostAuthor
import com.hhst.youtubelite.post.domain.PostAuthorizationRequiredException
import com.hhst.youtubelite.post.domain.PostDetail
import com.hhst.youtubelite.post.domain.PostIdentity
import com.hhst.youtubelite.post.domain.PostPollData
import com.hhst.youtubelite.post.domain.PostPollOption
import com.hhst.youtubelite.post.domain.PostRepository
import com.hhst.youtubelite.post.domain.PostSummary
import com.hhst.youtubelite.watch.domain.SignInRequiredException
import com.hhst.youtubelite.watch.domain.UnconfirmedActionException
import com.hhst.youtubelite.watch.domain.VideoRating
import com.liskovsoft.youtubeapi.videoinfo.V2.ActionState
import com.liskovsoft.youtubeapi.videoinfo.V2.PostCommand
import com.liskovsoft.youtubeapi.videoinfo.V2.PostItem
import com.liskovsoft.youtubeapi.videoinfo.V2.PostPoll
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestContext
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestFailure
import com.liskovsoft.youtubeapi.videoinfo.V2.ReadResult
import com.liskovsoft.youtubeapi.videoinfo.V2.loadChannel
import com.liskovsoft.youtubeapi.videoinfo.V2.loadChannelPosts
import com.liskovsoft.youtubeapi.videoinfo.V2.loadPost
import com.liskovsoft.youtubeapi.videoinfo.V2.performPostCommand
import com.liskovsoft.youtubeapi.videoinfo.V2.updatedPost
import kotlinx.coroutines.CancellationException

/** Parsing lives in MSC common; this repository only maps shared PostItem rows onto the app's post domain. */
class MediaPostRepository(private val client: MediaClient,
    private val webContext: (RequestContext) -> RequestContext? = { it.takeIf { it.webSession?.signedIn == true } },
) : PostRepository, PostActions {
    private class Entry(var post: PostItem? = null, var readSequence: Long = 0, var revision: Long = 0)
    private data class ReadTicket(val id: String, val entry: Entry, val sequence: Long,
        val revision: Long, val source: PostItem?)
    private val actionLock = Any()
    private val actionPosts = LinkedHashMap<String, Entry>(32, 0.75f, true)
    private var actionGeneration: Long? = null
    private var readSequence = 0L

    private fun beginRead(generation: Long, id: String): ReadTicket = synchronized(actionLock) {
        // A worker captured before a session change may reach this lock after a new worker.
        if (actionGeneration?.let { generation < it } == true)
            return@synchronized ReadTicket(id, Entry(), ++readSequence, 0, null)
        if (actionGeneration != generation) {
            actionPosts.clear()
            actionGeneration = generation
        }
        val entry = actionPosts.getOrPut(id) { Entry() }
        while (actionPosts.size > 32) actionPosts.remove(actionPosts.keys.first())
        ReadTicket(id, entry, ++readSequence, entry.revision, entry.post)
    }

    private fun rememberRead(ticket: ReadTicket, post: PostItem) = synchronized(actionLock) {
        val entry = actionPosts[ticket.id]
        if (entry === ticket.entry && entry.revision == ticket.revision && entry.readSequence <= ticket.sequence) {
            entry.post = post
            entry.readSequence = ticket.sequence
        }
    }

    private fun rememberMutation(ticket: ReadTicket, post: PostItem) = synchronized(actionLock) {
        val entry = actionPosts[ticket.id]
        // An evicted or previous-identity entry must never recreate itself after IO.
        if (entry === ticket.entry) {
            entry.post = post
            entry.revision++
        }
    }

    override suspend fun channelPosts(channelId: String): ChannelPostsPage = client.readIdempotent { context, http ->
        // Posts are public WEB content; the OAuth TV client exposes no Posts tab.
        val publicContext = context.anonymous()
        val resolved = resolveChannel(channelId, publicContext, http)
        val first = loadChannel(resolved, publicContext, http = http).requireValue()
        val tab = first.page.tabs.firstOrNull { it.title.equals("Posts", true) || it.title.equals("Community", true) }
            ?: return@readIdempotent ChannelPostsPage(emptyList())
        val page = loadChannelPosts(tab.browseId ?: resolved, publicContext, params = tab.params, http = http).requireValue()
        ChannelPostsPage(page.posts.map { it.summary() }, page.nextKey)
    }

    override suspend fun channelPostsNext(channelId: String, cursor: String): ChannelPostsPage = client.readIdempotent { context, http ->
        val page = loadChannelPosts(channelId, context.anonymous(), continuation = cursor, http = http).requireValue()
        ChannelPostsPage(page.posts.map { it.summary() }, page.nextKey)
    }

    override suspend fun post(identity: PostIdentity): PostDetail? = client.readIdempotent { context, http ->
        val ticket = beginRead(context.generation, identity.postId)
        val page = loadPost(identity.postId, webContext(context) ?: context.anonymous(), http).requireValue()
        val post = page.posts.firstOrNull { it.postId == identity.postId } ?: return@readIdempotent null
        rememberRead(ticket, post)
        post.detail(identity.channelId)
    }

    override suspend fun rate(identity: PostIdentity, rating: VideoRating, previous: VideoRating): PostDetail {
        val status = when (rating) {
            VideoRating.Like -> "LIKE"
            VideoRating.Dislike -> "DISLIKE"
            VideoRating.None -> "INDIFFERENT"
        }
        return mutate(identity, "rating", status, state = {
            it.voteStatus?.takeIf { value -> value in setOf("LIKE", "DISLIKE", "INDIFFERENT") }
        }) { post ->
            val command = when (rating) {
                VideoRating.Like -> post.like
                VideoRating.Dislike -> post.dislike
                VideoRating.None -> when (post.voteStatus) {
                    "DISLIKE" -> post.undislike
                    "LIKE" -> post.unlike
                    else -> if (previous == VideoRating.Dislike) post.undislike else post.unlike
                }
            }
            val delta = (if (rating == VideoRating.Like) 1 else 0) - (if (post.voteStatus == "LIKE") 1 else 0)
            command to post.copy(voteStatus = status, likes = adjustedCommentCount(post.likes.orEmpty(), delta))
        }
    }

    override suspend fun vote(identity: PostIdentity, option: Int, selected: Boolean): PostDetail =
        mutate(identity, "poll", if (selected) "option:$option" else "none", state = { post ->
            post.poll?.takeIf { it.selectionKnown }?.let { poll ->
                val selections = poll.choices.indices.filter { poll.choices[it].selected }
                when (selections.size) { 0 -> "none"; 1 -> "option:${selections.single()}"; else -> null }
            }
        }) { post ->
            val poll = post.poll ?: throw UnsupportedOperationException("missing_post_poll")
            val choice = poll.choices.getOrNull(option) ?: throw IllegalArgumentException("invalid_poll_option")
            val command = if (selected) choice.select else poll.choices.firstOrNull { it.selected }?.deselect ?: choice.deselect
            val updated = poll.copy(voted = selected, selectionKnown = true, total = command?.pollTotal ?: poll.total,
                choices = poll.choices.mapIndexed { index, value -> value.copy(selected = selected && index == option) })
            command to post.copy(poll = updated)
        }

    private suspend fun mutate(identity: PostIdentity, stateName: String, expected: String, state: (PostItem) -> String?,
        select: (PostItem) -> Pair<PostCommand?, PostItem>): PostDetail = try {
        var ticket: ReadTicket? = null
        var fresh: PostItem? = null
        var first = true
        val confirmed = client.desiredStateWrites.setState<PostItem?>("post:${identity.postId}", stateName, expected,
            retryUnknownState = false,
            write = { native, http ->
                if (!native.signedIn) throw SignInRequiredException()
                val web = webContext(native) ?: throw PostAuthorizationRequiredException()
                val source = if (first) {
                    first = false
                    beginRead(native.generation, identity.postId).also { ticket = it }.source
                        ?: loadPost(identity.postId, web, http).requireValue().posts.firstOrNull { it.postId == identity.postId }
                } else fresh // A retry can only consume capabilities from its preceding fresh read.
                val post = source ?: throw UnconfirmedActionException()
                val (command, updated) = select(post)
                if (command == null && post.authorizationRequired) throw PostAuthorizationRequiredException()
                val reply = performPostCommand(command, web, http).requireValue()
                // A post-rating update cannot acknowledge a poll feedback command.
                // Ambiguous poll replies are verified against a fresh selection snapshot.
                val acknowledged = if (stateName == "poll" && !reply.confirmed) null else updatedPost(reply, updated)
                DesiredStateAttempt(if (acknowledged != null) reply.copy(state = ActionState.CONFIRMED) else reply,
                    acknowledged ?: updated)
            },
            observe = { native, http ->
                val web = webContext(native) ?: throw PostAuthorizationRequiredException()
                val response = loadPost(identity.postId, web, http)
                if (!response.ok) ReadResult(error = response.error) else {
                    val observed = response.value!!.posts.firstOrNull { it.postId == identity.postId }
                    ReadResult(DesiredStateRead(observed?.let(state), observed))
                }
            },
            onObserved = { fresh = it },
            onConfirmed = { value -> rememberMutation(requireNotNull(ticket), requireNotNull(value)) })
        requireNotNull(confirmed).detail(identity.channelId)
    } catch (error: MediaException) {
        if (error.failure.kind == RequestFailure.AUTH) {
            if (error.failure.code == "session_changed") throw CancellationException("Session changed")
            throw PostAuthorizationRequiredException()
        }
        throw error
    }
}

private fun PostItem.detail(channelId: String): PostDetail {
    val authorId = authorId ?: channelId
    val rating = when (voteStatus) {
        "LIKE" -> VideoRating.Like
        "DISLIKE" -> VideoRating.Dislike
        else -> VideoRating.None
    }
    return PostDetail(PostIdentity(authorId, postId), PostAuthor(authorId, author.orEmpty(), authorAvatar.orEmpty()),
        published.orEmpty(), text.orEmpty(), images, likes.orEmpty(), replies.orEmpty(), rating, poll?.data())
}

private fun PostPoll.data() = PostPollData(choices.map {
    PostPollOption(it.text, it.image, if (voted && it.selected) it.selectedPercentage ?: it.percentage else it.percentage, it.selected)
}, total.orEmpty(), voted)

private fun PostItem.summary() = PostSummary(PostIdentity(authorId.orEmpty(), postId),
    PostAuthor(authorId.orEmpty(), author.orEmpty(), authorAvatar.orEmpty()),
    published.orEmpty(), text.orEmpty(), images, likes.orEmpty(), replies.orEmpty(), poll?.data())
