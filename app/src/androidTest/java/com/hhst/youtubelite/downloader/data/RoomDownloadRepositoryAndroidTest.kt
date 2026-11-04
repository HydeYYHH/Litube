package com.hhst.youtubelite.downloader.data

import android.util.Log
import androidx.room.InvalidationTracker
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.hhst.youtubelite.downloader.android.SeqIdFactory
import com.hhst.youtubelite.downloader.android.request
import com.hhst.youtubelite.downloader.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

class RoomDownloadRepositoryAndroidTest {
    private lateinit var db: DownloaderDatabase
    private lateinit var repository: RoomDownloadRepository
    private val selects = AtomicInteger()

    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), DownloaderDatabase::class.java)
            .setQueryCallback({ sql, _ ->
                if (sql.trimStart().startsWith("SELECT", ignoreCase = true) && sql.contains("dl_"))
                    selects.incrementAndGet()
            }, Executor { it.run() }).build()
        repository = RoomDownloadRepository(db)
    }

    @After fun close() { db.close() }

    @Test fun bulkSnapshotsMatchTheInMemoryRepositoryAndRespectFilters() = runBlocking {
        val memory = InMemoryDownloadRepository()
        suspend fun seed(repo: DownloadRepository): String {
            val coordinator = coordinator(repo)
            val requests = (0 until 40).map { request("video-$it") }
            val batch = coordinator.enqueueBatch(BatchSnapshot(BatchSource.PLAYLIST, "Bulk", requests),
                BatchSelection(requests.indices.toSet()), "bulk")
            repo.transact {
                batch.created.forEachIndexed { index, ref ->
                    val task = getTask(ref.taskId)!!
                    updateTask(task.copy(
                        phase = if (index % 2 == 0) DownloadPhase.TRANSFER else DownloadPhase.RESOLVE,
                        status = if (index % 3 == 0) DownloadStatus.PAUSED else DownloadStatus.RUNNING,
                        removed = index == 0, createdAt = index.toLong(),
                    ))
                    val asset = assetsForTask(task.id).first()
                    val component = componentsForAsset(asset.id).first()
                    insertChunk(DownloadChunk("${component.id}:0", component.id, 0, 15, 16,
                        true, "/test/${component.id}", "checksum"))
                    updatePublish(checkNotNull(publishForAsset(asset.id)).copy(
                        tempFiles = listOf("/test/${component.id}"), targetUri = "content://downloads/$index"))
                }
            }
            return batch.batchId
        }
        val batchId = seed(repository)
        assertEquals(batchId, seed(memory))
        val filters = listOf(
            DownloadFilter(), DownloadFilter(includeRemoved = true),
            DownloadFilter(videoId = request("video-5").videoId),
            DownloadFilter(videoId = "missing"), DownloadFilter(phases = emptySet()),
            DownloadFilter(statuses = emptySet()),
            DownloadFilter(phases = setOf(DownloadPhase.TRANSFER)),
            DownloadFilter(statuses = setOf(DownloadStatus.PAUSED, DownloadStatus.RUNNING)),
            DownloadFilter(phases = setOf(DownloadPhase.RESOLVE), statuses = setOf(DownloadStatus.PAUSED)),
        )
        for (filter in filters) {
            val expected = memory.transact { downloads(filter) }
            selects.set(0)
            assertEquals("filter=$filter", expected, repository.transact { downloads(filter) })
            assertTrue("Filtered list used ${selects.get()} SELECTs", selects.get() <= 7)
        }
        val expectedBatch = memory.transact { batchView(batchId) }
        selects.set(0)
        assertEquals(expectedBatch, repository.transact { legacyBatchView(batchId) })
        val legacySelects = selects.get()
        selects.set(0)
        assertEquals(expectedBatch, repository.transact { batchView(batchId) })
        Log.i("DownloadRefresh", "40-item batch SELECTs: legacy=$legacySelects bulk=${selects.get()}")
        assertEquals(9, selects.get())
        assertTrue("Bulk loading must substantially reduce queries", legacySelects > selects.get() * 10)
        val taskId = expectedBatch!!.items.first().task.task.id
        assertEquals(memory.transact { snapshot(taskId) }, repository.transact { snapshot(taskId) })
    }

    @Test fun largeHistoriesStayBelowTheSqliteBindLimit() = runBlocking {
        val first = coordinator(repository).enqueue(request("large"), "large").created.single().taskId
        val template = db.downloads().getTask(first)!!
        repository.transact {
            repeat(1_000) { index ->
                insertTask(template.copy(id = "large-$index", videoId = "v$index").toDomain())
            }
        }
        selects.set(0)
        val snapshots = repository.transact { downloads(DownloadFilter()) }
        assertEquals(1_001, snapshots.size)
        assertEquals(13, selects.get())
    }

    @Test fun slowCollectorsDoNotAccumulateSnapshotQueriesAndReceiveTheLatestState() = runBlocking {
        val id = coordinator(repository).enqueue(request("observe"), "observe").created.single().taskId
        val template = db.downloads().getTask(id)!!
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val states = Channel<String>(Channel.UNLIMITED)
        val job = launch {
            repository.observeDownloads(DownloadFilter()).collect { snapshots ->
                if (!entered.isCompleted) { entered.complete(Unit); release.await() }
                states.send(snapshots.single().task.title)
            }
        }
        val invalidated = CompletableDeferred<Unit>()
        val observer = object : InvalidationTracker.Observer("dl_task") {
            override fun onInvalidated(tables: Set<String>) { invalidated.complete(Unit) }
        }
        try {
            withTimeout(10_000) {
                entered.await()
                db.invalidationTracker.addObserver(observer)
                selects.set(0)
                repeat(50) { index -> db.downloads().updateTask(template.copy(title = "update-$index")) }
                invalidated.await()
                assertEquals("A blocked collector must not queue snapshot reads", 0, selects.get())
                release.complete(Unit)
                while (states.receive() != "update-49") { /* wait for the final invalidation */ }
            }
        } finally {
            job.cancelAndJoin()
            db.invalidationTracker.removeObserver(observer)
            states.close()
        }
    }

    @Test fun batchObserversRefreshRemovedRowsAndTotals() = runBlocking {
        val requests = listOf(request("remove-one"), request("keep-two"))
        val batch = coordinator(repository).enqueueBatch(BatchSnapshot(BatchSource.PLAYLIST, "Removal", requests),
            BatchSelection(setOf(0, 1)), "removal")
        val states = Channel<BatchView>(Channel.UNLIMITED)
        val job = launch { repository.observeBatch(batch.batchId).collect { states.send(checkNotNull(it)) } }
        try {
            withTimeout(10_000) {
                assertEquals(2, states.receive().stats.total)
                repository.transact {
                    val task = getTask(batch.created.first().taskId)!!
                    updateTask(task.copy(removed = true))
                }
                var latest = states.receive()
                while (latest.stats.total != 1) latest = states.receive()
                assertEquals(listOf(requests.last().videoId), latest.items.map { it.item.videoId })
            }
        } finally { job.cancelAndJoin(); states.close() }
    }

    private fun coordinator(repo: DownloadRepository) = DownloadCoordinator(
        repo, ids = SeqIdFactory(), clock = { 1_000L },
    )

    /** Previous production read path, retained as a query-cost/compatibility baseline. */
    private suspend fun legacyBatchView(batchId: String): BatchView {
        val dao = db.downloads()
        val batch = dao.getBatch(batchId)!!.toDomain()
        val items = dao.itemsForBatch(batchId).map { it.toDomain() }
        val snapshots = items.mapNotNull { item ->
            val task = dao.getTask(item.taskId)!!
            val taskItems = dao.itemsForTask(task.id)
            val assets = dao.assetsForTask(task.id)
            val components = assets.flatMap { dao.componentsForAsset(it.id) }
            val chunks = components.flatMap { dao.chunksForComponent(it.id) }
            val publishes = assets.mapNotNull { dao.publishForAsset(it.id) }
            val batches = taskItems.mapNotNull { dao.getBatch(it.batchId) }
            val snapshot = storeFromEntities(batches, emptyList(), listOf(task), taskItems,
                assets, components, chunks, publishes, listOfNotNull(dao.scheduleForTask(task.id)))
                .taskSnapshot(task.id)!!
            snapshot.takeUnless { it.task.removed }?.let { ItemSnapshot(item, it) }
        }
        return BatchView(batch, snapshots, BatchStatsCalculator.compute(snapshots))
    }
}
