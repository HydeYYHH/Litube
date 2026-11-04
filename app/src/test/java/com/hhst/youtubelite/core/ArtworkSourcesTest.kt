package com.hhst.youtubelite.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ArtworkSourcesTest {
    @Test fun nearbyCardSizesShareADecodeWithoutLosingRequestedDetail() {
        assertEquals(artworkDecodeDimension(389), artworkDecodeDimension(391))
        for (target in 1..2000) {
            val decoded = artworkDecodeDimension(target)
            org.junit.Assert.assertTrue(decoded >= target.coerceIn(64, 1600))
            org.junit.Assert.assertTrue(decoded in 64..1600)
            org.junit.Assert.assertTrue(decoded - target.coerceIn(64, 1600) < 64)
        }
    }
    @Test fun largeVideoCardsRequestAnUnscaledCoverWithTheOriginalAsFallback() {
        val original = "https://i.ytimg.com/vi/GjjK6yD5I9I/mqdefault.jpg?sqp=small&sigh=token"
        assertEquals(listOf("https://i.ytimg.com/vi/GjjK6yD5I9I/hq720.jpg", original),
            artworkSources(original, 1170))
        assertEquals(listOf(original), artworkSources(original, 128))
    }

    @Test fun webpCoversKeepTheirTransportAndForeignResourcesStayUnchanged() {
        val original = "https://i9.ytimg.com/vi_webp/FEZS601QSVE/hqdefault.webp"
        assertEquals(listOf("https://i9.ytimg.com/vi_webp/FEZS601QSVE/hq720.webp", original),
            artworkSources(original, 640))
        for (url in listOf("https://yt3.ggpht.com/avatar", "https://example.com/vi/GjjK6yD5I9I/mqdefault.jpg",
                "https://i.ytimg.com/vi/GjjK6yD5I9I/1.jpg", "bunny", "https://i.ytimg.com:8443/vi/GjjK6yD5I9I/mqdefault.jpg")) {
            assertEquals(listOf(url), artworkSources(url, 1170))
        }
        assertEquals(emptyList<String>(), artworkSources(null, 1170))
    }
}
