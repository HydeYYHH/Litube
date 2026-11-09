package com.hhst.youtubelite.downloader.engine

import com.hhst.youtubelite.downloader.core.*
import com.hhst.youtubelite.downloader.data.InMemoryDownloadRepository
import com.hhst.youtubelite.downloader.io.DownloadDirectories
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DownloadEngineGenerationTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun aLateFailureCannotFailTheResumedGeneration() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val env = environment(resolve = {
            entered.complete(Unit)
            finish.await()
            throw IOException("old execution failed")
        })
        val job = launch { env.engine.runTransfer(env.id) }
        withTimeout(5_000) {
            entered.await()
            env.pauseAndResume()
            val resumed = env.task()
            finish.complete(Unit)
            job.join()
            assertEquals(resumed, env.task())
        }
    }

    @Test fun aLateResolveCannotStartTransferForTheResumedGeneration() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var transfers = 0
        val env = environment(resolve = { id ->
            entered.complete(Unit)
            finish.await()
            DownloadResolveOutcome.Ready(id, 0)
        }, transfer = { transfers++; TransferResult.Completed })
        val job = launch { env.engine.runTransfer(env.id) }
        withTimeout(5_000) {
            entered.await()
            env.pauseAndResume()
            val resumed = env.task()
            finish.complete(Unit)
            job.join()
            assertEquals(0, transfers)
            assertEquals(resumed, env.task())
        }
    }

    @Test fun aLateTransferCannotAdvanceTheResumedGenerationToFinalization() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val env = environment(transfer = {
            entered.complete(Unit)
            finish.await()
            TransferResult.Completed
        })
        val job = launch { env.engine.runTransfer(env.id) }
        withTimeout(5_000) {
            entered.await()
            env.pauseAndResume()
            val resumed = env.task()
            assertEquals(DownloadPhase.TRANSFER, resumed.phase)
            finish.complete(Unit)
            job.join()
            assertEquals(resumed, env.task())
        }
    }

    @Test fun anExceptionStillFailsItsOwnGeneration() = runBlocking {
        val env = environment(resolve = { throw IOException("current execution failed") })
        env.engine.runTransfer(env.id)
        assertEquals(DownloadStatus.FAILED, env.task().status)
        assertEquals("current execution failed", env.task().errorMessage)
    }

    @Test fun aNewExecutionCanTransferAfterResume() = runBlocking {
        var transfers = 0
        val generations = mutableListOf<Pair<String, Long>>()
        val env = environment(transfer = { transfers++; TransferResult.Completed }, generations = generations)
        env.pauseAndResume()
        val admitted = env.task().executionGeneration
        assertTrue(admitted > 0)
        env.engine.runTransfer(env.id)
        assertEquals(1, transfers)
        assertEquals(listOf("resolve" to admitted, "sources" to admitted, "transfer" to admitted), generations)
        assertEquals(DownloadPhase.WAITING_PROCESS, env.task().phase)
        assertEquals(DownloadStatus.QUEUED, env.task().status)
    }

    private suspend fun environment(
        resolve: (suspend (String) -> DownloadResolveOutcome)? = null,
        transfer: suspend () -> TransferResult = { TransferResult.Completed },
        generations: MutableList<Pair<String, Long>> = mutableListOf(),
    ): Environment {
        val repository = InMemoryDownloadRepository()
        val transport = object : DownloadTransport {
            override fun cancelInFlight(taskId: String) = Unit
            override suspend fun downloadComponent(
                taskId: String,
                generation: Long,
                component: InputComponent,
                source: DownloadComponentSource,
                dest: File,
                verified: List<DownloadChunk>,
                onProgress: suspend (DownloadChunk, Long?) -> Boolean,
                onChunk: suspend (DownloadChunk) -> Boolean,
            ): TransferResult {
                generations += "transfer" to generation
                return transfer()
            }
        }
        val coordinator = DownloadCoordinator(repository, transport = transport)
        val id = coordinator.enqueue(request("generation"), "generation").created.single().taskId
        val resolver = object : DownloadResolver {
            override suspend fun resolve(taskId: String, generation: Long): DownloadResolveOutcome {
                generations += "resolve" to generation
                return resolve?.invoke(taskId) ?: DownloadResolveOutcome.Ready(taskId, generation)
            }
            override fun sourcesOf(taskId: String, generation: Long): List<DownloadComponentSource> {
                generations += "sources" to generation
                return listOf(DownloadComponentSource(
                    AssetKind.VIDEO, InputComponentKind.VIDEO, "https://example.test/video",
                ))
            }
        }
        return Environment(repository, coordinator, id, DownloadEngine(
            coordinator, repository, resolver, transport, NoOpFinalizer, NoOpPublisher,
            DownloadDirectories(folder.newFolder()),
        ))
    }

    private class Environment(
        val repository: InMemoryDownloadRepository,
        val coordinator: DownloadCoordinator,
        val id: String,
        val engine: DownloadEngine,
    ) {
        suspend fun task() = repository.transact { getTask(id) }!!
        suspend fun pauseAndResume() {
            coordinator.pause(DownloadTarget.Task(id))
            coordinator.resume(DownloadTarget.Task(id))
        }
    }
}
