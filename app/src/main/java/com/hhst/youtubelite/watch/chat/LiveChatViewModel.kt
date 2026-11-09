package com.hhst.youtubelite.watch.chat

import com.hhst.youtubelite.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hhst.youtubelite.data.media.MediaClient
import com.hhst.youtubelite.data.media.MediaException
import com.hhst.youtubelite.data.media.requireValue
import com.hhst.youtubelite.data.media.requireConfirmation
import com.hhst.youtubelite.watch.domain.SignInRequiredException
import com.liskovsoft.youtubeapi.videoinfo.V2.ChatPoller
import com.liskovsoft.youtubeapi.videoinfo.V2.ChatPage
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestFailure
import com.liskovsoft.youtubeapi.videoinfo.V2.sendChatMessage
import com.liskovsoft.youtubeapi.videoinfo.V2.loadChat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.hhst.youtubelite.core.AppNotice

/** Chat transport; the fixture seam passes null and renders a static empty state. */
interface LiveChatGateway {
    /** Polls chat pages, releasing the MediaClient worker between requests. */
    suspend fun listen(key: String, onPage: (ChatPage) -> Unit)
    /**
     * Replays an ended stream's chat at the player position. Defaults to the
     * live loop so a seam that only models live chat stays valid.
     */
    suspend fun replay(key: String, positionMs: () -> Long, onPage: (ChatPage) -> Unit) = listen(key, onPage)
    suspend fun send(continuation: String, key: String, text: String)
}

class ChatPostingRestrictedException(val reason: String) : Exception("chat_posting_restricted")

class MediaLiveChatGateway(
    private val client: MediaClient,
    private val chatSessions: ChatWebSessionStore? = null,
) : LiveChatGateway {
    override suspend fun listen(key: String, onPage: (ChatPage) -> Unit) {
        var generation: Long? = null
        ChatPoller(key).use { poller ->
            while (true) {
                val page = client.read { context, http ->
                    if (generation != null && generation != context.generation) throw CancellationException("Session changed")
                    generation = context.generation
                    poller.read(context, http).requireValue()
                }
                onPage(page)
                if (page.nextKey == null) break
                kotlinx.coroutines.delay(page.waitMs)
            }
        }
    }

    override suspend fun replay(key: String, positionMs: () -> Long, onPage: (ChatPage) -> Unit) {
        var generation: Long? = null
        com.liskovsoft.youtubeapi.videoinfo.V2.ChatReplay(key).use { replay ->
            while (true) {
                val page = client.read { context, http ->
                    if (generation != null && generation != context.generation) throw CancellationException("Session changed")
                    generation = context.generation
                    replay.read(positionMs(), context, http).requireValue()
                }
                onPage(page)
                if (page.nextKey == null) break
                kotlinx.coroutines.delay(REPLAY_POLL_MS)
            }
        }
    }

    override suspend fun send(continuation: String, key: String, text: String) {
        var usedWebSession = false
        try {
            client.write { context, http ->
                if (!context.signedIn) throw SignInRequiredException()
                val web = chatSessions?.context(context) ?: throw ChatAuthorizationRequiredException()
                usedWebSession = true
                val permission = loadChat(continuation, web, http).requireValue()
                permission.postingRestriction?.takeIf { it.isNotBlank() }?.let { throw ChatPostingRestrictedException(it) }
                sendChatMessage(permission.sendParams ?: key, text, web, http).requireValue().requireConfirmation()
            }
        } catch (error: MediaException) {
            if (error.failure.kind == RequestFailure.AUTH) {
                if (error.failure.code == "session_changed") throw CancellationException("Session changed")
                if (!usedWebSession) throw SignInRequiredException()
                throw ChatAuthorizationRequiredException()
            }
            throw error
        }
    }

    private companion object {
        const val REPLAY_POLL_MS = 2_000L
    }
}

class LiveChatViewModel(
    private val gateway: LiveChatGateway?,
    signedIn: Boolean = true,
) : ViewModel() {
    private val _uiState = MutableStateFlow(LiveChatUiState(signedIn = gateway == null || signedIn, canSend = gateway == null))
    val uiState: StateFlow<LiveChatUiState> = _uiState.asStateFlow()
    private val notices = Channel<AppNotice>(Channel.BUFFERED)
    val actionNotices = notices.receiveAsFlow()
    private val authorizations = Channel<Long>(Channel.BUFFERED)
    val authorizationRequests = authorizations.receiveAsFlow()
    fun authorizationIsCurrent(request: Long): Boolean = request == epoch && key != null && !replay && _uiState.value.signedIn

    private var key: String? = null
    private var listenJob: Job? = null
    private var sendJob: Job? = null
    private var displayedKey: String? = null
    private var generation: Long? = null
    private var epoch = 0L
    private var sendParams: String? = null
    private var messageParams: String? = null
    private var displayedMessageParams: String? = null
    private var replay = false
    private var displayedReplay = false
    private var position: () -> Long = { 0L }

    fun updateSession(signedIn: Boolean, generation: Long) {
        if (this.generation == generation && _uiState.value.signedIn == signedIn) return
        val reopen = key
        val reopenReplay = replay
        val reopenPosition = position
        val reopenParams = messageParams
        close()
        this.generation = generation
        displayedKey = null
        sendParams = null
        _uiState.value = LiveChatUiState(signedIn = signedIn, canSend = gateway == null)
        reopen?.let { open(it, reopenReplay, reopenPosition, reopenParams) }
    }

    /** Opens the stream for a chat continuation key; repeated opens with the same key are free. */
    fun open(key: String, messageParams: String? = null) = open(key, replay = false, position = { 0L }, messageParams = messageParams)

    /**
     * Follows an ended stream's replay at the player position. The panel keeps
     * the same layout; posting is disabled because the stream is over.
     */
    fun openReplay(key: String, position: () -> Long) = open(key, replay = true, position = position, messageParams = null)

    private fun open(key: String, replay: Boolean, position: () -> Long, messageParams: String?) {
        if (this.key == key && this.replay == replay && this.messageParams == messageParams && listenJob?.isActive == true) return
        close()
        this.key = key
        this.replay = replay
        this.position = position
        this.messageParams = messageParams
        if (displayedKey != key || (displayedReplay == replay && displayedMessageParams != messageParams)) {
            displayedKey = key
            displayedMessageParams = messageParams
            displayedReplay = replay
            sendParams = null
            _uiState.update {
                LiveChatUiState(signedIn = it.signedIn, canSend = gateway == null, replay = replay)
            }
        } else if (displayedReplay != replay) {
            // Same chat, live ended and the replay took over: keep the transcript.
            displayedReplay = replay
            displayedMessageParams = messageParams
            sendParams = null
            _uiState.update { it.copy(replay = replay, ended = false, canSend = false) }
        }
        val current = epoch
        val gateway = gateway ?: run {
            _uiState.update { it.copy(content = LiveChatContent.Ready(emptyList())) }
            return
        }
        listenJob = viewModelScope.launch {
            _uiState.update { state ->
                state.copy(
                    content = if (state.content is LiveChatContent.Ready) state.content else LiveChatContent.Loading,
                    ended = false,
                )
            }
            try {
                if (replay) {
                    gateway.replay(key, position) { page ->
                        viewModelScope.launch { if (current == epoch) apply(page) }
                    }
                } else {
                    gateway.listen(key) { page ->
                        viewModelScope.launch { if (current == epoch) apply(page) }
                    }
                }
                if (current == epoch) {
                    // The continuation stopped: the stream or its replay is over.
                    _uiState.update { state ->
                        state.copy(
                            content = if (state.content == LiveChatContent.Loading) LiveChatContent.Ready(emptyList()) else state.content,
                            ended = true, sending = false, canSend = false,
                        )
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (current == epoch) _uiState.update { it.copy(content = LiveChatContent.Failed, canSend = false) }
            }
        }
    }

    fun close() {
        epoch++
        listenJob?.cancel()
        sendJob?.cancel()
        sendJob = null
        _uiState.update { it.copy(sending = false, canSend = gateway == null) }
        listenJob = null
        key = null
        sendParams = null
        messageParams = null
        replay = false
        position = { 0L }
    }

    fun onAction(action: LiveChatAction) {
        when (action) {
            is LiveChatAction.DraftChanged ->
                _uiState.update { it.copy(draft = action.text.take(MAX_MESSAGE_LENGTH + 1)) }
            LiveChatAction.Send -> send()
            LiveChatAction.Retry -> key?.let { open(it, replay, position, messageParams) }
            LiveChatAction.SignIn -> notices.trySend(AppNotice.SignIn)
        }
    }

    private fun apply(page: ChatPage) {
        // TV polling pages do not contain an input renderer. Their absence says
        // nothing about posting rights; address the known broadcast directly.
        if (!replay) (page.sendParams?.takeIf { it.isNotBlank() } ?: messageParams)?.let { sendParams = it }
        _uiState.update { state ->
            val current = (state.content as? LiveChatContent.Ready)?.messages.orEmpty()
            val messages = current.associateByTo(linkedMapOf()) { it.id }
            val actions = page.actions.ifEmpty {
                page.messages.map { com.liskovsoft.youtubeapi.videoinfo.V2.ChatAction.Add(it) } +
                    page.removals.map { com.liskovsoft.youtubeapi.videoinfo.V2.ChatAction.Remove(it) }
            }
            for (action in actions) {
                when (action) {
                    is com.liskovsoft.youtubeapi.videoinfo.V2.ChatAction.Add -> {
                        val message = action.message
                        val id = message.id ?: continue
                        val text = message.text?.takeIf { it.isNotBlank() } ?: continue
                        if (id !in messages) messages[id] = LiveChatMessage(id, message.author.orEmpty(),
                            text, message.photo.orEmpty(), message.authorId)
                    }
                    is com.liskovsoft.youtubeapi.videoinfo.V2.ChatAction.Remove -> {
                        action.removal.messageId?.let(messages::remove)
                        action.removal.authorId?.let { author -> messages.entries.removeAll { it.value.authorId == author } }
                    }
                }
            }
            state.copy(content = LiveChatContent.Ready(messages.values.toList().takeLast(MAX_MESSAGES)),
                canSend = sendParams != null && !replay && !state.ended)
        }
    }

    private fun send() {
        val state = _uiState.value
        val text = state.draft.trim()
        if (text.isEmpty() || state.sending || state.ended || state.replay) return
        val continuation = key ?: return
        if (!state.signedIn) {
            notices.trySend(AppNotice.SignIn)
            return
        }
        if (!state.canSend) return
        val params = sendParams ?: return
        if (text.length > MAX_MESSAGE_LENGTH) {
            notices.trySend(AppNotice.LocalizedMessage(R.string.chat_message_character_limit, listOf(MAX_MESSAGE_LENGTH)))
            return
        }
        val gateway = gateway ?: return
        val current = epoch
        _uiState.update { it.copy(sending = true) }
        sendJob = viewModelScope.launch {
            try {
                gateway.send(continuation, params, text)
                if (current == epoch) _uiState.update {
                    it.copy(sending = false, draft = if (it.draft == state.draft) "" else it.draft, postingRestriction = null)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: ChatPostingRestrictedException) {
                if (current == epoch) {
                    _uiState.update { it.copy(sending = false, postingRestriction = error.reason) }
                    notices.trySend(AppNotice.Message(error.reason))
                }
            } catch (error: ChatAuthorizationRequiredException) {
                if (current == epoch) {
                    _uiState.update { it.copy(sending = false) }
                    authorizations.trySend(current)
                }
            } catch (error: SignInRequiredException) {
                if (current == epoch) {
                    _uiState.update { it.copy(sending = false, signedIn = false) }
                    notices.trySend(AppNotice.SignIn)
                }
            } catch (error: Exception) {
                if (current == epoch) {
                    _uiState.update { it.copy(sending = false) }
                    notices.trySend(com.hhst.youtubelite.diagnostics.writeFailureNotice(
                        "send_live_chat", error, message = when ((error as? MediaException)?.failure?.code) {
                            "chat_posting_restricted" -> "This stream does not allow this message. Check its chat restrictions."
                            "chat_rate_limited" -> "YouTube is limiting messages. Wait before sending again."
                            "chat_message_rejected" -> (error as MediaException).failure.detail ?: "YouTube could not send this message. Your draft has been kept."
                            else -> "Couldn't confirm that your message was sent. Your draft has been kept."
                        }))
                }
            }
        }
    }

    override fun onCleared() = close()

    companion object {
        const val MAX_MESSAGES = 200
        const val MAX_MESSAGE_LENGTH = 200
    }
}
