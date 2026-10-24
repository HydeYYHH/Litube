package com.hhst.youtubelite.comments.domain

interface CommentActions {
    val supportedMenuActions: Set<CommentMenuAction> get() = CommentMenuAction.entries.toSet()
    suspend fun perform(request: CommentNavigationRequest)
}
