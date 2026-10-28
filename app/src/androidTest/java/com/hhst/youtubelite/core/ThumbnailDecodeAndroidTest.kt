package com.hhst.youtubelite.core

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbnailDecodeAndroidTest {
    private fun image(width: Int, height: Int): ByteArray {
        val source = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return try {
            ByteArrayOutputStream().use { output ->
                source.compress(Bitmap.CompressFormat.PNG, 100, output)
                output.toByteArray()
            }
        } finally { source.recycle() }
    }

    @Test fun decodingKeepsTheRequestedDetailWithoutUpscaling() {
        for ((width, height, target) in listOf(Triple(1280, 720, 1080), Triple(3840, 2160, 1080),
            Triple(720, 1280, 1080), Triple(320, 180, 1080), Triple(301, 199, 256))) {
            val decoded = YoutubeThumbnail.decode(image(width, height), target)
            assertNotNull(decoded)
            requireNotNull(decoded)
            try {
                assertEquals(minOf(maxOf(width, height), target), maxOf(decoded.width, decoded.height))
                assertTrue(kotlin.math.abs(decoded.width.toFloat() / decoded.height - width.toFloat() / height) < 0.01f)
                assertTrue(decoded.byteCount <= target * target * 4)
                assertEquals(Bitmap.DENSITY_NONE, decoded.density)
            } finally { decoded.recycle() }
        }
    }

    @Test fun invalidBytesDoNotProduceAnImage() {
        assertNull(YoutubeThumbnail.decode(byteArrayOf(1, 2, 3), 1080))
    }
}
