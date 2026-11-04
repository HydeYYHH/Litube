package com.hhst.youtubelite.explore

import androidx.lifecycle.ViewModelStore
import com.hhst.youtubelite.core.DeviceEvidence
import com.hhst.youtubelite.data.media.MediaClient
import com.hhst.youtubelite.data.media.MediaExploreRepository
import com.hhst.youtubelite.explore.data.ExploreScenario
import com.hhst.youtubelite.explore.domain.*
import com.hhst.youtubelite.home.domain.AppendState
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestContext
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** App repository + core parser + ViewModel budgets over real loopback HTTP. */
class ExplorePagingBudgetAndroidTest {
    @Test fun emptyAndDuplicatePageAndShelfChainsStopAfterThreeNetworkReads() = runBlocking {
        val evidence = mutableListOf<String>()
        for (shelf in listOf(false, true)) for (duplicate in listOf(false, true)) {
            MockWebServer().use { server ->
                val continuations = AtomicInteger()
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        val continued = request.body.readUtf8().contains("\"continuation\"")
                        val count = if (continued) continuations.incrementAndGet() else 0
                        val rows = when {
                            count == 0 -> video("existing000")
                            count >= 4 -> video("new0000000$count")
                            duplicate -> video("existing000")
                            else -> ""
                        }
                        val key = "k${count + 1}"
                        val contents = listOf(rows, cursor(key)).filter(String::isNotEmpty).joinToString(",")
                        val body = if (shelf && count == 0) """{"contents":[{"shelfRenderer":{
                            "title":{"simpleText":"Shelf"},"content":{"horizontalListRenderer":{"items":[$contents]}}}}]}"""
                        else """{"contents":[$contents]}"""
                        return MockResponse().setBody(body).setHeadersDelay(20, TimeUnit.MILLISECONDS)
                    }
                }
                server.start()
                val http = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).addInterceptor { chain ->
                    chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
                }.build()
                val store = ViewModelStore()
                val vm = withContext(Dispatchers.Main) {
                    ExploreViewModel(MediaExploreRepository(MediaClient(http) { RequestContext(visitor = "explore-fixture") }),
                        ExploreScenario.Content).also { store.put("explore", it) }
                }
                suspend fun ready(): ExploreContent.Ready? = withContext(Dispatchers.Main) {
                    vm.uiState.value.content as? ExploreContent.Ready
                }
                suspend fun awaitCount(count: Int) = withTimeout(5_000) {
                    while (true) {
                        val state = ready()
                        val empty = if (shelf) state?.page?.sections?.singleOrNull()?.emptyAutoLoads else state?.emptyAutoLoads
                        if (empty == count) break
                        delay(10)
                    }
                }
                suspend fun dispatch(manual: Boolean) = withContext(Dispatchers.Main) {
                    vm.onAction(if (shelf) {
                        if (manual) ExploreAction.LoadShelf(0) else ExploreAction.AutomaticLoadShelf(0)
                    } else if (manual) ExploreAction.LoadMore else ExploreAction.AutomaticLoadMore)
                }
                try {
                    withTimeout(5_000) { while (ready() == null) delay(10) }
                    repeat(3) { dispatch(manual = false); awaitCount(it + 1) }
                    repeat(20) { dispatch(manual = false) }
                    assertEquals(3, continuations.get())
                    val paused = requireNotNull(ready())
                    assertEquals(AppendState.Idle, if (shelf) paused.page.sections.single().append else paused.append)
                    assertTrue(if (shelf) paused.page.sections.single().automaticPagingPaused else paused.automaticPagingPaused)
                    dispatch(manual = true)
                    awaitCount(0)
                    assertEquals(4, continuations.get())
                    dispatch(manual = false)
                    withTimeout(5_000) {
                        while (true) {
                            val state = ready()
                            val rows = if (shelf) state?.page?.sections?.singleOrNull()?.videos else state?.page?.videos
                            if (rows?.any { it.id == "new00000005" } == true) break
                            delay(10)
                        }
                    }
                    assertEquals(5, continuations.get())
                    assertEquals(6, server.requestCount)
                    evidence += """{"shelf":$shelf,"duplicate":$duplicate,"automaticBeforePause":3,"requestsAfterManualAndGrowth":${server.requestCount}}"""
                } finally {
                    withContext(Dispatchers.Main) { store.clear() }
                    http.connectionPool.evictAll()
                    http.dispatcher.executorService.shutdown()
                }
            }
        }
        DeviceEvidence.writeJson("msc-explore-paging-budget.json", "[${evidence.joinToString(",")}]")
    }

    private companion object {
        fun video(id: String) = """{"videoRenderer":{"videoId":"$id","title":{"simpleText":"$id"}}}"""
        fun cursor(key: String) = """{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"$key"}}}}"""
    }
}
