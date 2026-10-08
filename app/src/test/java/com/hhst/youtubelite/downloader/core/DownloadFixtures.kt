package com.hhst.youtubelite.downloader.core

import com.hhst.youtubelite.downloader.data.InMemoryDownloadRepository

internal class SeqIdFactory : IdFactory {
    private val counts = mutableMapOf<String, Int>()
    override fun next(kind: String): String {
        val n = (counts[kind] ?: 0) + 1
        counts[kind] = n
        return "$kind-$n"
    }
}

internal class FakeTransport : DownloadTransport {
    val cancelled = mutableListOf<String>()
    override fun cancelInFlight(taskId: String) {
        cancelled += taskId
    }
}

internal class FakeScheduler : DownloadScheduler {
    val scheduled = mutableListOf<String>()
    val cancelled = mutableListOf<String>()
    override suspend fun schedule(taskId: String) {
        scheduled += taskId
    }
    override suspend fun cancel(taskId: String) {
        cancelled += taskId
    }
}

internal class FakePublisher : DownloadPublisher {
    val deleted = mutableListOf<String>()
    var failDelete: Boolean = false
    var failReason: String = "delete-failed"
    override fun delete(uri: String): DeleteResult {
        deleted += uri
        return if (failDelete) DeleteResult.fail(failReason) else DeleteResult.OK
    }
    override fun exists(uri: String): Boolean = uri !in deleted
}

internal class DownloadHarness {
    val repo = InMemoryDownloadRepository()
    val transport = FakeTransport()
    val scheduler = FakeScheduler()
    val publisher = FakePublisher()
    val coordinator = DownloadCoordinator(
        repository = repo,
        transport = transport,
        scheduler = scheduler,
        publisher = publisher,
        ids = SeqIdFactory(),
        clock = { 1_000L },
    )
}

internal fun vid(raw: String): String = raw.padEnd(11, 'x').take(11)

internal fun request(
    id: String,
    title: String = "T$id",
    config: DownloadConfig = DownloadConfig(),
): DownloadRequest = DownloadRequest(
    videoId = vid(id),
    title = title,
    author = "Author",
    thumbnailUrl = "https://i.ytimg.com/vi/${vid(id)}/hqdefault.jpg",
    config = config,
)
