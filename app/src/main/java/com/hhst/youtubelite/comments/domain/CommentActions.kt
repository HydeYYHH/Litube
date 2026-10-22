package com.hhst.youtubelite.comments.domain

interface CommentActions {
    suspend fun perform(request: CommentNavigationRequest)
}

sealed interface CommentNotice {
    data object SignIn : CommentNotice
    data class Message(val text: String) : CommentNotice
}
