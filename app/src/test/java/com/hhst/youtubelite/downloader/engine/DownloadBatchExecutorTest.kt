package com.hhst.youtubelite.downloader.engine

import com.hhst.youtubelite.downloader.core.DownloadResolveOutcome
import com.hhst.youtubelite.downloader.core.DownloadResolver
import com.hhst.youtubelite.downloader.core.NoOpFinalizer
import com.hhst.youtubelite.downloader.core.NoOpPublisher
import com.hhst.youtubelite.downloader.core.NoOpTransport
import com.hhst.youtubelite.downloader.core.request
import com.hhst.youtubelite.downloader.io.DownloadDirectories
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DownloadBatchExecutorTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun duplicateTransferWaitsUntilTheFirstExecutionEnds() = runTest {
        val h = SchedulerHarness(33)
        val batch = h.wired.enqueue(request("a"), "single")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        var active = 0
        var peak = 0
        val resolver = object : DownloadResolver {
            override suspend fun resolve(taskId: String): DownloadResolveOutcome {
                calls++
                active++
                peak = maxOf(peak, active)
                try {
                    if (calls == 1) {
                        entered.complete(Unit)
                        release.await()
                    }
                    return DownloadResolveOutcome.Ignored
                } finally { active-- }
            }
        }
        val executor = executor(h, resolver)
        val first = launch { executor.executeTransfer(batch.batchId) }
        entered.await()
        val second = launch { executor.executeTransfer(batch.batchId) }
        runCurrent()
        assertEquals(1, calls)
        release.complete(Unit)
        first.join()
        second.join()
        assertEquals(1, peak)
        assertTrue(calls > 1)
    }

    @Test
    fun cancellingTheOwnerReleasesTheWaitingExecution() = runTest {
        val h = SchedulerHarness(33)
        val batch = h.wired.enqueue(request("a"), "cancel")
        val entered = CompletableDeferred<Unit>()
        var calls = 0
        val resolver = object : DownloadResolver {
            override suspend fun resolve(taskId: String): DownloadResolveOutcome {
                if (++calls == 1) {
                    entered.complete(Unit)
                    CompletableDeferred<Unit>().await()
                }
                return DownloadResolveOutcome.Ignored
            }
        }
        val executor = executor(h, resolver)
        val first = launch { executor.executeTransfer(batch.batchId) }
        entered.await()
        val second = launch { executor.executeTransfer(batch.batchId) }
        runCurrent()
        assertEquals(1, calls)
        first.cancelAndJoin()
        second.join()
        assertTrue(calls > 1)
    }

    private fun executor(h: SchedulerHarness, resolver: DownloadResolver) = DownloadBatchExecutor(
        DownloadEngine(h.wired, h.repo, resolver, NoOpTransport, NoOpFinalizer, NoOpPublisher,
            DownloadDirectories(folder.newFolder())), h.repo, h.wired, h.scheduler,
    )
}
