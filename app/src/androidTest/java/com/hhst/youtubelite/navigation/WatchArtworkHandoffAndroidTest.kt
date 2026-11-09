package com.hhst.youtubelite.navigation

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import androidx.compose.ui.geometry.Rect
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.core.YoutubeThumbnail
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class WatchArtworkHandoffAndroidTest {
    private fun cacheImage(url: String, dimension: Int): Bitmap = runBlocking {
        val image = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        val bytes = ByteArrayOutputStream().use { output ->
            image.compress(Bitmap.CompressFormat.PNG, 100, output)
            output.toByteArray()
        }
        image.recycle()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("Artwork").body(bytes.toResponseBody()).build()
        }.build()
        requireNotNull(YoutubeThumbnail.load(url, dimension, YoutubeThumbnail.version.value, client))
    }

    @Test fun cardOriginRemainsAvailableWithoutAPlayerPosterHandoff() {
        val url = "https://i.ytimg.com/vi/cleanPoster/hqdefault.jpg"
        val clean = cacheImage(url, 128)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val card = View(instrumentation.targetContext).apply {
                // Simulates a card whose visible screenshot includes a red progress overlay.
                setBackgroundColor(Color.RED)
                layout(0, 0, 100, 100)
            }
            val intent = Intent()
            val bounds = Rect(0f, 0f, 100f, 100f)
            WatchTransition.artwork(card, "cleanPoster", bounds)
            WatchTransition.prepare(card, "cleanPoster", intent)
            assertEquals(bounds, WatchTransition.origin(intent))
            assertSame(clean, YoutubeThumbnail.cachedForDisplay(url))
            assertEquals(Color.RED, WatchTransition.backdrop!!.getPixel(16, 16))
        }
    }

    @Test fun anAvatarAlreadyDecodedForTheCardIsAvailableAtAnotherDisplaySize() {
        val url = "https://yt3.ggpht.com/watch-avatar-handoff"
        val avatar = cacheImage(url, 192)
        assertNull(YoutubeThumbnail.cached(url, 128))
        assertSame(avatar, YoutubeThumbnail.cachedForDisplay(url))
    }

    @Test fun aSourceWithoutThumbnailBoundsStillBelongsOnlyToItsOwnEntrance() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val browse = View(instrumentation.targetContext).apply {
                setBackgroundColor(Color.BLUE)
                layout(0, 0, 100, 100)
            }
            val prepared = Intent()
            WatchTransition.prepare(browse, "no-thumbnail", prepared)
            assertNull("A text/link entrance must not invent thumbnail geometry", WatchTransition.origin(prepared))
            val source = requireNotNull(WatchTransition.backdrop(prepared))
            assertEquals(Color.BLUE, source.getPixel(50, 50))
            assertSame(source, WatchTransition.backdrop(Intent(prepared)))
            assertNull("Direct notification/external launches cannot reuse an older page", WatchTransition.backdrop(Intent()))

            browse.setBackgroundColor(Color.GREEN)
            val next = Intent()
            WatchTransition.prepare(browse, "next-entrance", next)
            assertNull("An older intent cannot pick up the newest entrance's page", WatchTransition.backdrop(prepared))
            assertEquals(Color.GREEN, requireNotNull(WatchTransition.backdrop(next)).getPixel(50, 50))
        }
    }
}
