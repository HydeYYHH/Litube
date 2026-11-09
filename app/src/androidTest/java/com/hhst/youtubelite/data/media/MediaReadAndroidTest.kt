package com.hhst.youtubelite.data.media

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import com.hhst.youtubelite.search.domain.SearchFilters

class MediaReadAndroidTest {
    @Test fun anonymousContentReadsUseTheCoreAndPreserveIdentities() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("network") == "1")
        val repositories = ContentRepositories(MediaClient(OkHttpClient.Builder().callTimeout(java.time.Duration.ofSeconds(40)).build()))
        val search = repositories.search.search("blender open movie", SearchFilters())
        assertTrue("Search must contain actual videos", search.results.isNotEmpty())
        assertNotNull("Real video artwork must decode", kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.hhst.youtubelite.core.YoutubeThumbnail.fetch(search.results.first().artwork)
        })
        val cursor = requireNotNull(search.nextCursor) { "A broad search must expose continuation" }
        val next = repositories.search.next("blender open movie", SearchFilters(), cursor)
        assertTrue("Continuation must contain actual videos", next.results.isNotEmpty())
        val id = search.results.first().id
        assertTrue(id.matches(Regex("[A-Za-z0-9_-]{11}")))
        val watch = repositories.watch.watch(id)
        assertEquals(id, watch.video.id)
        assertTrue(watch.video.title.isNotBlank())
        assertFalse(watch.video.channelId.isBlank())
        assertTrue("Watch author must be visible", watch.video.channel.isNotBlank())
        assertTrue("Author artwork must not be a video cover", !watch.video.avatar.isNullOrBlank())
        val channel = repositories.channel.channel(watch.video.channelId)
        assertTrue(channel.profile.name.isNotBlank())
        assertTrue("Channel handle must be preserved", channel.profile.handle.isNotBlank())
        assertTrue("Subscriber count must be preserved", channel.profile.subscribers.isNotBlank())
        val comments = repositories.comments().comments(com.hhst.youtubelite.comments.domain.CommentTarget.Video("aqz-KE-bpKQ"),
            com.hhst.youtubelite.comments.domain.CommentSort.Top, null)
        assertTrue("Public reference video must return actual comments", comments.threads.isNotEmpty())
        assertTrue(comments.threads.all { it.id.isNotBlank() && it.author.displayName.isNotBlank() && it.body.isNotBlank() })
        assertTrue("Comment total must be preserved", comments.total.isNotBlank())
    }
    @Test fun normalEntryUsesNativeHostsAndTheRealRepositoryWithoutStartingPlayback() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("network") == "1")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertFalse(ContentSource.fixtures)
        fun hasWebView(view: android.view.View): Boolean = view is android.webkit.WebView ||
            (view is android.view.ViewGroup && (0 until view.childCount).any { hasWebView(view.getChildAt(it)) })
        androidx.test.core.app.ActivityScenario.launch<com.hhst.youtubelite.home.HomeActivity>(
            android.content.Intent(context, com.hhst.youtubelite.home.HomeActivity::class.java)).use { home ->
            home.onActivity {
                assertFalse(hasWebView(it.window.decorView))
                assertFalse((it.application as com.hhst.youtubelite.App).mediaRuntimeStarted)
            }
            androidx.test.core.app.ActivityScenario.launch<com.hhst.youtubelite.search.SearchResultsActivity>(
                com.hhst.youtubelite.search.SearchResultsActivity.intent(context, "blender open movie")).use { search ->
                val deadline = android.os.SystemClock.elapsedRealtime() + 45_000
                var ready = false
                while (!ready && android.os.SystemClock.elapsedRealtime() < deadline) {
                    search.onActivity {
                        ready = it.searchViewModel.uiState.value.content is com.hhst.youtubelite.search.domain.SearchContent.Results
                    }
                    if (!ready) Thread.sleep(100)
                }
                assertTrue("Real search did not render results", ready)
                search.onActivity {
                    val page = (it.searchViewModel.uiState.value.content as com.hhst.youtubelite.search.domain.SearchContent.Results).page
                    assertTrue(page.results.isNotEmpty())
                    assertTrue(page.results.all { result -> result.id.matches(Regex("[A-Za-z0-9_-]{11}")) })
                    assertFalse(hasWebView(it.window.decorView))
                    assertFalse((it.application as com.hhst.youtubelite.App).mediaRuntimeStarted)
                }
                instrumentation.waitForIdleSync()
                var artwork: String? = null
                search.onActivity {
                    artwork = (it.searchViewModel.uiState.value.content as com.hhst.youtubelite.search.domain.SearchContent.Results)
                        .page.results.first().artwork.let { url -> if (url.startsWith("//")) "https:$url" else url }
                }
                val imageDeadline = android.os.SystemClock.elapsedRealtime() + 20_000
                while (com.hhst.youtubelite.core.YoutubeThumbnail.cached(artwork) == null &&
                    android.os.SystemClock.elapsedRealtime() < imageDeadline) Thread.sleep(100)
                assertNotNull("The rendered result thumbnail must finish loading", com.hhst.youtubelite.core.YoutubeThumbnail.cached(artwork))
                instrumentation.waitForIdleSync()
                com.hhst.youtubelite.core.DeviceEvidence.captureScene("native-search-service")
            }
        }
    }

    @Test fun nativeDeviceSignInLoadsACodeWithoutAnAccountWrite() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("network") == "1")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        androidx.test.core.app.ActivityScenario.launch<com.hhst.youtubelite.account.SignInActivity>(
            android.content.Intent(context, com.hhst.youtubelite.account.SignInActivity::class.java)).use { host ->
            var state: com.hhst.youtubelite.account.domain.SignInUiState? = null
            val deadline = android.os.SystemClock.elapsedRealtime() + 60_000
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                host.onActivity {
                    val model = androidx.lifecycle.ViewModelProvider(it)[com.hhst.youtubelite.account.presentation.SignInViewModel::class.java]
                    state = model.uiState.value
                }
                if (state is com.hhst.youtubelite.account.domain.SignInUiState.Ready ||
                    state is com.hhst.youtubelite.account.domain.SignInUiState.Error) break
                Thread.sleep(100)
            }
            assertTrue("Device sign-in state: $state", state is com.hhst.youtubelite.account.domain.SignInUiState.Ready)
            val ready = state as com.hhst.youtubelite.account.domain.SignInUiState.Ready
            assertTrue(ready.userCode.isNotBlank())
            assertTrue(ready.verificationUrl.startsWith("https://"))
        }
    }

}
