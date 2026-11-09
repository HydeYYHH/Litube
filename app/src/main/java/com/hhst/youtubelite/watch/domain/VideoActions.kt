package com.hhst.youtubelite.watch.domain

enum class VideoRating { None, Like, Dislike }

// Domain control-flow exceptions, not transport: they must not be caught by
// transport-level IOException handling (read normalization, retry, recovery).
class SignInRequiredException : RuntimeException("sign_in_required")
class UnconfirmedActionException(
    val httpStatus: Int? = null,
    val detail: Map<String, String> = emptyMap(),
) : RuntimeException("action_unconfirmed")

interface VideoActions {
    suspend fun rate(videoId: String, rating: VideoRating, previous: VideoRating)
    suspend fun subscribe(channelId: String, subscribed: Boolean)
}

data class VoteCounts(val likes: Long?, val dislikes: Long?)
fun interface VoteCountRepository { suspend fun counts(videoId: String): VoteCounts }
