package com.hhst.youtubelite.player

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.channel.ChannelActivity
import com.hhst.youtubelite.core.PipAutoEnter
import com.hhst.youtubelite.core.PipSupport
import com.hhst.youtubelite.data.media.ContentRepositories
import com.hhst.youtubelite.extension.ExtensionManager
import com.hhst.youtubelite.extension.PreferenceKeys
import com.hhst.youtubelite.watch.WatchActivity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.util.concurrent.atomic.AtomicReference

/**
 * Real navigation chain auto-PiP: a source screen launching Watch holds auto-PiP only for the handoff;
 * once Watch owns the foreground and reports player eligibility, leaving the app enters PiP — auto-enter
 * params on API 31+, the leave hint on API 26-30. Skipped where PiP is unsupported.
 */
class PipNavigationChainAndroidTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Before
    fun setup() {
        assumeTrue(PipSupport.isSupported(instrumentation.targetContext))
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        GlobalContext.get().get<ExtensionManager>().setEnabled(PreferenceKeys.ENABLE_PIP, true)
    }

    @Test
    fun watchLaunchedThroughTheNavigationChainAutoEntersPip() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val fixture = Intent(context, ChannelActivity::class.java)
            .putExtra(ContentRepositories.EXTRA_FIXTURE_SCENE, true)
        ActivityScenario.launch<ChannelActivity>(fixture).use { source ->
            val monitor = instrumentation.addMonitor(WatchActivity::class.java.name, null, false)
            source.onActivity { activity ->
                activity.startActivity(
                    WatchActivity.intent(activity, "dQw4w9WgXcQ")
                        .putExtra(ContentRepositories.EXTRA_FIXTURE_SCENE, true),
                )
            }
            val watch = monitor.waitForActivityWithTimeout(20_000)
            assertNotNull("WatchActivity should open through the navigation chain", watch)
            instrumentation.removeMonitor(monitor)
            waitUntil("the destination resumed and released the handoff") {
                !PipAutoEnter.isSuppressed()
            }
            watch!!.runOnUiThread { (watch as WatchActivity).setPlayerPipEligible(true) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                waitUntil("auto-enter armed on the playback host") {
                    PipAutoEnter.isAutoEnterEnabled(watch) ||
                        (PipAutoEnter.lastRequestedAutoEnter && PipAutoEnter.lastPushError == null)
                }
            } else {
                // API 26-30 has no auto-enter params; the leave hint consults
                // pipEligible() at leave time. Legacy launches start a short
                // suppression window, so give it time to lapse.
                SystemClock.sleep(2_000)
            }
            context.startActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            waitUntil("Watch entered PiP after leaving the app", 15_000) {
                currentOnMain(watch) { it.isInPictureInPictureMode }
            }
            watch.runOnUiThread {
                (watch as WatchActivity).setPlayerPipEligible(false)
                watch.finish()
            }
        }
    }

    private fun currentOnMain(activity: Activity, read: (Activity) -> Boolean): Boolean {
        var value = false
        instrumentation.runOnMainSync { value = read(activity) }
        return value
    }

    private fun waitUntil(label: String, timeoutMs: Long = 12_000L, pred: () -> Boolean) {
        val start = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - start < timeoutMs) {
            if (runCatching(pred).getOrDefault(false)) return
            SystemClock.sleep(80)
        }
        throw AssertionError("timed out waiting for $label")
    }
}
