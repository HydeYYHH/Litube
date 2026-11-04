package com.hhst.youtubelite.data.media

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.hhst.youtubelite.core.DeviceEvidence
import com.hhst.youtubelite.library.data.LibraryScenario
import com.hhst.youtubelite.library.domain.LibraryAction
import com.hhst.youtubelite.library.domain.LibraryContent
import com.hhst.youtubelite.library.presentation.YouViewModel
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test

/** Each failed You shelf retries through the real repository and core using one loopback request. */
class LibraryShelfRetryAndroidTest {
    @Test fun historyShelfRetryKeepsAccountAndPlaylistsWithoutReloadingThem() = verify(history = true)
    @Test fun playlistShelfRetryKeepsAccountAndHistoryWithoutReloadingThem() = verify(history = false)

    private fun verify(history: Boolean) = runBlocking<Unit> {
        val requestCounts = mutableListOf<Int>()
        repeat(5) {
            MockWebServer().use { server ->
                val accounts = AtomicInteger()
                val histories = AtomicInteger()
                val playlists = AtomicInteger()
                val retryEntered = CountDownLatch(1)
                val release = CountDownLatch(1)
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        val body = request.body.readUtf8()
                        if (request.path?.contains("accounts_list") == true) {
                            accounts.incrementAndGet()
                            return MockResponse().setBody(accountBody)
                        }
                        val historyRequest = body.contains("FEhistory")
                        val count = if (historyRequest) histories.incrementAndGet() else playlists.incrementAndGet()
                        if (historyRequest == history) {
                            if (count == 1) return MockResponse().setResponseCode(500).setBody("{}")
                            retryEntered.countDown()
                            check(release.await(10, TimeUnit.SECONDS))
                        }
                        return MockResponse().setBody(if (historyRequest) historyBody else playlistBody)
                    }
                }
                val transport = OkHttpClient.Builder().callTimeout(12, TimeUnit.SECONDS).addInterceptor { chain ->
                    chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
                }.build()
                val repo = MediaLibraryRepository(MediaClient(transport) {
                    RequestContext(accountId = "owner", accessToken = "fixture-access", generation = 7)
                })
                val store = ViewModelStore()
                val vm = withContext(Dispatchers.Main.immediate) {
                    YouViewModel(repo, SavedStateHandle(), LibraryScenario.Content, true).also { store.put("you", it) }
                }
                try {
                    val initial = withTimeout(5_000) { vm.uiState.first { it.content is LibraryContent.Ready } }
                    val before = (initial.content as LibraryContent.Ready).page
                    assertEquals(history, before.historyFailed)
                    assertEquals(!history, before.playlistsFailed)
                    assertEquals(3, server.requestCount)
                    val action = if (history) LibraryAction.RetryHistoryShelf else LibraryAction.RetryPlaylistsShelf
                    withContext(Dispatchers.Main.immediate) { repeat(5) { vm.onAction(action) } }
                    assertTrue(retryEntered.await(5, TimeUnit.SECONDS))
                    assertEquals(before, (vm.uiState.value.content as LibraryContent.Ready).page)
                    assertEquals(history, vm.uiState.value.historyRetrying)
                    assertEquals(!history, vm.uiState.value.playlistsRetrying)
                    release.countDown()
                    val done = withTimeout(5_000) { vm.uiState.first {
                        val page = (it.content as? LibraryContent.Ready)?.page
                        page != null && !page.historyFailed && !page.playlistsFailed && !it.historyRetrying && !it.playlistsRetrying
                    } }
                    val after = (done.content as LibraryContent.Ready).page
                    assertEquals(before.account, after.account)
                    if (history) assertEquals(before.playlists, after.playlists) else assertEquals(before.history, after.history)
                    assertEquals("video", after.history.single().videoId)
                    assertEquals("PLfixture", after.playlists.single().id)
                    withContext(Dispatchers.Main.immediate) { repeat(3) { vm.onAction(action) } }
                    assertEquals(1, accounts.get())
                    assertEquals(if (history) 2 else 1, histories.get())
                    assertEquals(if (history) 1 else 2, playlists.get())
                    assertEquals(4, server.requestCount)
                    requestCounts += server.requestCount
                } finally {
                    release.countDown()
                    withContext(Dispatchers.Main.immediate) { store.clear() }
                }
            }
        }
        DeviceEvidence.writeJson("msc-library-${if (history) "history" else "playlists"}-shelf-retry.json",
            """{"rounds":5,"requestsPerRound":$requestCounts,"initialRequestsPerRound":3,"retryRequestsPerRound":1,"accountAndPeerReloads":0}""")
    }

    private val accountBody = """{"contents":[{"accountItemRenderer":{"accountId":"owner","accountName":{"simpleText":"Owner"},"accountByline":{"simpleText":"owner@example.com"},"isSelected":true}}]}"""
    private val historyBody = """{"contents":[{"videoRenderer":{"videoId":"video","title":{"simpleText":"History video"},"lengthText":{"simpleText":"1:00"}}}]}"""
    private val playlistBody = """{"contents":[{"playlistRenderer":{"playlistId":"PLfixture","title":{"simpleText":"Playlist"},"videoCount":"1"}}]}"""
}
