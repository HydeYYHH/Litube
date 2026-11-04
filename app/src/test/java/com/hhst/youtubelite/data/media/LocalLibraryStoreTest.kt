package com.hhst.youtubelite.data.media

import com.hhst.youtubelite.library.domain.HistoryEntry
import org.junit.Assert.*
import org.junit.Test

class LocalLibraryStoreTest {
    @Test fun anInFlightHistoryWriteCannotOverwriteANewerCollectionEdit() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val first = java.util.concurrent.atomic.AtomicBoolean(true)
        val backing = MemoryAuthStore()
        val storage = object : AuthStore {
            override fun read(key: String) = backing.read(key)
            override fun write(key: String, value: String?) {
                if (first.compareAndSet(true, false)) {
                    entered.countDown()
                    check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                }
                backing.write(key, value)
            }
        }
        val writer = java.util.concurrent.Executors.newSingleThreadExecutor()
        val edits = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val library = LocalLibraryStore(storage, writer = writer)
            library.record(video("one"))
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val edit = edits.submit<com.hhst.youtubelite.saves.domain.SaveChoice> {
                library.create("Films", "", video("two"))
            }
            release.countDown()
            val collection = edit.get(5, java.util.concurrent.TimeUnit.SECONDS)
            writer.submit {}.get(5, java.util.concurrent.TimeUnit.SECONDS)
            val restored = LocalLibraryStore(storage)
            assertNotNull(restored.playlist(collection.id))
            assertEquals("one", restored.history().entries.single().videoId)
        } finally {
            release.countDown()
            writer.shutdownNow()
            edits.shutdownNow()
        }
    }

    @Test fun aDelayedSerializationCannotWriteAnObsoleteRevision() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val first = java.util.concurrent.atomic.AtomicBoolean(true)
        val storage = MemoryAuthStore()
        val writer = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val library = LocalLibraryStore(storage, writer)
            library.writeInterceptor = { text ->
                if (first.compareAndSet(true, false)) {
                    entered.countDown()
                    check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                }
                text
            }
            library.record(video("one"))
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val collection = library.create("Films", "", video("two"))
            release.countDown()
            writer.submit {}.get(5, java.util.concurrent.TimeUnit.SECONDS)
            assertNotNull(LocalLibraryStore(storage).playlist(collection.id))
        } finally {
            release.countDown()
            writer.shutdownNow()
        }
    }

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

    @Test fun aFailedDeferredWriteKeepsTheDirtyRevisionForTheNextFlush() {
        val inner = MemoryAuthStore()
        val failures = java.util.concurrent.atomic.AtomicInteger(1)
        val storage = object : AuthStore {
            override fun read(key: String) = inner.read(key)
            override fun write(key: String, value: String?) {
                if (failures.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) throw java.io.IOException("disk full")
                inner.write(key, value)
            }
        }
        val library = LocalLibraryStore(storage)
        library.record(video("one"))
        library.record(video("two"))
        val stored = inner.read("device-library:data")!!
        assertTrue(stored.contains("\"one\""))
        assertTrue(stored.contains("\"two\""))
    }
    @Test fun corruptPayloadsKeepABoundedRecoveryCopy() {
        val raw = "{not json at all"
        val storage = MemoryAuthStore().apply { write("device-library:data", raw) }
        assertTrue(LocalLibraryStore(storage).history().entries.isEmpty())
        assertEquals(raw, storage.read("device-library:recovery"))
    }

    @Test fun removeReturnsAnchorRecordAndRestorePutsTheEntryBackBetweenItsNeighbours() {
        val library = LocalLibraryStore(MemoryAuthStore())
        listOf("a", "b", "c", "d").forEach { library.record(video(it)) }
        assertEquals(listOf("d", "c", "b", "a"), library.history().entries.map { it.videoId })
        val removed = library.remove("c")!!
        assertEquals("c", removed.entry.videoId)
        // Newest-first list: prevVideoId is the newer neighbour ("d"), nextVideoId the older ("b").
        assertEquals("d", removed.prevVideoId)
        assertEquals("b", removed.nextVideoId)
        assertEquals(1, removed.index)
        library.restore(removed)
        assertEquals(listOf("d", "c", "b", "a"), library.history().entries.map { it.videoId })
    }

    @Test fun restoreKeepsChangesTheUserMadeAfterTheRemoval() {
        val library = LocalLibraryStore(MemoryAuthStore())
        listOf("a", "b", "c").forEach { library.record(video(it)) }
        val removed = library.remove("b")!!
        library.record(video("new"))
        library.remove("a")
        library.restore(removed)
        assertEquals(listOf("new", "c", "b"), library.history().entries.map { it.videoId })
    }

    @Test fun restoreIsANoOpWhenTheVideoIsPresentAgain() {
        val library = LocalLibraryStore(MemoryAuthStore())
        listOf("a", "b").forEach { library.record(video(it)) }
        val removed = library.remove("b")!!
        library.record(video("b").copy(title = "Re-watched"))
        library.restore(removed)
        assertEquals(listOf("b", "a"), library.history().entries.map { it.videoId })
        assertEquals("Re-watched", library.history().entries.first { it.videoId == "b" }.title)
    }

    @Test fun restoreFallsBackToTheRecordedIndexWhenBothNeighboursAreGone() {
        val library = LocalLibraryStore(MemoryAuthStore())
        listOf("a", "b", "c").forEach { library.record(video(it)) }
        val removed = library.remove("b")!!
        library.remove("c")
        library.remove("a")
        library.restore(removed)
        assertEquals(listOf("b"), library.history().entries.map { it.videoId })
    }

    @Test fun removeOfUnknownVideoReturnsNullAndDoubleRestoreStaysIdempotent() {
        val library = LocalLibraryStore(MemoryAuthStore())
        library.record(video("a"))
        assertNull(library.remove("ghost"))
        val removed = library.remove("a")!!
        assertTrue(library.history().entries.isEmpty())
        library.restore(removed)
        library.restore(removed)
        assertEquals(listOf("a"), library.history().entries.map { it.videoId })
    }

    /** Rows persisted before removeToken existed deserialize with a null token; history() normalizes it so legacy rows stay removable. */
    @Test fun legacyEntriesWithoutRemoveTokenStayRemovable() {
        val storage = MemoryAuthStore()
        val legacyJson = """{"history":[
            {"id":"new","videoId":"new","title":"Fresh entry","author":"Author","channelId":"channel",
             "artwork":"image","duration":"1:00","section":"Today","removeToken":"new"},
            {"id":"old","videoId":"old","title":"Legacy entry","author":"Author","channelId":"channel",
             "artwork":"image","duration":"1:00","section":"Today"}],
            "collections":[],"paused":false}"""
        storage.write("device-library:data", legacyJson)
        val library = LocalLibraryStore(storage)
        val entries = library.history().entries.associateBy { it.videoId }
        assertEquals("old", entries.getValue("old").removeToken)
        assertEquals("new", entries.getValue("new").removeToken)
        val removed = library.remove("old")!!
        assertEquals("new", removed.prevVideoId)
        assertTrue(library.history().entries.map { it.videoId }.contains("new"))
        library.restore(removed)
        assertEquals(listOf("new", "old"), library.history().entries.map { it.videoId })
    }
}
