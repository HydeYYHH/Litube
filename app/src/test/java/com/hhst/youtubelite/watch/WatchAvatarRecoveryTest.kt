package com.hhst.youtubelite.watch

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.hhst.youtubelite.data.media.MediaAvatarDirectory
import com.hhst.youtubelite.data.media.MediaClient
import com.hhst.youtubelite.watch.data.FixtureWatchRepository
import com.hhst.youtubelite.watch.domain.*
import com.hhst.youtubelite.watch.presentation.WatchViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class WatchAvatarRecoveryTest {
    @Test fun missingWatchAndRecommendedAvatarsFillWithoutBlockingThePage() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val client = MediaClient(OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            assertEquals("67", chain.request().header("X-Youtube-Client-Name"))
            check(release.await(5, TimeUnit.SECONDS))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"header":{"musicVisualHeaderRenderer":{"foregroundThumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://yt3.ggpht.com/avatar"}]}}}}}}""".toResponseBody()).build()
        }.build())
        val directory = MediaAvatarDirectory(client)
        val store = ViewModelStore()
        try {
            val base = FixtureWatchRepository.samplePage("bunny")
            val page = base.copy(video = base.video.copy(channelId = "UCabc", avatar = ""),
                related = base.related.take(2).map { it.copy(channelId = "UCabc", avatar = "") })
            val repository = object : WatchRepository { override suspend fun watch(videoId: String) = page }
            val model = WatchViewModel(repository, SavedStateHandle(), "bunny", avatars = directory)
            store.put("watch", model)
            assertTrue(model.uiState.value.content is WatchContent.Ready)
            assertTrue((model.uiState.value.content as WatchContent.Ready).page.video.avatar.isNullOrBlank())
            release.countDown()
            val loaded = withTimeout(5_000) { model.uiState.first { state ->
                (state.content as? WatchContent.Ready)?.page?.let {
                    it.video.avatar == "https://yt3.ggpht.com/avatar" && it.related.all { row -> row.avatar == it.video.avatar }
                } == true
            } }.content as WatchContent.Ready
            assertEquals(2, loaded.page.related.size)
            assertEquals(1, calls.get())
        } finally {
            release.countDown()
            store.clear()
            directory.dispose()
            Dispatchers.resetMain()
        }
    }
}
