package com.hhst.youtubelite.core

import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.channel.ChannelActivity
import com.hhst.youtubelite.data.media.ContentRepositories
import com.hhst.youtubelite.search.SearchResultsActivity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * In-app navigation holds auto-PiP only for the handoff itself: the hold ends when the
 * destination owns the foreground, not when the source behind it resumes or dies. Overlay
 * holds keep their own lifetime and are covered by the downloader tests.
 */
class PipHandoffAndroidTest {
    private fun fixtureIntent(context: android.content.Context, target: Class<*>): Intent =
        Intent(context, target).putExtra(ContentRepositories.EXTRA_FIXTURE_SCENE, true)

    @Test fun navigationSuppressionEndsWhenTheDestinationResumes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch<ChannelActivity>(fixtureIntent(context, ChannelActivity::class.java)).use { source ->
            source.onActivity { activity ->
                val destination = SearchResultsActivity.intent(activity, "pip handoff")
                    .putExtra(ContentRepositories.EXTRA_FIXTURE_SCENE, true)
                activity.startActivity(destination)
                assertTrue("Navigation should hold auto-PiP during the handoff", PipAutoEnter.isSuppressed())
            }
            awaitReleased()
        }
    }

    @Test fun sourceResumeReleasesItsOwnHandoff() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch<ChannelActivity>(fixtureIntent(context, ChannelActivity::class.java)).use { source ->
            source.onActivity { activity ->
                // A launch that never leaves this activity still leaves its
                // hold released on the owner's own resume or destroy.
                activity.startActivity(fixtureIntent(activity, ChannelActivity::class.java))
                assertTrue(PipAutoEnter.isSuppressed())
            }
            source.recreate()
            awaitReleased()
        }
    }

    private fun awaitReleased() {
        val deadline = System.currentTimeMillis() + 5_000
        while (PipAutoEnter.isSuppressed() && System.currentTimeMillis() < deadline) {
            SystemClock.sleep(50)
        }
        assertFalse("The handoff hold must release once the destination owns the foreground", PipAutoEnter.isSuppressed())
    }

    @Test fun completingMultipleHandoffsPreservesAnIndependentOverlayHold() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch<ChannelActivity>(fixtureIntent(context, ChannelActivity::class.java)).use { source ->
            source.onActivity { activity ->
                val overlay = PipAutoEnter.suppress()
                val first = PipAutoEnter.suppressForNavigation(activity)
                val second = PipAutoEnter.suppressForNavigation(activity)
                try {
                    first.restore()
                    assertTrue(PipAutoEnter.isSuppressed())
                    second.restore()
                } finally { overlay.restore() }
                assertFalse(PipAutoEnter.isSuppressed())
            }
        }
    }
}
