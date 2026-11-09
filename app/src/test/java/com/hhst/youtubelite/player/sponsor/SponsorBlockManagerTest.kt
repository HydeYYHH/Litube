package com.hhst.youtubelite.player.sponsor

import com.hhst.youtubelite.extension.ExtensionManager
import com.hhst.youtubelite.extension.MemoryPrefStore
import com.hhst.youtubelite.extension.PreferenceKeys
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SponsorBlockManagerTest {
    private val first = "aaaaaaaaaaa"
    private val second = "bbbbbbbbbbb"

    private fun preferences() = ExtensionManager(MemoryPrefStore()).apply {
        importPreferences(PreferenceKeys.DEFAULTS.keys.filter { it.startsWith("skip_") }
            .associateWith { it == PreferenceKeys.SKIP_SPONSORS }, null)
    }

    private fun body(videoId: String) =
        """[{"videoID":"$videoId","segments":[{"category":"sponsor","segment":[1.0,2.0]}]}]"""

    @Test fun disablingTheLastCategoryBeforeDispatchSendsNoRequest() = runTest {
        val prefs = preferences()
        var calls = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(body(first).toResponseBody()).build()
        }.build()
        val manager = SponsorBlockManager(http, prefs, backgroundScope)
        val published = mutableListOf<List<SponsorBlockManager.Segment>>()
        manager.onSegmentsLoaded = { published += it }

        manager.load(first)
        prefs.setEnabled(PreferenceKeys.SKIP_SPONSORS, false)
        runCurrent()

        assertEquals(0, calls)
        assertTrue(manager.currentSegments.isEmpty())
        assertTrue(published.all { it.isEmpty() })
    }

    @Test fun disablingTheLastCategoryWhileReadingCannotRepublishSegments() = runTest {
        val prefs = preferences()
        val payload = body(first)
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val responseBody = object : ResponseBody() {
                override fun contentType() = null
                override fun contentLength() = payload.length.toLong()
                override fun source(): BufferedSource {
                    // OkHttp's cancellation check runs at headers; the body read can finish after the user disables the last category.
                    prefs.setEnabled(PreferenceKeys.SKIP_SPONSORS, false)
                    return Buffer().writeUtf8(payload)
                }
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(responseBody).build()
        }.build()
        val manager = SponsorBlockManager(http, prefs, backgroundScope)
        val published = mutableListOf<List<SponsorBlockManager.Segment>>()
        manager.onSegmentsLoaded = { published += it }

        manager.load(first)
        runCurrent()

        assertTrue(manager.currentSegments.isEmpty())
        assertTrue(published.all { it.isEmpty() })
    }

    @Test fun replacingAQueuedVideoOnlyFetchesTheCurrentVideo() = runTest {
        var calls = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(body(second).toResponseBody()).build()
        }.build()
        val manager = SponsorBlockManager(http, preferences(), backgroundScope)

        manager.load(first)
        manager.load(second)
        runCurrent()

        assertEquals(1, calls)
        assertEquals(listOf(SponsorBlockManager.Segment(1_000, 2_000, "sponsor")), manager.currentSegments)
    }

    @Test fun failedRefreshRetainsSegmentsAndReenablingRefetches() = runTest {
        val prefs = preferences()
        var calls = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(if (calls == 2) 503 else 200).message("Reply")
                .body(body(first).toResponseBody()).build()
        }.build()
        val manager = SponsorBlockManager(http, prefs, backgroundScope)
        manager.load(first)
        runCurrent()
        val loaded = manager.currentSegments
        assertTrue(loaded.isNotEmpty())

        manager.load(first)
        runCurrent()
        assertEquals(loaded, manager.currentSegments)
        prefs.setEnabled(PreferenceKeys.SKIP_SPONSORS, false)
        assertTrue(manager.currentSegments.isEmpty())
        prefs.setEnabled(PreferenceKeys.SKIP_SPONSORS, true)
        runCurrent()

        assertEquals(3, calls)
        assertEquals(loaded, manager.currentSegments)
    }
}
