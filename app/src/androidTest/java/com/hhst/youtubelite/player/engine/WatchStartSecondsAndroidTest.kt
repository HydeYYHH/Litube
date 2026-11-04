package com.hhst.youtubelite.player.engine

import android.content.Intent
import com.hhst.youtubelite.watch.WatchActivity
import com.hhst.youtubelite.watch.WatchActivity.Companion.startSecondsExtra
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Launch-intent start contract: missing extra resumes progress, 0 is explicit, negatives are ignored. */
class WatchStartSecondsAndroidTest {
    private fun intentWithExtra(seconds: Long?): Intent {
        val intent = Intent().putExtra(WatchActivity.EXTRA_VIDEO_ID, "menuTest001")
        if (seconds != null) intent.putExtra(WatchActivity.EXTRA_START_SECONDS, seconds)
        return intent
    }

    @Test fun startSecondsExtraSeparatesUnspecifiedFromExplicitZero() {
        assertNull(intentWithExtra(null).startSecondsExtra())
        assertEquals(0L, intentWithExtra(0L).startSecondsExtra())
        assertEquals(90L, intentWithExtra(90L).startSecondsExtra())
        assertNull("Negative values count as unspecified", intentWithExtra(-5L).startSecondsExtra())
    }

    @Test fun anUnspecifiedIntentStartResumesFromAccountProgress() {
        OfflineWatchFixture("startsec001").use { fixture ->
            val calls = AtomicInteger()
            fixture.stubRemoteResume({ calls.incrementAndGet(); 30_000L })
            try {
                fixture.launch()
                fixture.await { fixture.engine.snapshot.value.positionMs in 30_000..35_000 }
                assertEquals(1, calls.get())
            } finally { fixture.restoreRemoteResume() }
        }
    }

    @Test fun anExplicitZeroStartsAtTheBeginningWithoutReadingAccountProgress() {
        OfflineWatchFixture("startsec002").use { fixture ->
            val calls = AtomicInteger()
            fixture.stubRemoteResume({ calls.incrementAndGet(); 30_000L })
            try {
                fixture.launch(startSeconds = 0L)
                fixture.await { fixture.engine.snapshot.value.isPlaying && fixture.engine.snapshot.value.positionMs in 0..5_000 }
                assertEquals("Explicit zero must not trigger the account read", 0, calls.get())
            } finally { fixture.restoreRemoteResume() }
        }
    }

    @Test fun anExplicitPositiveStartBeginsAtThatTime() {
        OfflineWatchFixture("startsec003").use { fixture ->
            val calls = AtomicInteger()
            fixture.stubRemoteResume({ calls.incrementAndGet(); 30_000L })
            try {
                fixture.launch(startSeconds = 15L)
                fixture.await { fixture.engine.snapshot.value.positionMs in 15_000..20_000 }
                assertEquals(0, calls.get())
            } finally { fixture.restoreRemoteResume() }
        }
    }

    @Test fun aRecreatedActivityDoesNotReapplyTheExplicitStart() {
        OfflineWatchFixture("startsec004").use { fixture ->
            val calls = AtomicInteger()
            fixture.stubRemoteResume({ calls.incrementAndGet(); 30_000L })
            try {
                fixture.launch(startSeconds = 15L)
                fixture.await { fixture.engine.snapshot.value.positionMs in 15_000..20_000 }
                fixture.instrumentation.runOnMainSync { fixture.engine.seekTo(25_000) }
                fixture.await { fixture.engine.snapshot.value.positionMs >= 25_000 }
                val prepares = fixture.engine.mediaPrepareCount
                fixture.recreate()
                fixture.await { fixture.engine.snapshot.value.positionMs >= 25_000 }
                assertEquals("Recreation must not re-prepare the video", prepares, fixture.engine.mediaPrepareCount)
                assertEquals("Recreation must not re-read account progress", 0, calls.get())
            } finally { fixture.restoreRemoteResume() }
        }
    }

    @Test fun aFailedFirstLoadKeepsTheExplicitStartForRetry() {
        OfflineWatchFixture("startsec005").use { fixture ->
            val calls = AtomicInteger()
            fixture.stubRemoteResume({ calls.incrementAndGet(); 30_000L })
            val pending = fixture.engine.javaClass.getDeclaredField("pendingExplicitStartMs")
                .apply { isAccessible = true }
            try {
                // First load never reaches first frame: the explicit start must survive for the
                // retry. A real network retry re-extracts, which an offline fixture cannot resolve,
                // so the field contract is asserted directly and recovery runs via play().
                fixture.mediaRefused = true
                fixture.launch(expectFailure = true, startSeconds = 90L)
                assertEquals(90_000L, pending.get(fixture.engine))
                fixture.mediaRefused = false
                fixture.instrumentation.runOnMainSync { fixture.engine.play(fixture.url + "&t=90") }
                fixture.await { fixture.engine.snapshot.value.isPlaying &&
                    fixture.engine.snapshot.value.positionMs >= 90_000 }
                assertEquals("First frame must consume the explicit start", null, pending.get(fixture.engine))
                assertEquals("Explicit reloads must not read account progress", 0, calls.get())
            } finally { fixture.restoreRemoteResume() }
        }
    }
}
