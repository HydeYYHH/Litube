package com.hhst.youtubelite.data.media

import com.hhst.youtubelite.library.domain.HistoryEntry
import org.junit.Assert.*
import org.junit.Test

class LocalLibraryStoreTest {
    private fun video(id: String) = HistoryEntry(id, id, "Video $id", "Author", "channel", "image", "1:00", section = "On this device")
    @Test fun historyAndCollectionsPersistIndependentlyAndRemovalDoesNotDeleteSavedVideos() {
        val storage = MemoryAuthStore()
        val first = LocalLibraryStore(storage)
        first.record(video("one"))
        first.record(video("two"))
        first.record(video("one").copy(progress = .5f))
        val list = first.create("My list", "Description", video("one"))
        first.remove("one")
        val restored = LocalLibraryStore(storage)
        assertEquals(listOf("two"), restored.history().entries.map { it.videoId })
        assertEquals("My list", restored.playlist(list.id)!!.summary.title)
        assertEquals("one", restored.playlist(list.id)!!.entries.single().videoId)
        assertTrue(restored.choices("one").single().contains)
        restored.setContaining(list.id, video("one"), false)
        assertTrue(restored.playlist(list.id)!!.entries.isEmpty())
    }
    @Test fun pauseAndClearArePersistentAndBounded() {
        val storage = MemoryAuthStore()
        val library = LocalLibraryStore(storage)
        repeat(502) { library.record(video("v$it")) }
        assertEquals(500, library.history().entries.size)
        library.pause(true)
        LocalLibraryStore(storage).record(video("ignored"))
        assertFalse(LocalLibraryStore(storage).history().entries.any { it.videoId == "ignored" })
        library.clear()
        assertTrue(LocalLibraryStore(storage).history().entries.isEmpty())
    }
    @Test fun localCollectionsSurviveAccountStoreChanges() {
        val storage = MemoryAuthStore()
        val library = LocalLibraryStore(storage)
        library.setContaining(LocalLibraryStore.WATCH_LATER, video("one"), true)
        storage.write("auth:refresh_token", "account")
        storage.write("auth:refresh_token", null)
        assertEquals("one", LocalLibraryStore(storage).playlist(LocalLibraryStore.WATCH_LATER)!!.entries.single().videoId)
    }
    @Test fun corruptedDataDoesNotCrashTheLibrary() {
        val storage = MemoryAuthStore().apply { write("device-library:data", "{\"history\":null}") }
        assertTrue(LocalLibraryStore(storage).history().entries.isEmpty())
    }
    @Test fun collectionLimitDoesNotCreateUnreadableStoredData() {
        val storage = MemoryAuthStore()
        val library = LocalLibraryStore(storage)
        repeat(100) { library.create("List $it", "", video("one")) }
        assertThrows(IllegalStateException::class.java) {
            library.setContaining(LocalLibraryStore.WATCH_LATER, video("two"), true)
        }
        assertEquals(100, LocalLibraryStore(storage).playlists().playlists.size)
    }
    @Test fun burstsOfWritesCoalesceIntoASingleLatestStateFlush() {
        val writes = java.util.concurrent.atomic.AtomicInteger()
        val inner = MemoryAuthStore()
        val storage = object : AuthStore {
            override fun read(key: String) = inner.read(key)
            override fun write(key: String, value: String?) {
                writes.incrementAndGet()
                inner.write(key, value)
            }
        }
        val queued = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()
        val library = LocalLibraryStore(storage, writer = java.util.concurrent.Executor { queued.add(it) })
        library.record(video("one"))
        library.record(video("two"))
        // Nothing is marshalled or persisted while the writer is idle; memory stays current.
        assertEquals(0, writes.get())
        assertEquals(listOf("two", "one"), library.history().entries.map { it.videoId })
        // The first flush persists the latest pending state; the queued remainder becomes a no-op.
        queued.poll()!!.run()
        assertEquals(1, writes.get())
        assertEquals(listOf("two", "one"), LocalLibraryStore(inner).history().entries.map { it.videoId })
        queued.poll()!!.run()
        assertEquals(1, writes.get())
    }

    @Test fun explicitEditsPropagateWriteFailureWithoutChangingMemory() {
        val storage = object : AuthStore {
            override fun read(key: String): String? = null
            override fun write(key: String, value: String?) { throw java.io.IOException("disk_full") }
        }
        val library = LocalLibraryStore(storage)
        assertThrows(java.io.IOException::class.java) { library.create("Films", "", video("one")) }
        assertTrue(library.playlists().playlists.isEmpty())
        assertThrows(java.io.IOException::class.java) { library.pause(true) }
        assertFalse(library.paused())
    }

    @Test fun queuedHistoryCannotOverwriteAnExplicitCollectionEdit() {
        val storage = MemoryAuthStore()
        val queued = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()
        val library = LocalLibraryStore(storage, writer = java.util.concurrent.Executor { queued.add(it) })
        library.record(video("one"))
        library.create("Films", "", video("two"))
        queued.forEach(Runnable::run)
        val restored = LocalLibraryStore(storage)
        assertEquals("Films", restored.playlists().playlists.single().title)
        assertEquals("one", restored.history().entries.single().videoId)
    }
}
