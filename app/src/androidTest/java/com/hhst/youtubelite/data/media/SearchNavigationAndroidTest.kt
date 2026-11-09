package com.hhst.youtubelite.data.media

import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.hhst.youtubelite.core.DeviceEvidence
import com.hhst.youtubelite.search.domain.*
import com.hhst.youtubelite.search.presentation.SearchResultsViewModel
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestContext
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Actual app repositories and loopback HTTP, including the metadata ViewModel window. */
class SearchNavigationAndroidTest {
    @Test fun filterNavigationPreservesBothSelectionsWithHalfTheRequestsAcrossFiveTrials() = runBlocking {
        Fixture().use { fixture ->
            val reused = mutableListOf<Long>()
            val cold = mutableListOf<Long>()
            val today = SearchFilters(uploaded = SearchUploadDate.Today)
            suspend fun trial(reuse: Boolean): Long {
                val before = fixture.searches.get()
                val first = fixture.repository()
                first.search("q", SearchFilters())
                val started = SystemClock.elapsedRealtime()
                val date = (if (reuse) first else fixture.repository()).search("q", today)
                val combined = (if (reuse) first else fixture.repository()).search("q", today.copy(type = SearchResultType.Channels))
                assertEquals("Today|", date.results.single().title)
                assertEquals("Today|Channel", combined.results.single().title)
                assertEquals(if (reuse) 3 else 6, fixture.searches.get() - before)
                return SystemClock.elapsedRealtime() - started
            }
            repeat(5) { iteration ->
                if (iteration % 2 == 0) { reused += trial(true); cold += trial(false) }
                else { cold += trial(false); reused += trial(true) }
            }
            assertEquals(45, fixture.searches.get())
            assertEquals(0, fixture.players.get())
            DeviceEvidence.writeJson("msc-search-navigation.json", """{
                "trials":5,"perRequestDelayMs":60,"reusedFilterMs":$reused,"coldFilterMs":$cold,
                "reusedSearchesPerTrial":3,"coldSearchesPerTrial":6,"totalSearches":${fixture.searches.get()}
            }""".trimIndent())
        }
    }

    @Test fun aChangedVisitorCannotReuseThePreviousSearchPanel() = runBlocking {
        Fixture().use { fixture ->
            val repository = fixture.repository()
            repository.search("q", SearchFilters())
            fixture.context.set(fixture.context.get().copy(visitor = "other-visitor", generation = 2))
            val page = repository.search("q", SearchFilters(uploaded = SearchUploadDate.Today))
            assertEquals("Today|", page.results.single().title)
            assertEquals(3, fixture.searches.get())
        }
    }

    @Test fun sparseMetadataDoesNotAutoRepeatButViewportReentryCanCompleteIt() = runBlocking {
        Fixture().use { fixture ->
            val store = ViewModelStore()
            val vm = withContext(Dispatchers.Main) {
                SearchResultsViewModel(fixture.repository(), SavedStateHandle(), "q").also { store.put("search", it) }
            }
            suspend fun row(): SearchResult? = withContext(Dispatchers.Main) {
                (vm.uiState.value.content as? SearchContent.Results)?.page?.results?.singleOrNull()
            }
            try {
                withTimeout(5_000) { while (row()?.duration != "1:00") delay(10) }
                // Observe beyond the removed 800ms timer; the same visible row stays partial.
                delay(1_200)
                assertEquals(1, fixture.players.get())
                assertEquals("", row()?.views)
                withContext(Dispatchers.Main) {
                    repeat(5) { vm.onAction(SearchAction.VisibleResults("q", SearchFilters(), listOf(videoId))) }
                }
                assertEquals(1, fixture.players.get())
                withContext(Dispatchers.Main) {
                    vm.onAction(SearchAction.VisibleResults("q", SearchFilters(), emptyList()))
                    vm.onAction(SearchAction.VisibleResults("q", SearchFilters(), listOf(videoId)))
                }
                withTimeout(5_000) { while (row()?.views != "100 views") delay(10) }
                assertEquals(2, fixture.players.get())
                assertEquals("1:00", row()?.duration)
                assertEquals("2026-11-06", row()?.age)
                assertEquals(1, fixture.searches.get())
            } finally { withContext(Dispatchers.Main) { store.clear() } }
        }
    }

    private class Fixture : java.io.Closeable {
        val searches = AtomicInteger()
        val players = AtomicInteger()
        val context = AtomicReference(RequestContext(visitor = "search-fixture", generation = 1))
        private val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path.orEmpty().endsWith("/player")) {
                        val extra = if (players.incrementAndGet() == 1) "" else
                            """, "microformat":{"playerMicroformatRenderer":{"publishDate":"2026-11-06"}}"""
                        val views = if (players.get() == 1) "" else ", \"viewCount\":\"100\""
                        return MockResponse().setBody("""{"videoDetails":{"videoId":"$videoId","title":"Result",
                            "author":"Author","channelId":"UCfixture","lengthSeconds":"60"$views}$extra}""")
                    }
                    searches.incrementAndGet()
                    val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                    return MockResponse().setBody(document(body["params"]?.jsonPrimitive?.contentOrNull))
                        .setHeadersDelay(60, TimeUnit.MILLISECONDS)
                }
            }
            start()
        }
        private val http = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
        }.build()
        private val client = MediaClient(http) { context.get() }
        fun repository() = MediaSearchRepository(client)
        override fun close() {
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdown()
            server.close()
        }
    }

    private companion object {
        const val videoId = "abcdefghijk"
        fun document(params: String?): String {
            val date = params?.substringBefore('|').orEmpty()
            val type = params?.substringAfter('|').orEmpty()
            fun option(label: String, next: String, selected: Boolean) = """{"searchFilterRenderer":{
                "label":{"simpleText":"$label"},"status":"${if (selected) "FILTER_STATUS_SELECTED" else "FILTER_STATUS_OFF"}",
                "navigationEndpoint":{"searchEndpoint":{"params":"$next"}}}}"""
            val types = listOf("Channel", "Playlist").joinToString(",") { option(it, "$date|$it", it == type) }
            val dates = listOf("Today", "This week").joinToString(",") { option(it, "$it|$type", it == date) }
            return """{"contents":[{"videoRenderer":{"videoId":"$videoId","title":{"simpleText":"$date|$type"}}},
                {"searchFilterGroupRenderer":{"title":{"simpleText":"Type"},"contents":[$types]}},
                {"searchFilterGroupRenderer":{"title":{"simpleText":"Upload date"},"contents":[$dates]}}]}"""
        }
    }
}
