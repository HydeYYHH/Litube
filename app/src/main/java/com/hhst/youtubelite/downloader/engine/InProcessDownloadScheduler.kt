package com.hhst.youtubelite.downloader.engine

import com.hhst.youtubelite.downloader.core.DownloadScheduler
import com.hhst.youtubelite.downloader.core.DownloadSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

/**
 * In-process runner kept for hermetic JVM tests. Production uses
 * [com.hhst.youtubelite.downloader.work.BackgroundDownloadScheduler].
 */
class InProcessDownloadScheduler(
    private val scope: CoroutineScope,
    private val engine: () -> DownloadEngine,
    maxConcurrent: Int = DownloadSettings.MAX_CONCURRENT_ITEMS,
) : DownloadScheduler {
    private val jobs = ConcurrentHashMap<String, Job>()
    private val gate = Semaphore(maxConcurrent.coerceAtLeast(1))

    override suspend fun schedule(taskId: String) {
        jobs.remove(taskId)?.cancel()
        jobs[taskId] = scope.launch {
            try {
                gate.withPermit { engine().run(taskId) }
            } finally {
                jobs.remove(taskId, coroutineContext[Job])
            }
        }
    }

    override suspend fun cancel(taskId: String) {
        jobs.remove(taskId)?.cancel()
    }
}
