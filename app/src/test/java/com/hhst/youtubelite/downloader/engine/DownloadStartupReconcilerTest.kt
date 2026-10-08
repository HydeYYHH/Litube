package com.hhst.youtubelite.downloader.engine

import com.hhst.youtubelite.downloader.core.DownloadTarget
import com.hhst.youtubelite.downloader.core.NoOpResolver
import com.hhst.youtubelite.downloader.core.NoOpTransport
import com.hhst.youtubelite.downloader.core.NoOpFinalizer
import com.hhst.youtubelite.downloader.core.AssetKind
import com.hhst.youtubelite.downloader.core.DownloadPhase
import com.hhst.youtubelite.downloader.core.DownloadStatus
import com.hhst.youtubelite.downloader.core.NoOpPublisher
import com.hhst.youtubelite.downloader.core.request
import com.hhst.youtubelite.downloader.io.DownloadDirectories
import com.hhst.youtubelite.downloader.ui.DownloadActions
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DownloadStartupReconcilerTest {

    private fun env(sdk: Int): Triple<SchedulerHarness, DownloadStartupReconciler, DownloadDirectories> {
        val h = SchedulerHarness(sdk)
        val dirs = DownloadDirectories(File(System.getProperty("java.io.tmpdir"), "dl-rec-${System.nanoTime()}"))
        val rec = DownloadStartupReconciler(
            repository = h.repo,
            coordinator = h.wired,
            scheduler = h.scheduler,
            publisher = NoOpPublisher,
            directories = dirs,
        )
        return Triple(h, rec, dirs)
    }

    @Test
    fun repairsSubmittedButUnscheduled_andMissingSystemJob() = runTest {
        val (h, rec, _) = env(33)
        val batchId = h.wired.enqueue(request("a"), "s1").batchId
        val taskId = h.wired.ownedTaskIds(batchId).single()
        h.work.states.clear()
        h.work.enqueued.clear()
        rec.reconcile()
        assertTrue(h.work.enqueued.any { it.uniqueName == DownloadWorkNames.transfer(batchId) })

        h.work.states.clear()
        h.work.enqueued.clear()
        rec.reconcile()
        assertTrue(
            "scheduled-but-missing-system-job should repair",
            h.work.enqueued.any { it.batchId == batchId } ||
                h.scheduler.plans.any { it.batchId == batchId && it.kind == DownloadWorkKind.TRANSFER },
        )
    }

    @Test
    fun waitingSystem_forceStop_doesNotAutoResumeAsUser() = runTest {
        val (h, rec, _) = env(33)
        val id = h.wired.enqueue(request("a"), "s1").created.single().taskId
        h.wired.reportExecution(id, 0, DownloadStatus.RUNNING, DownloadPhase.TRANSFER)
        h.work.enqueued.clear()
        h.work.states.clear()
        rec.reconcile()
        assertEquals(DownloadStatus.WAITING_SYSTEM, h.repo.transact { getTask(id) }!!.status)
        assertTrue(h.work.enqueued.none { it.kind == DownloadWorkKind.TRANSFER })
        assertTrue(h.uidt.registered.isEmpty())
    }

    @Test
    fun lateWorkerCallback_staleGeneration_cannotUnpause() = runTest {
        val h = SchedulerHarness(33)
        val id = h.wired.enqueue(request("a"), "s1").created.single().taskId
        h.wired.reportExecution(id, 0, DownloadStatus.RUNNING, DownloadPhase.TRANSFER)
        h.wired.pause(DownloadTarget.Task(id))
        assertFalse(h.wired.reportExecution(id, 0, DownloadStatus.RUNNING, DownloadPhase.TRANSFER))
        val executor = DownloadBatchExecutor(
            engine = DownloadEngine(
                coordinator = h.wired,
                repository = h.repo,
                resolver = NoOpResolver,
                transport = NoOpTransport,
                finalizer = NoOpFinalizer,
                publisher = NoOpPublisher,
                directories = DownloadDirectories(
                    File(System.getProperty("java.io.tmpdir"), "dl-e-${System.nanoTime()}"),
                ),
            ),
            repository = h.repo,
            coordinator = h.wired,
            scheduler = h.scheduler,
        )
        executor.executeTransfer(h.wired.batchIdForTask(id)!!)
        val snap = h.repo.transact { snapshot(id) }!!
        assertEquals(DownloadStatus.PAUSED, snap.task.status)
        assertTrue(snap.task.userPaused)
        assertFalse(h.wired.reportExecution(id, 0, DownloadStatus.QUEUED, DownloadPhase.TRANSFER))
    }

    @Test
    fun notificationPause_reachesCoordinator() = runTest {
        val h = SchedulerHarness(33)
        val batchId = h.wired.enqueue(request("a"), "s1").batchId
        val id = h.wired.ownedTaskIds(batchId).single()
        h.wired.reportExecution(id, 0, DownloadStatus.RUNNING, DownloadPhase.TRANSFER)
        DownloadActions.apply(h.wired, DownloadActions.PAUSE, batchId)
        val snap = h.repo.transact { snapshot(id) }!!
        assertTrue(snap.task.userPaused)
        assertEquals(DownloadStatus.PAUSED, snap.task.status)
    }

    @Test
    fun permissionDenied_stillLeavesRoomAccurate() = runTest {
        val h = SchedulerHarness(33)
        h.notifications.enabled = false
        val id = h.wired.enqueue(request("a"), "s1").created.single().taskId
        assertTrue(h.notifications.posted.isEmpty())
        val snap = h.repo.transact { snapshot(id) }!!
        assertEquals(DownloadStatus.QUEUED, snap.task.status)
        assertTrue(snap.schedule!!.pending)
        assertTrue(h.work.enqueued.isNotEmpty())
    }

}

class DownloadNotificationControllerTest {

    @Test
    fun unchangedPayloadIsNotReposted_butPauseFlushesImmediately() {
        val port = RecordingNotificationPort()
        var now = 0L
        val c = DownloadNotificationController(port) { now }
        val h = SchedulerHarness(33)
        kotlinx.coroutines.runBlocking {
            val batchId = h.wired.enqueue(request("a"), "s1").batchId
            val view = h.repo.transact { batchView(batchId) }!!
            c.publish(view)
            c.publish(view)
            assertEquals(1, port.posted.size)
            now = 1_200L
            c.publish(view)
            assertEquals(1, port.posted.size)
            h.wired.pause(DownloadTarget.Batch(batchId))
            c.publish(h.repo.transact { batchView(batchId) }!!)
            assertEquals(2, port.posted.size)
            assertTrue(port.posted.last().second.showResume)
        }
    }

    @Test
    fun permissionDenied_skipsNotify() {
        val port = RecordingNotificationPort(enabled = false)
        val c = DownloadNotificationController(port)
        val h = SchedulerHarness(33)
        kotlinx.coroutines.runBlocking {
            val batchId = h.wired.enqueue(request("a"), "s1").batchId
            val view = h.repo.transact { batchView(batchId) }!!
            c.publish(view)
            assertTrue(port.posted.isEmpty())
            port.enabled = true
            c.publish(view)
            assertEquals(1, port.posted.size)
        }
    }

    @Test
    fun completedBatchIsNotRepostedWhenOtherDownloadsEmit() = kotlinx.coroutines.runBlocking {
        val port = RecordingNotificationPort()
        var now = 0L
        val controller = DownloadNotificationController(port) { now }
        val h = SchedulerHarness(33)
        val result = h.wired.enqueue(request("a"), "s1")
        h.wired.reportAssetPublished(result.created.single().taskId, 0,
            AssetKind.VIDEO, "content://test/complete.mp4")
        val view = h.repo.transact { batchView(result.batchId) }!!
        controller.publish(view)
        repeat(10) { now += 1_200L; controller.publish(view) }
        assertEquals(1, port.posted.size)
        assertTrue(port.posted.single().second.complete)
    }
}
