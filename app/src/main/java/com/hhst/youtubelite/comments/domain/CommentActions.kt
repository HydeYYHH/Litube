package com.hhst.youtubelite.comments.domain

interface CommentActions {
    val supportedMenuActions: Set<CommentMenuAction> get() = CommentMenuAction.entries.toSet()
    suspend fun perform(request: CommentNavigationRequest): CommentMutation
}

/** Confirmed server entity, when the write response supplies one. Never requires a list reload. */
data class CommentMutation(val comment: CommentThread? = null)

internal fun adjustedCommentCount(value: String, delta: Int): String {
    if (delta == 0) return value
    val count = if (value.isBlank()) 0L else value.replace(",", "").toLongOrNull() ?: return value
    return java.text.NumberFormat.getIntegerInstance(java.util.Locale.US).format((count + delta).coerceAtLeast(0))
}
