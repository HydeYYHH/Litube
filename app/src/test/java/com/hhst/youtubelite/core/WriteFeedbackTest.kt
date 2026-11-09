package com.hhst.youtubelite.core

import app.cash.turbine.test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WriteFeedbackTest {
    @Test fun overlappingWritesKeepProgressUntilBothFinish() = runTest {
        val feedback = WriteFeedback()
        val first = CompletableDeferred<Unit>()
        val second = CompletableDeferred<Unit>()
        feedback.pending.test {
            assertTrue(awaitItem().isEmpty())
            feedback.launch(this@runTest) { first.await() }
            val firstTokens = awaitItem()
            assertEquals(1, firstTokens.size)
            feedback.launch(this@runTest) { second.await() }
            val bothTokens = awaitItem()
            assertEquals(2, bothTokens.size)
            assertTrue(bothTokens.containsAll(firstTokens))
            runCurrent()
            first.complete(Unit)
            assertEquals(bothTokens - firstTokens, awaitItem())
            second.complete(Unit)
            assertTrue(awaitItem().isEmpty())
            expectNoEvents()
        }
    }

    @Test fun cancellationOfAnOldWriteCannotHideANewerWrite() = runTest {
        val feedback = WriteFeedback()
        val old = feedback.launch(this) { CompletableDeferred<Unit>().await() }
        runCurrent()
        old.cancel()
        val current = feedback.launch(this) { CompletableDeferred<Unit>().await() }
        runCurrent()
        assertEquals(1, feedback.pending.value.size)
        current.cancel()
        runCurrent()
        assertTrue(feedback.pending.value.isEmpty())
    }

    @Test fun alreadyCancelledScopesDoNotLeaveProgressStuck() = runTest {
        val feedback = WriteFeedback()
        val scope = CoroutineScope(coroutineContext + Job())
        scope.cancel()
        feedback.launch(scope) { fail("Cancelled work must not run") }
        runCurrent()
        assertTrue(feedback.pending.value.isEmpty())
    }

    @Test fun retiringASessionClearsItsProgressWithoutHidingTheNextSessionsWrite() = runTest {
        val feedback = WriteFeedback()
        val oldResponse = CompletableDeferred<Unit>()
        val newResponse = CompletableDeferred<Unit>()
        val old = feedback.launch(this) { withContext(NonCancellable) { oldResponse.await() } }
        runCurrent()
        old.cancel()
        feedback.reset()
        assertTrue(feedback.pending.value.isEmpty())
        feedback.launch(this) { newResponse.await() }
        runCurrent()
        oldResponse.complete(Unit)
        runCurrent()
        assertEquals(1, feedback.pending.value.size)
        newResponse.complete(Unit)
        runCurrent()
        assertTrue(feedback.pending.value.isEmpty())
    }
}
