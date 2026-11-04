package com.hhst.youtubelite.core

import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.extractor.Chapter
import com.hhst.youtubelite.extractor.ChapterList
import com.hhst.youtubelite.extractor.Format
import com.hhst.youtubelite.extractor.Metadata
import com.hhst.youtubelite.extractor.Stream
import com.hhst.youtubelite.player.engine.ResumePoint
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PreferencePersistenceAndroidTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun modelCacheRoundTripsThroughANewStoreInstance() {
        val store = PreferenceStore.of(context, "cache-roundtrip-test")
        store.clearAll()
        try {
            val cache = PreferenceJsonCache(store)
            cache.put("metadata", Metadata(id = "video", title = "A title", author = "Author"), 60_000)
            cache.put("stream", Stream().apply { formats = listOf(Format(url = "https://example.org/video", height = 720)) }, 60_000)
            cache.put("chapters", ChapterList(listOf(Chapter("Introduction", 0))), 60_000)
            cache.put("progress", ResumePoint(12_345), 60_000)
            cache.put("string", "{\"value\":true}", 60_000)
            val restored = PreferenceJsonCache(PreferenceStore.of(context, "cache-roundtrip-test"))
            assertEquals("A title", restored.get("metadata", Metadata::class.java)?.title)
            assertEquals(720, restored.get("stream", Stream::class.java)?.formats?.single()?.height)
            assertEquals("Introduction", restored.get("chapters", ChapterList::class.java)?.chapters?.single()?.title)
            assertEquals(12_345L, restored.get("progress", ResumePoint::class.java)?.positionMs)
            assertEquals("{\"value\":true}", restored.get("string", String::class.java))
            store.putString("corrupt", "not-json")
            assertNull(restored.get("corrupt", Metadata::class.java))
            assertFalse(store.contains("corrupt"))
            store.putString("wrong-shape", "{\"until\":${System.currentTimeMillis() + 60_000},\"obj\":{\"positionMs\":\"invalid\"}}")
            assertNull(restored.get("wrong-shape", ResumePoint::class.java))
            assertFalse(store.contains("wrong-shape"))
            cache.put("expired", "gone", -1)
            assertNull(restored.get("expired", String::class.java))
        } finally { store.clearAll() }
    }

    @Test fun concurrentCredentialEncryptionKeepsEveryRecordReadable() {
        val pool = Executors.newFixedThreadPool(4)
        try {
            val records = (0 until 16).map { index ->
                pool.submit<Pair<String, String>> {
                    val plain = "credential-$index"
                    plain to CredentialCipher.encrypt(plain)
                }
            }.map { it.get(15, TimeUnit.SECONDS) }
            records.forEach { (plain, sealed) ->
                assertFalse(sealed.contains(plain))
                assertEquals(plain, CredentialCipher.decrypt(sealed))
            }
            assertNull(CredentialCipher.decrypt("v1:AQ=="))
            assertNull(CredentialCipher.decrypt(records.first().second.dropLast(8) + "AAAAAAAA"))
            assertNull(CredentialCipher.decrypt("plain-text"))
        } finally { pool.shutdownNow() }
    }
}
