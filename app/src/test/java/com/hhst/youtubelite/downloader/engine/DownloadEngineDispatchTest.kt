package com.hhst.youtubelite.downloader.engine

import com.hhst.youtubelite.diagnostics.DiagnosticCoroutineContext
import com.hhst.youtubelite.downloader.core.*
import com.hhst.youtubelite.downloader.data.InMemoryDownloadRepository
import com.hhst.youtubelite.downloader.io.DownloadDirectories
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.Executors
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext

class DownloadEngineDispatchTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun pipelineLeavesTheCallingThreadAndRetainsDiagnostics() {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { caller ->
            runBlocking(caller) {
                val callingThread = Thread.currentThread()
                val repository = InMemoryDownloadRepository()
                val coordinator = DownloadCoordinator(repository)
                val id = coordinator.enqueue(request("dispatch"), "dispatch").created.single().taskId
                var executionThread: Thread? = null
                var executionContext: CoroutineContext? = null
                val resolver = object : DownloadResolver {
                    override suspend fun resolve(taskId: String, generation: Long): DownloadResolveOutcome {
                        executionThread = Thread.currentThread()
                        executionContext = currentCoroutineContext()
                        return DownloadResolveOutcome.Ignored
                    }
                }
                engine(repository, coordinator, resolver).runTransfer(id)
                val captured = checkNotNull(executionContext)
                assertNotSame(callingThread, executionThread)
                assertSame(Dispatchers.IO, captured[ContinuationInterceptor])
                assertEquals(id, captured[DiagnosticCoroutineContext]?.diagnostic?.taskId)
                assertSame(callingThread, Thread.currentThread())
            }
        }
    }

    @Test fun cancellationReachesThePipelineAcrossTheIoBoundary() = runBlocking {
        val repository = InMemoryDownloadRepository()
        val coordinator = DownloadCoordinator(repository)
        val id = coordinator.enqueue(request("cancel"), "cancel").created.single().taskId
        val entered = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val resolver = object : DownloadResolver {
            override suspend fun resolve(taskId: String, generation: Long): DownloadResolveOutcome {
                entered.complete(Unit)
                try { awaitCancellation() } finally { stopped.complete(Unit) }
            }
        }
        val job = launch { engine(repository, coordinator, resolver).runTransfer(id) }
        withTimeout(5_000) {
            entered.await()
            job.cancelAndJoin()
            stopped.await()
        }
        assertTrue(job.isCancelled)
        assertNotEquals(DownloadStatus.FAILED, repository.transact { getTask(id) }!!.status)
    }

    private fun engine(repository: InMemoryDownloadRepository, coordinator: DownloadCoordinator,
                       resolver: DownloadResolver) = DownloadEngine(
        coordinator, repository, resolver, NoOpTransport, NoOpFinalizer, NoOpPublisher,
        DownloadDirectories(folder.newFolder()),
    )
}
