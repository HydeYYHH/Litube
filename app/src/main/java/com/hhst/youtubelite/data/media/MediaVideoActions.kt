package com.hhst.youtubelite.data.media

import com.hhst.youtubelite.watch.domain.*
import com.liskovsoft.youtubeapi.videoinfo.V2.*

class MediaVideoActions(private val client: MediaClient) : VideoActions {
    override suspend fun rate(videoId: String, rating: VideoRating, previous: VideoRating) {
        action { context, http ->
            val vote = when (rating) {
                VideoRating.Like -> VideoVote.LIKE
                VideoRating.Dislike -> VideoVote.DISLIKE
                VideoRating.None -> if (previous == VideoRating.Dislike) VideoVote.UNDISLIKE else VideoVote.UNLIKE
            }
            voteVideo(videoId, vote, context, http).requireValue()
        }
    }

    override suspend fun subscribe(channelId: String, subscribed: Boolean) {
        action { context, http -> subscribeChannel(channelId, subscribed, context, http = http).requireValue() }
    }

    private suspend fun action(block: (RequestContext, HttpClient) -> ActionReply) {
        try {
            client.read { context, http ->
                if (!context.signedIn) throw SignInRequiredException()
                if (!block(context, http).confirmed) throw UnconfirmedActionException()
            }
        } catch (error: MediaException) {
            if (error.failure.kind == RequestFailure.AUTH) throw SignInRequiredException()
            throw error
        }
    }
}
