package com.hhst.youtubelite.data.media

import com.hhst.youtubelite.watch.domain.WatchComment
import com.hhst.youtubelite.watch.domain.WatchPage
import com.hhst.youtubelite.watch.domain.WatchRepository
import com.hhst.youtubelite.watch.domain.WatchVideo
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CachedWatchRepositoryTest {
    @Before fun setup() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun cleanup() { Dispatchers.resetMain() }

    private fun page(id: String, marker: String = "") = WatchPage(
        WatchVideo(id, "Title $id$marker", "Channel", "@handle", "ch-$id", "art", "1K", "100", "1 day ago", "3", WatchComment("a", "b", "c"), "desc"),
        emptyList(),
    )

    private class CountingWatch(val handler: suspend (String) -> WatchPage) : WatchRepository {
        val calls = mutableListOf<String>()
        override suspend fun watch(videoId: String): WatchPage {
            calls += videoId
            return handler(videoId)
        }
    }

    @Test fun reopeningAVideoServesTheCachedPage() = runTest {
        val delegate = CountingWatch { page(it) }
        val repo = CachedWatchRepository(delegate, identity = { 0L })
        val first = repo.watch("a")
        val second = repo.watch("a")
        assertEquals(listOf("a"), delegate.calls)
        assertTrue(first === second)
    }

    @Test fun concurrentReadersShareOneRequest() = runTest {
        val gate = CompletableDeferred<Unit>()
        val delegate = CountingWatch { gate.await(); page(it) }
        val repo = CachedWatchRepository(delegate, identity = { 0L })
        val first = async { repo.watch("a") }
        val second = async { repo.watch("a") }
        runCurrent()
        assertEquals(listOf("a"), delegate.calls)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(page("a"), first.await())
        assertEquals(page("a"), second.await())
        assertEquals(listOf("a"), delegate.calls)
    }

    @Test fun identitySwitchMissesEveryKey() = runTest {
        val identity = AtomicLong(0)
        val delegate = CountingWatch { page(it) }
        val repo = CachedWatchRepository(delegate, identity = { identity.get() })
        repo.watch("a")
        identity.set(1)
        repo.watch("a")
        assertEquals(listOf("a", "a"), delegate.calls)
    }

    @Test fun retryBypassesAndRefreshesTheCache() = runTest {
        var marker = 0
        val delegate = CountingWatch { page(it, "-${++marker}") }
        val repo = CachedWatchRepository(delegate, identity = { 0L })
        assertEquals("Title a-1", repo.watch("a").video.title)
        assertEquals("Title a-2", repo.watchFresh("a").video.title)
        assertEquals("Title a-2", repo.watch("a").video.title)
        assertEquals(listOf("a", "a"), delegate.calls)
    }

    @Test fun explicitRefreshReachesTheUnderlyingFreshRead() = runTest {
        val delegate = object : WatchRepository {
            override suspend fun watch(videoId: String) = page(videoId, "-cached")
            override suspend fun watchFresh(videoId: String) = page(videoId, "-fresh")
        }
        val repo = CachedWatchRepository(delegate, identity = { 0L })
        assertEquals("Title a-cached", repo.watch("a").video.title)
        assertEquals("Title a-fresh", repo.watchFresh("a").video.title)
        assertEquals("Title a-fresh", repo.watch("a").video.title)
    }

    @Test fun theCacheIsBounded() = runTest {
        val delegate = CountingWatch { page(it) }
        val repo = CachedWatchRepository(delegate, identity = { 0L }, maxEntries = 2)
        repo.watch("a")
        repo.watch("b")
        repo.watch("c")
        assertEquals(listOf("a", "b", "c"), delegate.calls)
        repo.watch("c")
        repo.watch("a")
        assertEquals(listOf("a", "b", "c", "a"), delegate.calls)
    }

    @Test fun failuresAreNotCached() = runTest {
        var fail = true
        val delegate = CountingWatch { if (fail) throw IOException("boom") else page(it) }
        val repo = CachedWatchRepository(delegate, identity = { 0L })
        try {
            repo.watch("a")
            error("must throw")
        } catch (_: IOException) {
        }
        fail = false
        assertEquals(page("a"), repo.watch("a"))
        assertEquals(listOf("a", "a"), delegate.calls)
    }

    @Test fun cancelledOwnerDoesNotPoisonLaterReads() = runTest {
        val gate = CompletableDeferred<Unit>()
        var first = true
        val delegate = CountingWatch { if (first) { first = false; gate.await() }; page(it) }
        val repo = CachedWatchRepository(delegate, identity = { 0L })
        val pending = async { repo.watch("a") }
        runCurrent()
        pending.cancelAndJoin()
        assertEquals(page("a"), repo.watch("a"))
        assertEquals(2, delegate.calls.size)
    }

    @Test fun refreshCannotCacheAPreviousIdentityUnderTheNewOne() = runTest {
        val identity = AtomicLong(0)
        val gate = CompletableDeferred<Unit>()
        var first = true
        val delegate = CountingWatch {
            if (first) { first = false; gate.await(); page(it, "-old") } else page(it, "-new")
        }
        val repo = CachedWatchRepository(delegate, identity = identity::get)
        val pending = async { runCatching { repo.watchFresh("a") } }
        runCurrent()
        identity.incrementAndGet()
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(pending.await().exceptionOrNull() is CancellationException)
        assertEquals("Title a-new", repo.watch("a").video.title)
    }

    @Test fun olderReadCannotOverwriteAnExplicitRefresh() = runTest {
        val gate = CompletableDeferred<Unit>()
        var first = true
        val delegate = CountingWatch {
            if (first) { first = false; gate.await(); page(it, "-old") } else page(it, "-fresh")
        }
        val repo = CachedWatchRepository(delegate, identity = { 0L })
        val pending = async { repo.watch("a") }
        runCurrent()
        assertEquals("Title a-fresh", repo.watchFresh("a").video.title)
        gate.complete(Unit)
        pending.await()
        assertEquals("Title a-fresh", repo.watch("a").video.title)
    }

    @Test fun clearingMemoryStartsANewReadWithoutCancellingExistingReaders() = runTest {
        val gate = CompletableDeferred<Unit>()
        var first = true
        val delegate = CountingWatch {
            if (first) { first = false; gate.await(); page(it, "-old") } else page(it, "-new")
        }
        val repo = CachedWatchRepository(delegate, identity = { 0L })
        val owner = async { repo.watch("a") }
        val waiter = async { repo.watch("a") }
        runCurrent()
        repo.clearMemory()
        assertEquals("Title a-new", repo.watch("a").video.title)
        gate.complete(Unit)
        assertEquals("Title a-old", owner.await().video.title)
        assertEquals("Title a-old", waiter.await().video.title)
        assertEquals("Title a-new", repo.watch("a").video.title)
        assertEquals(2, delegate.calls.size)
        repo.clearMemory()
        repo.watch("a")
        assertEquals(3, delegate.calls.size)
    }

    @Test fun expiredPagesAreReadAgain() = runTest {
        var now = 0L
        val delegate = CountingWatch { page(it) }
        val repo = CachedWatchRepository(delegate, identity = { 0L }, maxAgeMs = 100, clock = { now })
        repo.watch("a")
        now = 99
        repo.watch("a")
        assertEquals(1, delegate.calls.size)
        now = 100
        repo.watch("a")
        assertEquals(2, delegate.calls.size)
    }

    @Test fun continuationsPassThroughUncached() = runTest {
        val delegate = CountingWatch { page(it) }
        var nextCalls = 0
        val counting = object : WatchRepository by delegate {
            override suspend fun next(videoId: String, cursor: String) =
                delegate.next(videoId, cursor).also { nextCalls++ }
        }
        val repo = CachedWatchRepository(counting, identity = { 0L })
        repo.next("a", "k1")
        repo.next("a", "k1")
        assertEquals(2, nextCalls)
    }
    @Test fun cancellingTheOriginalReaderDoesNotStrandAnotherReader() = runTest {
        val gate = CompletableDeferred<Unit>()
        var first = true
        val delegate = CountingWatch { if (first) { first = false; gate.await() }; page(it) }
        val repo = CachedWatchRepository(delegate, identity = { 0L })
        val owner = async { repo.watch("a") }
        runCurrent()
        val waiter = async { runCatching { repo.watch("a") } }
        runCurrent()
        owner.cancelAndJoin()
        advanceUntilIdle()
        assertEquals(page("a"), waiter.await().getOrThrow())
        assertEquals(2, delegate.calls.size)
    }

}
