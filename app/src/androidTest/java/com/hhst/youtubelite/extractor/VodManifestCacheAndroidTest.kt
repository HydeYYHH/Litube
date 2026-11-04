@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.hhst.youtubelite.extractor

import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.hhst.youtubelite.player.datasource.VodManifestCache
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class VodManifestCacheAndroidTest {
    @Test fun completeReadsReuseOnlyMatchingSessionAndUnexpiredManifest() {
        var time = 1_000L
        var current = true
        var opens = 0
        val upstream = DataSource.Factory { opens++; ByteArrayDataSource("#EXTM3U\nfixture".toByteArray()) }
        val cache = VodManifestCache { time }
        fun factory(scope: String = "account-one") = cache.factory(scope, 1_000_000, { current }, upstream)
        val spec = DataSpec.Builder().setUri("https://manifest.googlevideo.com/fixture.m3u8").build()
        fun read(source: DataSource.Factory): String {
            val data = source.createDataSource()
            try {
                data.open(spec)
                val bytes = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(4)
                while (true) { val count = data.read(chunk, 0, chunk.size); if (count < 0) break; bytes.write(chunk, 0, count) }
                return bytes.toString("UTF-8")
            } finally { data.close() }
        }
        assertEquals("#EXTM3U\nfixture", read(factory()))
        assertEquals("#EXTM3U\nfixture", read(factory()))
        assertEquals(1, opens)
        read(factory("account-two")); assertEquals(2, opens)
        current = false
        assertThrows(IOException::class.java) { read(factory()) }
        assertEquals(2, opens)
        current = true; time += 120_001
        read(factory()); assertEquals(3, opens)
    }

    @Test fun partialOrOversizeReadsNeverBecomeCompleteManifests() {
        var opens = 0
        val upstream = DataSource.Factory { opens++; ByteArrayDataSource(ByteArray(VodManifestCache.MAX_DOCUMENT_BYTES + 1) { 7 }) }
        val factory = VodManifestCache().factory("session", Long.MAX_VALUE, { true }, upstream)
        val spec = DataSpec.Builder().setUri("https://manifest.googlevideo.com/large.m3u8").build()
        repeat(2) { index ->
            val source = factory.createDataSource()
            try {
                source.open(spec)
                val bytes = ByteArray(8192)
                if (index == 0) source.read(bytes, 0, bytes.size)
                else while (source.read(bytes, 0, bytes.size) >= 0) { }
            } finally { source.close() }
        }
        val third = factory.createDataSource()
        try { third.open(spec); assertEquals(3, opens) } finally { third.close() }
    }
    @Test fun aComplete400KiBPlaylistCanBeReused() {
        verifyLargeDocuments(listOf("one", "one"), 400 * 1024, 1)
    }

    @Test fun largerDocumentsStillRespectTheTotalCacheBudget() {
        // The second 600 KiB document evicts the first from the 1 MiB cache.
        verifyLargeDocuments(listOf("one", "two", "two", "one"), 600 * 1024, 3)
    }

    private fun verifyLargeDocuments(paths: List<String>, size: Int, expectedOpens: Int) {
        val bytes=ByteArray(size) { (it % 251).toByte() }
        var opens=0
        val factory=VodManifestCache().factory("session",Long.MAX_VALUE,{true},
            DataSource.Factory { opens++;ByteArrayDataSource(bytes) })
        paths.forEach { path ->
            val source=factory.createDataSource()
            try {
                source.open(DataSpec.Builder().setUri("https://manifest.googlevideo.com/$path.m3u8").build())
                val actual=java.io.ByteArrayOutputStream()
                val buffer=ByteArray(8192)
                while(true) {
                    val count=source.read(buffer,0,buffer.size)
                    if(count<0)break
                    actual.write(buffer,0,count)
                }
                assertArrayEquals(bytes,actual.toByteArray())
            } finally { source.close() }
        }
        assertEquals(expectedOpens,opens)
    }
}
