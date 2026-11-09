package com.hhst.youtubelite.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.hhst.youtubelite.core.YoutubeThumbnail
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class YoutubeThumbContinuityAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun prepare() {
        YoutubeThumbnail.clearMemory()
        compose.mainClock.autoAdvance = false
    }

    @After fun cleanUp() {
        compose.mainClock.autoAdvance = true
        YoutubeThumbnail.clearMemory()
    }

    /** The public loader rejects this fixture host; all tests stay offline. */
    private fun seed(url: String, color: Int) = runBlocking {
        val source = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        val bytes = try {
            ByteArrayOutputStream().use { output ->
                source.compress(Bitmap.CompressFormat.PNG, 100, output)
                output.toByteArray()
            }
        } finally { source.recycle() }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("Fixture").body(bytes.toResponseBody()).build()
        }.build()
        requireNotNull(YoutubeThumbnail.load(url, 128, YoutubeThumbnail.version.value, client))
    }

    private fun assertCenter(color: Int) {
        val pixels = compose.onNodeWithTag("thumbnail").captureToImage().asAndroidBitmap()
        assertEquals(color, pixels.getPixel(pixels.width / 2, pixels.height / 2))
    }

    @Test fun cachedImagesRenderImmediatelyAndNewSourcesNeverKeepOldPixels() {
        val first = "https://example.invalid/continuity/first.png"
        val second = "https://example.invalid/continuity/second.png"
        seed(first, android.graphics.Color.RED)
        seed(second, android.graphics.Color.BLUE)
        var url by mutableStateOf(first)
        compose.setContent {
            YoutubeThumb(url, Modifier.size(128.dp).testTag("thumbnail"), placeholderColor = Color.Black)
        }
        compose.mainClock.advanceTimeByFrame()
        assertCenter(android.graphics.Color.RED)

        compose.runOnIdle { url = second }
        compose.mainClock.advanceTimeByFrame()
        assertCenter(android.graphics.Color.BLUE)

        compose.runOnIdle { url = "https://example.invalid/continuity/not-cached.png" }
        compose.mainClock.advanceTimeByFrame()
        assertCenter(android.graphics.Color.BLACK)
    }

    @Test fun clearingTheCacheAndResizingKeepTheVisibleImageThroughEveryFrame() {
        val url = "https://example.invalid/continuity/resize.png"
        seed(url, android.graphics.Color.RED)
        var size by mutableIntStateOf(128)
        compose.setContent {
            YoutubeThumb(url, Modifier.size(size.dp).testTag("thumbnail"), placeholderColor = Color.Black)
        }
        compose.mainClock.advanceTimeByFrame()
        assertCenter(android.graphics.Color.RED)

        compose.runOnIdle {
            YoutubeThumbnail.clearMemory()
            size = 256
        }
        repeat(8) {
            compose.mainClock.advanceTimeByFrame()
            assertCenter(android.graphics.Color.RED)
        }
    }
}
