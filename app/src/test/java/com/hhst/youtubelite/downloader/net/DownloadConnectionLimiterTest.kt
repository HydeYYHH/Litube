package com.hhst.youtubelite.downloader.net

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadConnectionLimiterTest {
    private val client = OkHttpClient()
    private fun call(host: String = "one.test") = client.newCall(Request.Builder().url("https://$host/").build())

    @Test fun hostLimitsDoNotBlockOtherHostsAndTotalStillApplies() = runTest {
        val gate = DownloadConnectionLimiter(2, 1)
        val release = CompletableDeferred<Unit>()
        val entered = mutableListOf<String>()
        val jobs = listOf("one.test", "one.test", "two.test", "three.test").map { host -> launch {
            gate.withConnection(call(host)) { entered += host; release.await() }
        } }
        runCurrent()
        assertEquals(listOf("one.test", "two.test"), entered)
        release.complete(Unit)
        jobs.joinAll()
        assertEquals(4, entered.size)
    }

    @Test fun liveResizeWakesWaitersAndShrinkLetsExistingTransfersFinish() = runTest {
        val gate = DownloadConnectionLimiter(1, 1)
        val firstRelease = CompletableDeferred<Unit>()
        val secondRelease = CompletableDeferred<Unit>()
        val entered = mutableListOf<Int>()
        val first = launch { gate.withConnection(call()) { entered += 1; firstRelease.await() } }
        val second = launch { gate.withConnection(call()) { entered += 2; secondRelease.await() } }
        runCurrent()
        assertEquals(listOf(1), entered)
        gate.update(2, 2)
        runCurrent()
        assertEquals(listOf(1, 2), entered)
        gate.update(1, 1)
        val third = launch { gate.withConnection(call()) { entered += 3 } }
        firstRelease.complete(Unit)
        runCurrent()
        assertEquals(listOf(1, 2), entered)
        secondRelease.complete(Unit)
        joinAll(first, second, third)
        assertEquals(listOf(1, 2, 3), entered)
    }

    @Test fun cancellationImmediatelyAfterAdmissionDoesNotLeakCapacity() = runTest {
        val gate = DownloadConnectionLimiter(1, 1)
        val release = CompletableDeferred<Unit>()
        val owner = launch { gate.withConnection(call()) { release.await() } }
        var entered = false
        val waiting = launch { gate.withConnection(call()) { fail("Cancelled work was admitted") } }
        runCurrent()
        gate.update(2, 2) // admitted synchronously, before its suspended continuation can resume
        waiting.cancelAndJoin()
        release.complete(Unit)
        owner.join()
        gate.withConnection(call()) { entered = true }
        assertTrue(entered)
    }

    @Test fun pausingAWaitingCallAndAFailedTransferBothReleaseTheirSlots() = runTest {
        val gate = DownloadConnectionLimiter(1, 1)
        val release = CompletableDeferred<Unit>()
        val owner = launch { gate.withConnection(call()) { release.await() } }
        val pendingCall = call()
        val pending = launch { gate.withConnection(pendingCall) { fail("Paused call ran") } }
        runCurrent()
        pendingCall.cancel()
        gate.cancelWaiting(pendingCall)
        pending.join()
        assertTrue(pending.isCancelled)
        release.complete(Unit)
        owner.join()
        try { gate.withConnection(call()) { throw java.io.IOException("body failed") } }
        catch (_: java.io.IOException) { }
        gate.withConnection(call()) { }
    }
}
