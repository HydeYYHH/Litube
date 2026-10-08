package com.hhst.youtubelite.downloader.engine

import com.hhst.youtubelite.downloader.core.DownloadStatus
import com.hhst.youtubelite.downloader.core.SeqIdFactory
import com.hhst.youtubelite.downloader.core.DownloadTarget
import com.hhst.youtubelite.downloader.core.BatchSnapshot
import com.hhst.youtubelite.downloader.core.BatchSource
import com.hhst.youtubelite.downloader.core.BatchSelection
import com.hhst.youtubelite.downloader.core.DownloadCoordinator
import com.hhst.youtubelite.downloader.core.request
import com.hhst.youtubelite.downloader.data.InMemoryDownloadRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal class FakeWorkEnqueuePort : WorkEnqueuePort {
    val enqueued = mutableListOf<UniqueWorkRequest>()
    val states = mutableMapOf<String, WorkSnapshot>()

    override fun enqueueUnique(request: UniqueWorkRequest): EnqueueOutcome {
        val existing = states[request.uniqueName]
        if (existing?.active == true && !request.replace) {
            return EnqueueOutcome.ALREADY_PRESENT
        }
        enqueued += request
        states[request.uniqueName] = WorkSnapshot(active = true, kind = request.kind)
        return if (existing?.active == true) EnqueueOutcome.REPLACED else EnqueueOutcome.CREATED
    }

    override fun cancelUnique(name: String) {
        states[name] = WorkSnapshot(active = false, kind = states[name]?.kind ?: DownloadWorkKind.TRANSFER)
    }

    override fun state(name: String): WorkSnapshot? = states[name]
}

internal class FakeUidtJobPort : UidtJobPort {
    val registered = mutableListOf<UidtJobRequest>()
    val active = mutableSetOf<Int>()
    val restoredIds = mutableSetOf<Int>()

    override fun register(request: UidtJobRequest, legalUserInteraction: Boolean): UidtRegisterResult {
        if (!legalUserInteraction) return UidtRegisterResult.RejectedNoUserInteraction
        if (request.jobId in restoredIds || request.jobId in active) {
            active += request.jobId
            return UidtRegisterResult.Restored(request.jobId)
        }
        registered += request
        active += request.jobId
        return UidtRegisterResult.Created(request.jobId)
    }

    override fun cancel(jobId: Int) {
        active.remove(jobId)
    }

    override fun isActive(jobId: Int): Boolean = jobId in active
}

internal class SchedulerHarness(
    sdk: Int,
    legal: Boolean = true,
) {
    val repo = InMemoryDownloadRepository()
    val work = FakeWorkEnqueuePort()
    val uidt = FakeUidtJobPort()
    val notifications = RecordingNotificationPort()
    lateinit var wired: DownloadCoordinator
    val scheduler = BackgroundDownloadScheduler(
        repository = repo,
        coordinator = { wired },
        work = work,
        uidt = uidt,
        notifications = notifications,
        sdk = { sdk },
        legalUserInteraction = legal,
    )

    init {
        wired = DownloadCoordinator(
            repository = repo,
            scheduler = scheduler,
            ids = SeqIdFactory(),
            clock = { 1_000L },
        )
    }
}

class BackgroundDownloadSchedulerTest {

    @Test
    fun api33_enqueue_usesLongRunningWmWithNetwork_finalizeHasNone() = runTest {
        val h = SchedulerHarness(sdk = 33)
        val batchId = h.wired.enqueue(request("a"), "s1").batchId
        val transfer = h.work.enqueued.single { it.kind == DownloadWorkKind.TRANSFER }
        assertEquals(DownloadWorkNames.transfer(batchId), transfer.uniqueName)
        assertTrue(transfer.requiresNetwork)
        assertEquals(0, h.uidt.registered.size)
        h.scheduler.enqueueFinalize(batchId)
        val finalize = h.work.enqueued.single { it.kind == DownloadWorkKind.FINALIZE }
        assertFalse(finalize.requiresNetwork)
    }

    @Test
    fun api34_confirm_registersOneUidtJobPerBatch() = runTest {
        val h = SchedulerHarness(sdk = 34)
        val result = h.wired.enqueueBatch(
            snapshot = BatchSnapshot(
                source = BatchSource.VIDEO,
                name = "B",
                items = listOf(request("a"), request("b")),
            ),
            selection = BatchSelection(indexes = setOf(0, 1)),
            submissionId = "s1",
        )
        assertEquals(1, h.uidt.registered.size)
        assertEquals(result.batchId, h.uidt.registered.single().batchId)
        assertEquals(0, h.work.enqueued.count { it.kind == DownloadWorkKind.TRANSFER })
        assertEquals(
            DownloadWorkNames.notificationId(result.batchId),
            h.notifications.posted.first().first,
        )
    }

    @Test
    fun api34_resume_registersUidt_networkDoesNot() = runTest {
        val h = SchedulerHarness(sdk = 34)
        val id = h.wired.enqueue(request("a"), "s1").created.single().taskId
        h.uidt.registered.clear()
        h.wired.pause(DownloadTarget.Task(id))
        h.wired.resume(DownloadTarget.Task(id))
        assertEquals(1, h.uidt.registered.size)
        h.uidt.registered.clear()
        val gen = h.repo.transact { getTask(id) }!!.executionGeneration
        h.wired.reportExecution(id, gen, DownloadStatus.WAITING_NETWORK)
        h.wired.onNetworkRestored()
        assertTrue(h.uidt.registered.isEmpty())
    }

    @Test
    fun duplicateSchedule_orSystemRestore_doesNotDoubleRun() = runTest {
        val h = SchedulerHarness(sdk = 33)
        val batchId = h.wired.enqueue(request("a"), "s1").batchId
        assertEquals(1, h.work.enqueued.size)
        val taskId = h.wired.ownedTaskIds(batchId).single()
        h.scheduler.repair(taskId)
        assertEquals(1, h.work.enqueued.size)
        assertEquals("already-present", h.scheduler.plans.last().outcome)

        val h34 = SchedulerHarness(sdk = 34)
        val batch34 = h34.wired.enqueue(request("a"), "s2").batchId
        val jobId = h34.uidt.registered.single().jobId
        h34.uidt.restoredIds += jobId
        h34.scheduler.noteRestored(batch34)
        assertEquals(1, h34.uidt.registered.size)
    }

    @Test
    fun foregroundTypes_api35MuxUsesMediaProcessing_saveUsesDataSync() {
        assertEquals(
            DownloadForegroundTypes.DATA_SYNC,
            DownloadForegroundTypes.forWork(33, DownloadWorkKind.TRANSFER),
        )
        assertEquals(
            DownloadForegroundTypes.DATA_SYNC,
            DownloadForegroundTypes.forWork(34, DownloadWorkKind.SAVE),
        )
        assertEquals(
            DownloadForegroundTypes.MEDIA_PROCESSING,
            DownloadForegroundTypes.forWork(35, DownloadWorkKind.MUX),
        )
        assertEquals(
            DownloadForegroundTypes.MEDIA_PROCESSING,
            DownloadForegroundTypes.forWork(35, DownloadWorkKind.FINALIZE),
        )
        assertEquals(DownloadForegroundTypes.NONE, DownloadForegroundTypes.forWork(28, DownloadWorkKind.TRANSFER))
    }
}
