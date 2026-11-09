package com.hhst.youtubelite.downloader.core

import com.hhst.youtubelite.downloader.data.InMemoryDownloadRepository
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test
import java.util.concurrent.Executors

class DownloadCoordinatorDispatchTest {
    @Test
    fun schedulingAndCancellationRunOffTheCallingThread() {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { caller ->
            runBlocking(caller) {
                val callingThread = Thread.currentThread()
                val scheduled = mutableListOf<Thread>()
                val cancelled = mutableListOf<Thread>()
                val scheduler = object : DownloadScheduler {
                    override suspend fun schedule(taskId: String) {
                        scheduled += Thread.currentThread()
                    }
                    override suspend fun cancel(taskId: String) {
                        cancelled += Thread.currentThread()
                    }
                }
                val coordinator = DownloadCoordinator(InMemoryDownloadRepository(), scheduler = scheduler)
                val task = coordinator.enqueue(request("dispatch"), "dispatch").created.single().taskId
                coordinator.pause(DownloadTarget.Task(task))
                assertEquals(1, scheduled.size)
                assertEquals(1, cancelled.size)
                (scheduled + cancelled).forEach { assertNotSame(callingThread, it) }
            }
        }
    }
}
