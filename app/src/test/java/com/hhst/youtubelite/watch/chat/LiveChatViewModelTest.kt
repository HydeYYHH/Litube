package com.hhst.youtubelite.watch.chat

import com.liskovsoft.youtubeapi.videoinfo.V2.ChatAction
import com.liskovsoft.youtubeapi.videoinfo.V2.ChatMessage
import com.liskovsoft.youtubeapi.videoinfo.V2.ChatPage
import com.liskovsoft.youtubeapi.videoinfo.V2.ChatRemoval
import com.hhst.youtubelite.watch.domain.SignInRequiredException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import com.hhst.youtubelite.core.AppNotice

@OptIn(ExperimentalCoroutinesApi::class)
class LiveChatViewModelTest {
    @Before fun setup() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun cleanup() { Dispatchers.resetMain() }

    private class FakeGateway : LiveChatGateway {
        val pages = ArrayDeque<ChatPage>()
        var listenError: Exception? = null
        var sendError: Exception? = null
        val sent = mutableListOf<Pair<String, String>>()
        var listenCalls = 0
        var initialSendParams: String? = "send-params"
        var sendGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

        override suspend fun listen(key: String, onPage: (ChatPage) -> Unit) {
            listenCalls++
            listenError?.let { throw it }
            onPage(ChatPage(emptyList(), emptyList(), null, 1000, sendParams = initialSendParams))
            while (pages.isNotEmpty()) onPage(pages.removeFirst())
            awaitCancellation()
        }

        override suspend fun send(continuation: String, key: String, text: String) {
            sendError?.let { throw it }
            sendGate?.await()
            sent += key to text
        }
    }

    private fun message(id: String, text: String = "msg $id") =
        ChatMessage(id, "author-$id", text, null, null, "liveChatTextMessageRenderer")

    private fun page(vararg messages: ChatMessage, removals: List<ChatRemoval> = emptyList()) =
        ChatPage(messages.toList(), removals, null, 1000,
            messages.map { ChatAction.Add(it) } + removals.map { ChatAction.Remove(it) })

    @Test fun streamMessagesAppearDeduplicatedAndRemovalsApply() = runTest {
        val gateway = FakeGateway()
        gateway.pages += page(message("a"), message("b"))
        gateway.pages += page(message("a"), message("c"), removals = listOf(ChatRemoval("b", null)))
        val vm = LiveChatViewModel(gateway)
        vm.open("key")
        advanceUntilIdle()
        val content = vm.uiState.value.content
        assertTrue(content is LiveChatContent.Ready)
        assertEquals(listOf("a", "c"), (content as LiveChatContent.Ready).messages.map { it.id })
        vm.close()
    }

    @Test fun listenFailuresShowTheRetryableError() = runTest {
        val gateway = FakeGateway().apply { listenError = RuntimeException("offline") }
        val vm = LiveChatViewModel(gateway)
        vm.open("key")
        advanceUntilIdle()
        assertEquals(LiveChatContent.Failed, vm.uiState.value.content)
        gateway.listenError = null
        gateway.pages += page(message("a"))
        vm.onAction(LiveChatAction.Retry)
        advanceUntilIdle()
        assertEquals(2, gateway.listenCalls)
        assertTrue(vm.uiState.value.content is LiveChatContent.Ready)
        vm.close()
    }

    @Test fun sendingClearsTheDraftAndHitsTheGateway() = runTest {
        val gateway = FakeGateway()
        val vm = LiveChatViewModel(gateway, signedIn = true)
        vm.open("key")
        advanceUntilIdle()
        vm.onAction(LiveChatAction.DraftChanged("  hello chat  "))
        vm.onAction(LiveChatAction.Send)
        advanceUntilIdle()
        assertEquals(listOf("send-params" to "hello chat"), gateway.sent)
        assertEquals("", vm.uiState.value.draft)
        assertFalse(vm.uiState.value.sending)
        vm.close()
    }

    @Test fun signedOutSendsRouteToTheSignInNotice() = runTest {
        val gateway = FakeGateway()
        val vm = LiveChatViewModel(gateway, signedIn = false)
        vm.open("key")
        vm.onAction(LiveChatAction.DraftChanged("hello"))
        val notices = mutableListOf<AppNotice>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.actionNotices.collect { notices += it } }
        vm.onAction(LiveChatAction.Send)
        advanceUntilIdle()
        assertEquals(listOf(AppNotice.SignIn), notices)
        assertTrue(gateway.sent.isEmpty())
        vm.close()
    }

    @Test fun authFailuresFromTheGatewayAlsoRouteToSignIn() = runTest {
        val gateway = FakeGateway().apply { sendError = SignInRequiredException() }
        val vm = LiveChatViewModel(gateway, signedIn = true)
        vm.open("key")
        advanceUntilIdle()
        vm.onAction(LiveChatAction.DraftChanged("hello"))
        val notices = mutableListOf<AppNotice>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.actionNotices.collect { notices += it } }
        vm.onAction(LiveChatAction.Send)
        advanceUntilIdle()
        assertEquals(listOf(AppNotice.SignIn), notices)
        assertEquals("hello", vm.uiState.value.draft)
        vm.close()
    }

    @Test fun genericSendFailuresKeepTheDraftAndNotify() = runTest {
        val gateway = FakeGateway().apply { sendError = RuntimeException("offline") }
        val vm = LiveChatViewModel(gateway, signedIn = true)
        vm.open("key")
        advanceUntilIdle()
        vm.onAction(LiveChatAction.DraftChanged("hello"))
        vm.onAction(LiveChatAction.Send)
        advanceUntilIdle()
        assertEquals("hello", vm.uiState.value.draft)
        assertFalse(vm.uiState.value.sending)
        val notice = withTimeoutOrNull(1_000) { vm.actionNotices.first() }
        assertTrue(notice is AppNotice.Failure)
        vm.close()
    }

    @Test fun webAuthorizationIsLazyKeepsDraftAndNeverAutomaticallyPosts() = runTest {
        val gateway = FakeGateway().apply { sendError = ChatAuthorizationRequiredException() }
        val vm = LiveChatViewModel(gateway)
        val requests = mutableListOf<Long>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.authorizationRequests.collect { requests += it } }
        vm.open("key")
        advanceUntilIdle()
        assertTrue(requests.isEmpty())
        vm.onAction(LiveChatAction.DraftChanged("keep this draft"))
        advanceUntilIdle()
        assertTrue(requests.isEmpty())
        vm.onAction(LiveChatAction.Send)
        advanceUntilIdle()
        assertEquals(1, requests.size)
        assertTrue(vm.authorizationIsCurrent(requests.single()))
        assertEquals("keep this draft", vm.uiState.value.draft)
        assertTrue(vm.uiState.value.signedIn)
        assertFalse(vm.uiState.value.sending)
        gateway.sendError = null
        advanceUntilIdle()
        assertTrue(gateway.sent.isEmpty())
        vm.onAction(LiveChatAction.Send)
        advanceUntilIdle()
        assertEquals(listOf("send-params" to "keep this draft"), gateway.sent)
        vm.close()
        assertFalse(vm.authorizationIsCurrent(requests.single()))
    }

    @Test fun aRestrictedChatPreservesTheDraftAndExplainsTheRestriction() = runTest {
        val gateway = FakeGateway().apply { sendError = ChatPostingRestrictedException("Subscribers-only mode") }
        val vm = LiveChatViewModel(gateway)
        vm.open("chat")
        advanceUntilIdle()
        vm.onAction(LiveChatAction.DraftChanged("keep this"))
        vm.onAction(LiveChatAction.Send)
        advanceUntilIdle()
        assertEquals("Subscribers-only mode", vm.uiState.value.postingRestriction)
        assertEquals("keep this", vm.uiState.value.draft)
        assertEquals(AppNotice.Message("Subscribers-only mode"), vm.actionNotices.first())
        gateway.sendError = null
        vm.onAction(LiveChatAction.Send)
        advanceUntilIdle()
        assertNull(vm.uiState.value.postingRestriction)
        assertEquals("", vm.uiState.value.draft)
        vm.close()
    }

    @Test fun messagesAreCappedAtTheWindow() = runTest {
        val gateway = FakeGateway()
        gateway.pages += page(*Array(300) { message("m$it") })
        val vm = LiveChatViewModel(gateway)
        vm.open("key")
        advanceUntilIdle()
        val content = vm.uiState.value.content as LiveChatContent.Ready
        assertEquals(LiveChatViewModel.MAX_MESSAGES, content.messages.size)
        assertEquals("m100", content.messages.first().id)
        vm.close()
    }

    @Test fun fixtureGatewayRendersAnEmptyReadyState() = runTest {
        val vm = LiveChatViewModel(null)
        vm.open("key")
        advanceUntilIdle()
        assertEquals(LiveChatContent.Ready(emptyList()), vm.uiState.value.content)
        assertTrue(vm.uiState.value.signedIn)
    }

    @Test fun signingInRefreshesTheComposerWithoutRecreatingTheWatchPage() = runTest {
        val gateway = FakeGateway()
        val vm = LiveChatViewModel(gateway, signedIn = false)
        vm.open("key")
        advanceUntilIdle()
        vm.updateSession(true, 2)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.signedIn)
        vm.onAction(LiveChatAction.DraftChanged("hello"))
        vm.onAction(LiveChatAction.Send)
        advanceUntilIdle()
        assertEquals(listOf("send-params" to "hello"), gateway.sent)
        vm.close()
    }

    @Test fun aCompletedSendDoesNotEraseTextTypedWhileWaiting() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val gateway = FakeGateway().apply { sendGate = gate }
        val vm = LiveChatViewModel(gateway)
        vm.open("key")
        advanceUntilIdle()
        vm.onAction(LiveChatAction.DraftChanged("first"))
        vm.onAction(LiveChatAction.Send)
        advanceUntilIdle()
        vm.onAction(LiveChatAction.DraftChanged("next draft"))
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals("next draft", vm.uiState.value.draft)
        vm.close()
    }

    @Test fun anotherStreamCancelsTheOldSendAndClearsItsDraft() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val gateway = FakeGateway().apply { sendGate = gate }
        val vm = LiveChatViewModel(gateway)
        vm.open("first")
        advanceUntilIdle()
        vm.onAction(LiveChatAction.DraftChanged("old draft"))
        vm.onAction(LiveChatAction.Send)
        advanceUntilIdle()
        vm.open("second")
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(gateway.sent.isEmpty())
        assertEquals("", vm.uiState.value.draft)
        assertFalse(vm.uiState.value.sending)
        vm.close()
    }

    @Test fun moderationActionsApplyInOrderIncludingAuthorWideRemovals() = runTest {
        val gateway = FakeGateway()
        val first = message("first").copy(authorId = "channel")
        val removed = message("removed")
        gateway.pages += page(first)
        gateway.pages += ChatPage(listOf(removed), listOf(ChatRemoval("removed", null), ChatRemoval(null, "channel")),
            null, 1000, listOf(ChatAction.Add(removed), ChatAction.Remove(ChatRemoval("removed", null)),
                ChatAction.Remove(ChatRemoval(null, "channel"))))
        val vm = LiveChatViewModel(gateway)
        vm.open("key")
        advanceUntilIdle()
        assertTrue((vm.uiState.value.content as LiveChatContent.Ready).messages.isEmpty())
        vm.close()
    }

    @Test fun aChatWithoutASendEndpointStaysReadOnly() = runTest {
        val vm = LiveChatViewModel(object : LiveChatGateway {
            override suspend fun listen(key: String, onPage: (ChatPage) -> Unit) {
                onPage(page(message("one")))
                awaitCancellation()
            }
            override suspend fun send(continuation: String, key: String, text: String) { fail("No send params were provided") }
        })
        vm.open("key")
        advanceUntilIdle()
        assertFalse(vm.uiState.value.canSend)
        vm.onAction(LiveChatAction.DraftChanged("hello"))
        vm.onAction(LiveChatAction.Send)
        advanceUntilIdle()
        vm.close()
    }

    @Test fun tvPollingUsesTheKnownBroadcastAddressAndPreservesPhotos() = runTest {
        val gateway = FakeGateway().apply {
            initialSendParams = null
            pages += page(message("one").copy(photo = "https://yt4.ggpht.com/avatar=s64"))
        }
        val vm = LiveChatViewModel(gateway)
        vm.open("continuation", "broadcast-address")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.canSend)
        assertEquals("https://yt4.ggpht.com/avatar=s64",
            (vm.uiState.value.content as LiveChatContent.Ready).messages.single().photo)
        vm.updateSession(true, 2)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.canSend)
        vm.onAction(LiveChatAction.DraftChanged("hello"))
        vm.onAction(LiveChatAction.Send)
        advanceUntilIdle()
        assertEquals(listOf("broadcast-address" to "hello"), gateway.sent)
        assertEquals("", vm.uiState.value.draft)
        vm.close()
    }

    @Test fun endedPollingCannotReenablePostingWithBroadcastParams() = runTest {
        val vm = LiveChatViewModel(EndingGateway(listOf(page(message("one")))))
        vm.open("continuation", "broadcast-address")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.ended)
        assertFalse(vm.uiState.value.canSend)
        vm.close()
    }

    /** A gateway whose continuation ends normally, as the server does when a stream ends. */
    private class EndingGateway(private val pages: List<ChatPage>) : LiveChatGateway {
        var replayCalls = 0
        var replayPosition = -1L
        override suspend fun listen(key: String, onPage: (ChatPage) -> Unit) {
            pages.forEach(onPage)
        }
        override suspend fun replay(key: String, positionMs: () -> Long, onPage: (ChatPage) -> Unit) {
            replayCalls++
            replayPosition = positionMs()
            pages.forEach(onPage)
        }
        override suspend fun send(continuation: String, key: String, text: String) = Unit
    }

    @Test fun aFinishedLiveStreamKeepsItsTranscriptAndStopsPosting() = runTest {
        val gateway = EndingGateway(listOf(page(message("a"))))
        val vm = LiveChatViewModel(gateway, signedIn = true)
        vm.open("key")
        advanceUntilIdle()
        val state = vm.uiState.value
        assertEquals(listOf("a"), (state.content as LiveChatContent.Ready).messages.map { it.id })
        assertTrue("A completed continuation means the stream ended", state.ended)
        assertFalse(state.replay)
        assertFalse("Ended chat cannot post", state.canSend)
        vm.close()
    }

    @Test fun anEndedStreamReplaysAtThePlayerPosition() = runTest {
        val gateway = EndingGateway(listOf(page(message("a"), message("b"))))
        val position = 65_000L
        val vm = LiveChatViewModel(gateway, signedIn = true)
        vm.openReplay("key") { position }
        advanceUntilIdle()
        val state = vm.uiState.value
        assertTrue(state.replay)
        assertTrue(state.ended)
        assertFalse(state.canSend)
        assertEquals(65_000L, gateway.replayPosition)
        assertEquals(setOf("a", "b"), (state.content as LiveChatContent.Ready).messages.map { it.id }.toSet())
        vm.close()
    }

    @Test fun aLiveStreamThatEndsSwitchesToReplayWithoutDroppingMessages() = runTest {
        val gateway = object : LiveChatGateway {
            var replayPosition = -1L
            override suspend fun listen(key: String, onPage: (ChatPage) -> Unit) {
                onPage(page(message("live")))
                awaitCancellation()
            }
            override suspend fun replay(key: String, positionMs: () -> Long, onPage: (ChatPage) -> Unit) {
                replayPosition = positionMs()
                onPage(page(message("replay")))
            }
            override suspend fun send(continuation: String, key: String, text: String) = Unit
        }
        val vm = LiveChatViewModel(gateway, signedIn = true)
        vm.open("key", "broadcast-address")
        advanceUntilIdle()
        assertFalse(vm.uiState.value.ended)
        vm.openReplay("key") { 12_000L }
        advanceUntilIdle()
        val state = vm.uiState.value
        assertTrue(state.replay)
        assertTrue(state.ended)
        assertEquals(listOf("live", "replay"), (state.content as LiveChatContent.Ready).messages.map { it.id })
        assertEquals(12_000L, gateway.replayPosition)
        vm.close()
    }
}
