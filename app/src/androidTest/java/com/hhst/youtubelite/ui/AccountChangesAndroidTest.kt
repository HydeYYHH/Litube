package com.hhst.youtubelite.ui

import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hhst.youtubelite.data.media.MediaSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry

/** Host refresh delivery without changing stored credentials. */
@RunWith(AndroidJUnit4::class)
class AccountChangesAndroidTest {

    private fun await(condition: () -> Boolean, what: String) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            Thread.sleep(100)
        }
        assertTrue("Never observed: $what", false)
    }

    @Test fun aSessionGenerationChangeFiresTheHostRefreshOncePerChange() {
        val before = MediaSession.anonymousMode()
        try {
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                val refreshes = AtomicInteger()
                scenario.onActivity { activity -> activity.observeAccountChanges { refreshes.incrementAndGet() } }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                assertEquals(0, refreshes.get())

                assertTrue(MediaSession.setAnonymousMode(!before))
                await({ refreshes.get() >= 1 }, "refresh after the first identity change")
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                assertEquals(1, refreshes.get())

                assertTrue(MediaSession.setAnonymousMode(before))
                await({ refreshes.get() >= 2 }, "refresh after the restored identity")
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                assertEquals(2, refreshes.get())
            }
        } finally {
            MediaSession.setAnonymousMode(before)
        }
    }

    @Test fun registeringTheObserverDoesNotRefreshAndRepeatedValuesStaySilent() {
        val before = MediaSession.anonymousMode()
        try {
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                val refreshes = AtomicInteger()
                scenario.onActivity { activity -> activity.observeAccountChanges { refreshes.incrementAndGet() } }
                // Same-mode writes return false and emit no generation change.
                assertEquals(false, MediaSession.setAnonymousMode(before))
                Thread.sleep(1_000)
                assertEquals(0, refreshes.get())
            }
        } finally {
            MediaSession.setAnonymousMode(before)
        }
    }
}
