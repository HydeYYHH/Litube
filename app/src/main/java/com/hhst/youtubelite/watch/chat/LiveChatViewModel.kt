package com.hhst.youtubelite.watch.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hhst.youtubelite.data.media.MediaClient
import com.hhst.youtubelite.data.media.MediaException
import com.hhst.youtubelite.data.media.requireValue
import com.hhst.youtubelite.watch.domain.SignInRequiredException
import com.hhst.youtubelite.watch.domain.UnconfirmedActionException
import com.liskovsoft.youtubeapi.videoinfo.V2.ChatConnection
import com.liskovsoft.youtubeapi.videoinfo.V2.ChatPage
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestFailure
import com.liskovsoft.youtubeapi.videoinfo.V2.sendChatMessage
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
    /** Blocking poll loop over the chat continuation; runs on a MediaClient worker. */
    suspend fun listen(key: String, onPage: (ChatPage) -> Unit)
    suspend fun send(key: String, text: String)
}

class MediaLiveChatGateway(private val client: MediaClient) : LiveChatGateway {
    override suspend fun listen(key: String, onPage: (ChatPage) -> Unit) {
        client.read<Unit> { context, http ->
            val result = ChatConnection(context, http).listen(key, onPage)
            if (!result.ok && result.error?.kind != RequestFailure.CANCELLED) {
                throw MediaException(result.error ?: com.liskovsoft.youtubeapi.videoinfo.V2.RequestError(RequestFailure.TRANSPORT, "chat_ended"))
            }
        }
    }

    override suspend fun send(key: String, text: String) {
        try {
            client.read { context, http ->
                if (!context.signedIn) throw SignInRequiredException()
                if (!sendChatMessage(key, text, context, http).requireValue().confirmed) throw UnconfirmedActionException()
            }
        } catch (error: MediaException) {
            if (error.failure.kind == RequestFailure.AUTH) throw SignInRequiredException()
            throw error
        }
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

    private var key: String? = null
    private var listenJob: Job? = null
    private var sendJob: Job? = null
    private var displayedKey: String? = null
    private var generation: Long? = null
    private var epoch = 0L
    private var sendParams: String? = null

    fun updateSession(signedIn: Boolean, generation: Long) {
        if (this.generation == generation && _uiState.value.signedIn == signedIn) return
        val reopen = key
        close()
        this.generation = generation
        displayedKey = null
        sendParams = null
        _uiState.value = LiveChatUiState(signedIn = signedIn, canSend = gateway == null)
        reopen?.let(::open)
    }

    /** Opens the stream for a chat continuation key; repeated opens with the same key are free. */
    fun open(key: String) {
        if (this.key == key && listenJob?.isActive == true) return
        close()
        this.key = key
        if (displayedKey != key) {
            displayedKey = key
            sendParams = null
            _uiState.update { LiveChatUiState(signedIn = it.signedIn, canSend = gateway == null) }
        }
        val current = epoch
        val gateway = gateway ?: run {
            _uiState.update { it.copy(content = LiveChatContent.Ready(emptyList())) }
            return
        }
        listenJob = viewModelScope.launch {
            _uiState.update { it.copy(content = LiveChatContent.Loading) }
            try {
                gateway.listen(key) { page ->
                    viewModelScope.launch { if (current == epoch) apply(page) }
                }
                if (current == epoch && _uiState.value.content == LiveChatContent.Loading)
                    _uiState.update { it.copy(content = LiveChatContent.Ready(emptyList())) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (current == epoch) _uiState.update { it.copy(content = LiveChatContent.Failed) }
            }
        }
    }

    fun close() {
        epoch++
        listenJob?.cancel()
        sendJob?.cancel()
        sendJob = null
        _uiState.update { it.copy(sending = false) }
        listenJob = null
        key = null
    }

    fun onAction(action: LiveChatAction) {
        when (action) {
            is LiveChatAction.DraftChanged ->
                _uiState.update { it.copy(draft = action.text.take(MAX_MESSAGE_LENGTH + 1)) }
            LiveChatAction.Send -> send()
            LiveChatAction.Retry -> key?.let { listenJob?.cancel(); listenJob = null; open(it) }
            LiveChatAction.SignIn -> notices.trySend(AppNotice.SignIn)
        }
    }

    private fun apply(page: ChatPage) {
        page.sendParams?.takeIf { it.isNotBlank() }?.let { sendParams = it }
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
            state.copy(content = LiveChatContent.Ready(messages.values.toList().takeLast(MAX_MESSAGES)), canSend = sendParams != null)
        }
    }

    private fun send() {
        val state = _uiState.value
        val text = state.draft.trim()
        if (text.isEmpty() || state.sending) return
        key ?: return
        if (!state.signedIn) {
            notices.trySend(AppNotice.SignIn)
            return
        }
        val params = sendParams ?: return
        if (text.length > MAX_MESSAGE_LENGTH) {
            notices.trySend(AppNotice.Message("Messages are limited to $MAX_MESSAGE_LENGTH characters."))
            return
        }
        val gateway = gateway ?: return
        val current = epoch
        _uiState.update { it.copy(sending = true) }
        sendJob = viewModelScope.launch {
            try {
                gateway.send(params, text)
                if (current == epoch) _uiState.update {
                    it.copy(sending = false, draft = if (it.draft == state.draft) "" else it.draft)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: SignInRequiredException) {
                if (current == epoch) {
                    _uiState.update { it.copy(sending = false, signedIn = false) }
                    notices.trySend(AppNotice.SignIn)
                }
            } catch (error: Exception) {
                if (current == epoch) {
                    _uiState.update { it.copy(sending = false) }
                    notices.trySend(AppNotice.Message("Couldn't send. Try again."))
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
