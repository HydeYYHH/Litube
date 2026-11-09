package com.hhst.youtubelite.watch.chat

/** One rendered chat line; paid/membership kinds keep their text like ordinary messages. */
data class LiveChatMessage(val id: String, val author: String, val text: String, val photo: String, val authorId: String? = null)

sealed interface LiveChatContent {
    data object Loading : LiveChatContent
    data class Ready(val messages: List<LiveChatMessage>) : LiveChatContent
    data object Failed : LiveChatContent
}

data class LiveChatUiState(
    val content: LiveChatContent = LiveChatContent.Loading,
    val draft: String = "",
    val sending: Boolean = false,
    val signedIn: Boolean = true,
    val canSend: Boolean = true,
    /** Following an ended stream's replay rather than the live continuation. */
    val replay: Boolean = false,
    /** The stream (or its replay) is over: messages stay, posting stops. */
    val ended: Boolean = false,
    val postingRestriction: String? = null,
)

sealed interface LiveChatAction {
    data class DraftChanged(val text: String) : LiveChatAction
    data object Send : LiveChatAction
    data object Retry : LiveChatAction
    data object SignIn : LiveChatAction
}
