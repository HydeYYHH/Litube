package com.hhst.youtubelite.notifications.domain

data class NotificationItem(
    val id: String,
    val channelId: String,
    val author: String,
    val avatar: String,
    val text: String,
    val age: String,
    val artwork: String,
    val videoId: String? = null,
    val unread: Boolean = false,
)

data class NotificationsPage(val items: List<NotificationItem>)

sealed interface NotificationsContent {
    data object Loading : NotificationsContent
    data class SignedOut(val prompt: com.hhst.youtubelite.library.domain.SignInPrompt) : NotificationsContent
    data class Ready(val page: NotificationsPage) : NotificationsContent
    data object Empty : NotificationsContent
    data object Failed : NotificationsContent
}

data class NotificationsUiState(
    val signedIn: Boolean = false,
    val content: NotificationsContent = NotificationsContent.Loading,
)

sealed interface NotificationsNavigationRequest {
    data class Watch(val videoId: String) : NotificationsNavigationRequest
    data class Channel(val channelId: String) : NotificationsNavigationRequest
    data object SignIn : NotificationsNavigationRequest
}

sealed interface NotificationsAction {
    data object Retry : NotificationsAction
    data class OpenVideo(val videoId: String) : NotificationsAction
    data class OpenChannel(val channelId: String) : NotificationsAction
    data object SignIn : NotificationsAction
}
