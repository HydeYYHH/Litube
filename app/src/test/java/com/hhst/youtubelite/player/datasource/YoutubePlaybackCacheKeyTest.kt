package com.hhst.youtubelite.player.datasource

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YoutubePlaybackCacheKeyTest {

    @Test
    fun identityIgnoresRotatingSignatureAndIncludesLanguageAndLength() {
        val a = YoutubePlaybackCacheKey.ofQuery(
            "id=abc&itag=140&xtags=acont=original:lang=en&clen=1000&sig=old&expire=1&rn=2",
        )
        val b = YoutubePlaybackCacheKey.ofQuery(
            "id=abc&itag=140&xtags=acont=original:lang=en&clen=1000&sig=new&expire=9&pot=x",
        )
        assertEquals(a, b)
        assertEquals("yt:v2:abc:140:acont=original:lang=en:1000", a)
    }

    @Test
    fun differentLanguageOrContentLengthIsADifferentKey() {
        val en = YoutubePlaybackCacheKey.ofQuery("id=abc&itag=140&xtags=lang=en&clen=1000")
        val es = YoutubePlaybackCacheKey.ofQuery("id=abc&itag=140&xtags=lang=es&clen=1000")
        val longer = YoutubePlaybackCacheKey.ofQuery("id=abc&itag=140&xtags=lang=en&clen=2000")
        assertNotEquals(en, es)
        assertNotEquals(en, longer)
    }

    @Test
    fun versionPrefixIsolatesLegacyIdItagKeys() {
        val key = YoutubePlaybackCacheKey.ofQuery("id=abc&itag=137")!!
        assertEquals("yt:v2:abc:137::", key)
        assertNotEquals("yt:abc:137", key)
    }

    @Test
    fun missingIdOrItagIsNotAPlaybackKey() {
        assertNull(YoutubePlaybackCacheKey.ofQuery("itag=137"))
        assertNull(YoutubePlaybackCacheKey.ofQuery("id=abc"))
        assertNull(YoutubePlaybackCacheKey.ofQuery(""))
    }
}
