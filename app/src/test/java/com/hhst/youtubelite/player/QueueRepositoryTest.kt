package com.hhst.youtubelite.player

import com.hhst.youtubelite.core.JsonCache
import com.hhst.youtubelite.extractor.VideoId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueRepositoryTest {

    /** Real video ids are 11 chars; pad so [VideoId.parse] accepts fixtures. */
    private fun vid(raw: String) = raw.padEnd(11, 'x').take(11)

    private fun item(id: String): QueueItem {
        val videoId = vid(id)
        return QueueItem(
            videoId = videoId,
            url = "https://m.youtube.com/watch?v=$videoId",
            title = "T$id",
        )
    }

    @Test
    fun add_capsAtMaxItems() {
        val repo = QueueRepository(MemJsonCache())
        repeat(60) { repo.add(item("v$it")) }
        assertEquals(50, repo.state.value.items.size)
        assertEquals(vid("v10"), repo.state.value.items.first().videoId)
        assertEquals(vid("v59"), repo.state.value.items.last().videoId)
    }

    @Test
    fun add_canonicalizesUrlAndThumbnail() {
        val repo = QueueRepository(MemJsonCache())
        repo.add(
            QueueItem(
                videoId = "",
                url = "https://evil.example/watch?v=dQw4w9WgXcQ",
                title = "  Hello  ",
                thumbnailUrl = "https://evil.example/huge.bmp",
            ),
        )
        val queued = repo.state.value.items.single()
        assertEquals("dQw4w9WgXcQ", queued.videoId)
        assertEquals(VideoId.watchUrl("dQw4w9WgXcQ"), queued.url)
        assertEquals("Hello", queued.title)
        assertEquals(VideoId.thumbnailUrl("dQw4w9WgXcQ"), queued.thumbnailUrl)
    }

    @Test
    fun add_rejectsUnparseable() {
        val repo = QueueRepository(MemJsonCache())
        repo.add(QueueItem(videoId = "nope", url = "https://example.com", title = "x"))
        assertTrue(repo.state.value.items.isEmpty())
    }

    @Test
    fun add_remove_persisted() {
        val repo = QueueRepository(MemJsonCache())
        repo.add(item("a"))
        repo.add(item("b"))
        assertEquals(2, repo.state.value.items.size)
        repo.remove(vid("a"))
        assertEquals(listOf(vid("b")), repo.state.value.items.map { it.videoId })
    }

    @Test
    fun nextStrict_stopsAtTail_previous_nullAtHead() {
        val repo = QueueRepository(MemJsonCache())
        repo.add(item("a")); repo.add(item("b")); repo.add(item("c"))
        assertEquals(vid("b"), repo.nextStrict(vid("a"))?.videoId)
        // Tail: navigation stops instead of looping back to the head.
        assertNull(repo.nextStrict(vid("c")))
        assertNull(repo.previous(vid("a")))
        assertEquals(vid("a"), repo.previous(vid("b"))?.videoId)
    }

    @Test
    fun random_excludesCurrent() {
        val repo = QueueRepository(MemJsonCache())
        repo.add(item("a")); repo.add(item("b"))
        repeat(20) {
            assertTrue(repo.random(vid("a"))?.videoId == vid("b"))
        }
    }

    @Test
    fun move_reorders() {
        val repo = QueueRepository(MemJsonCache())
        repo.add(item("a")); repo.add(item("b")); repo.add(item("c"))
        repo.move(0, 2)
        assertEquals(listOf(vid("b"), vid("c"), vid("a")), repo.state.value.items.map { it.videoId })
        repo.move(5, 0) // out of bounds: no-op
        assertEquals(listOf(vid("b"), vid("c"), vid("a")), repo.state.value.items.map { it.videoId })
    }

    @Test
    fun nextStrict_nullWhenSingleItem_firstWhenCurrentMissing() {
        val repo = QueueRepository(MemJsonCache())
        repo.add(item("a"))
        assertNull(repo.nextStrict(vid("a")))
        assertEquals(vid("a"), repo.nextStrict("other")?.videoId)
        repo.add(item("b"))
        assertEquals(vid("b"), repo.nextStrict(vid("a"))?.videoId)
        assertNull(repo.nextStrict(vid("b")))
    }

    @Test
    fun upsertPlaying_mergesAndCaps() {
        val repo = QueueRepository(MemJsonCache())
        val id = vid("a")
        repo.upsertPlaying(QueueItem(videoId = id, url = "not-a-url", title = ""))
        repo.upsertPlaying(QueueItem(videoId = id, url = "not-a-url", title = "Alpha", author = "Ch"))
        repo.upsertPlaying(QueueItem(videoId = id, url = "not-a-url", title = ""))
        assertEquals(1, repo.state.value.items.size)
        assertEquals("Alpha", repo.state.value.items[0].title)
        assertEquals("Ch", repo.state.value.items[0].author)
        assertEquals(VideoId.watchUrl(id), repo.state.value.items[0].url)
    }

    @Test
    fun upsertPlaying_capsAtMaxItems() {
        val repo = QueueRepository(MemJsonCache())
        repeat(60) { repo.upsertPlaying(item("v$it")) }
        assertEquals(50, repo.state.value.items.size)
        assertEquals(vid("v10"), repo.state.value.items.first().videoId)
        assertEquals(vid("v59"), repo.state.value.items.last().videoId)
    }

    @Test
    fun addNext_insertsDirectlyAfterThePlayingItem() {
        val repo = QueueRepository(MemJsonCache())
        repo.add(item("a")); repo.add(item("b")); repo.add(item("c"))
        repo.upsertPlaying(item("b"))
        repo.addNext(item("n"))
        assertEquals(listOf(vid("a"), vid("b"), vid("n"), vid("c")),
            repo.state.value.items.map { it.videoId })
    }

    @Test
    fun addNext_movesAnAlreadyQueuedItemInsteadOfDuplicating() {
        val repo = QueueRepository(MemJsonCache())
        repo.add(item("a")); repo.add(item("b")); repo.add(item("c"))
        repo.upsertPlaying(item("a"))
        repo.addNext(item("c"))
        val ids = repo.state.value.items.map { it.videoId }
        assertEquals(listOf(vid("a"), vid("c"), vid("b")), ids)
        assertEquals(3, ids.distinct().size)
    }

    @Test
    fun addNext_keepsThePlayingItemAndNeverDuplicatesIt() {
        val repo = QueueRepository(MemJsonCache())
        repo.add(item("a")); repo.add(item("b"))
        repo.upsertPlaying(item("a"))
        repo.addNext(item("a"))
        assertEquals(listOf(vid("a"), vid("b")), repo.state.value.items.map { it.videoId })
    }

    @Test
    fun addNext_withoutAQueuedPlayingItem_landsAtTheHead() {
        val repo = QueueRepository(MemJsonCache())
        repo.add(item("a")); repo.add(item("b"))
        repo.addNext(item("n"))
        assertEquals(vid("n"), repo.state.value.items.first().videoId)
    }

    @Test
    fun addNext_isCapacityBoundedAndKeepsThePlayingItem() {
        val repo = QueueRepository(MemJsonCache())
        val playing = item("play")
        repo.upsertPlaying(playing)
        repeat(60) { repo.addNext(item("v$it")) }
        assertEquals(50, repo.state.value.items.size)
        assertTrue(repo.state.value.items.any { it.videoId == playing.videoId })
        // The most recent "play next" target sits right after the playing item.
        assertEquals(playing.videoId, repo.state.value.items.first().videoId)
        assertEquals(vid("v59"), repo.state.value.items[1].videoId)
    }

    @Test
    fun add_stillAppendsAtTheTailAfterAddNext() {
        val repo = QueueRepository(MemJsonCache())
        repo.add(item("a")); repo.add(item("b"))
        repo.upsertPlaying(item("a"))
        repo.addNext(item("n"))
        repo.add(item("tail"))
        assertEquals(listOf(vid("a"), vid("n"), vid("b"), vid("tail")),
            repo.state.value.items.map { it.videoId })
    }

    @Test
    fun add_doesNotEvictPlayingItem() {
        val repo = QueueRepository(MemJsonCache())
        val playing = item("play")
        repo.upsertPlaying(playing)
        repeat(60) { repo.add(item("v$it")) }
        assertTrue(repo.state.value.items.any { it.videoId == playing.videoId })
        assertEquals(50, repo.state.value.items.size)
    }

    @Test
    fun stateRestored_fromCache() {
        val cache = MemJsonCache()
        QueueRepository(cache).apply { add(item("a")); setEnabled(true) }
        val restored = QueueRepository(cache)
        assertTrue(restored.state.value.enabled)
        assertEquals(listOf(vid("a")), restored.state.value.items.map { it.videoId })
    }

    @Test
    fun load_discardsNullEntriesAndNormalizesNullFields() {
        val cache = MemJsonCache()
        cache.put("queue:items", """[null,{"url":"https://youtu.be/dQw4w9WgXcQ","title":null},{"url":null,"videoId":null}]""", 0)

        val items = QueueRepository(cache).state.value.items
        assertEquals(1, items.size)
        assertEquals("dQw4w9WgXcQ", items.single().videoId)
        assertEquals("dQw4w9WgXcQ", items.single().title)
    }

    @Test
    fun load_deduplicatesPersistedVideoIds() {
        val cache = MemJsonCache()
        cache.put("queue:items", """[{"videoId":"dQw4w9WgXcQ","title":"First"},{"url":"https://youtu.be/dQw4w9WgXcQ","title":"Duplicate"}]""", 0)
        val items = QueueRepository(cache).state.value.items
        assertEquals(1, items.size)
        assertEquals("First", items.single().title)
    }

    @Test
    fun load_keepsEnabledWhenItemsKeyMissing() {
        val cache = MemJsonCache()
        cache.put("queue:enabled", "true", 0)
        val repo = QueueRepository(cache)
        assertTrue(repo.state.value.enabled)
        assertTrue(repo.state.value.items.isEmpty())
    }
}

/** In-memory JsonCache shared with PlayerViewModelTest (same package). */
class MemJsonCache : JsonCache {
    private val map = mutableMapOf<String, Any>()

    @Suppress("UNCHECKED_CAST")
    override fun <T> get(key: String, type: Class<T>): T? = map[key] as? T

    override fun put(key: String, value: Any, ttlMs: Long) { map[key] = value }

    override fun invalidate(key: String) { map.remove(key) }
}
