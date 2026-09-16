package com.hhst.youtubelite.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream

class YoutubeThumbnailTest {

    @Test
    fun readBounded_rejectsOversizeAndKeepsExactCap() {
        val exact = ByteArray(16) { 1 }
        val got = YoutubeThumbnail.readBounded(ByteArrayInputStream(exact), 16)
        assertNotNull(got)
        assertEquals(16, got!!.size)

        val over = ByteArray(17) { 2 }
        assertNull(YoutubeThumbnail.readBounded(ByteArrayInputStream(over), 16))
    }
}
