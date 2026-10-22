package com.hhst.youtubelite.watch.domain

enum class VideoRating { None, Like, Dislike }
class SignInRequiredException : java.io.IOException("sign_in_required")
class UnconfirmedActionException : java.io.IOException("action_unconfirmed")

interface VideoActions {
    suspend fun rate(videoId: String, rating: VideoRating, previous: VideoRating)
    suspend fun subscribe(channelId: String, subscribed: Boolean)
}

data class VoteCounts(val likes: Long?, val dislikes: Long?)
fun interface VoteCountRepository { suspend fun counts(videoId: String): VoteCounts }

sealed interface WatchNotice {
    data object SignIn : WatchNotice
    data class Message(val text: String) : WatchNotice
}
