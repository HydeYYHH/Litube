package com.hhst.youtubelite.data.media

import com.hhst.youtubelite.watch.domain.SignInRequiredException
import com.hhst.youtubelite.watch.domain.VideoActions
import com.hhst.youtubelite.watch.domain.VideoRating
import com.liskovsoft.youtubeapi.videoinfo.V2.ReadResult
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestFailure
import com.liskovsoft.youtubeapi.videoinfo.V2.VideoVote
import com.liskovsoft.youtubeapi.videoinfo.V2.loadChannel
import com.liskovsoft.youtubeapi.videoinfo.V2.loadWatch
import com.liskovsoft.youtubeapi.videoinfo.V2.subscribeChannel
import com.liskovsoft.youtubeapi.videoinfo.V2.voteVideo

class MediaVideoActions(private val client: MediaClient, private val onConfirmed: () -> Unit = {}) : VideoActions {
    override suspend fun rate(videoId: String, rating: VideoRating, previous: VideoRating) = authorized {
        val vote = when (rating) {
            VideoRating.Like -> VideoVote.LIKE
            VideoRating.Dislike -> VideoVote.DISLIKE
            VideoRating.None -> if (previous == VideoRating.Dislike) VideoVote.UNDISLIKE else VideoVote.UNLIKE
        }
        val expected = when (rating) {
            VideoRating.Like -> "LIKE"
            VideoRating.Dislike -> "DISLIKE"
            VideoRating.None -> "INDIFFERENT"
        }
        client.desiredStateWrites.setState("video:$videoId", "rating", expected,
            write = { context, http -> DesiredStateAttempt(voteVideo(videoId, vote, context, http).requireValue(), Unit) },
            observe = { context, http ->
                val result = loadWatch(videoId, context, http = http)
                if (result.ok) ReadResult(DesiredStateRead(result.value?.rating?.takeIf { it in RATINGS }, Unit))
                else ReadResult(error = result.error)
            }, onConfirmed = { onConfirmed() })
    }

    override suspend fun subscribe(channelId: String, subscribed: Boolean) = authorized {
        client.desiredStateWrites.setState("channel:$channelId", "subscribed", subscribed.toString(),
            write = { context, http -> DesiredStateAttempt(subscribeChannel(channelId, subscribed, context, http = http).requireValue(), Unit) },
            observe = { context, http ->
                val result = loadChannel(channelId, context, http = http)
                if (result.ok) ReadResult(DesiredStateRead(result.value?.subscribed?.toString(), Unit))
                else ReadResult(error = result.error)
            }, onConfirmed = { onConfirmed() })
    }

    private suspend fun <T> authorized(block: suspend () -> T): T = try {
        block()
    } catch (error: MediaException) {
        if (error.failure.kind == RequestFailure.AUTH) throw SignInRequiredException()
        throw error
    }

    private companion object { val RATINGS = setOf("LIKE", "DISLIKE", "INDIFFERENT") }
}
