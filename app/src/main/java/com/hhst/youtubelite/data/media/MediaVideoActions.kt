package com.hhst.youtubelite.data.media

import com.hhst.youtubelite.watch.domain.*
import com.liskovsoft.youtubeapi.videoinfo.V2.*

class MediaVideoActions(private val client: MediaClient, private val onConfirmed: () -> Unit = {}) : VideoActions {
    override suspend fun rate(videoId: String, rating: VideoRating, previous: VideoRating) {
        action { context, http ->
            val vote = when (rating) {
                VideoRating.Like -> VideoVote.LIKE
                VideoRating.Dislike -> VideoVote.DISLIKE
                VideoRating.None -> if (previous == VideoRating.Dislike) VideoVote.UNDISLIKE else VideoVote.UNLIKE
            }
            val reply = voteVideo(videoId, vote, context, http).requireValue()
            if (!reply.confirmed) {
                val observed = loadWatch(videoId, context, http = http)
                val expected = when (rating) {
                    VideoRating.Like -> "LIKE"
                    VideoRating.Dislike -> "DISLIKE"
                    VideoRating.None -> "INDIFFERENT"
                }
                if (observed.ok && observed.value?.rating == expected) return@action reply.copy(state = ActionState.CONFIRMED)
                throw unconfirmed(reply, mapOf("expected_rating" to expected,
                    "observed_rating" to (observed.value?.rating?.takeIf { it in setOf("LIKE", "DISLIKE", "INDIFFERENT") } ?: "unknown"),
                    "readback" to if (observed.ok) "completed" else "failed"))
            }
            reply
        }
    }

    override suspend fun subscribe(channelId: String, subscribed: Boolean) {
        action { context, http ->
            val reply = subscribeChannel(channelId, subscribed, context, http = http).requireValue()
            if (!reply.confirmed) {
                val observed = loadChannel(channelId, context, http = http)
                if (observed.ok && observed.value?.subscribed == subscribed) return@action reply.copy(state = ActionState.CONFIRMED)
                throw unconfirmed(reply, mapOf("expected_subscribed" to subscribed.toString(),
                    "observed_subscribed" to (observed.value?.subscribed?.toString() ?: "unknown"),
                    "readback" to if (observed.ok) "completed" else "failed"))
            }
            reply
        }
    }

    private suspend fun action(block: (RequestContext, HttpClient) -> ActionReply) {
        try {
            client.read { context, http ->
                if (!context.signedIn) throw SignInRequiredException()
                val reply = block(context, http)
                if (!reply.confirmed) throw unconfirmed(reply)
            }
            onConfirmed()
        } catch (error: MediaException) {
            if (error.failure.kind == RequestFailure.AUTH) throw SignInRequiredException()
            throw error
        }
    }

    private fun unconfirmed(reply: ActionReply, detail: Map<String, String> = emptyMap()) =
        UnconfirmedActionException(reply.httpStatus, detail + mapOf("response_shape" to reply.responseShape.orEmpty()))
}
