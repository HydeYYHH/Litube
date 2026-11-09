package com.hhst.youtubelite.navigation

import android.content.Intent
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.home.HomeActivity
import com.hhst.youtubelite.shorts.ShortsActivity
import com.hhst.youtubelite.watch.WatchActivity
import org.junit.Assert.*
import org.junit.Test

class ContentLinkAndroidTest {
    @Test fun videoOpensBeforeHomeAndPreservesTheTimestampAndBackStack() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val watchMonitor = instrumentation.addMonitor(WatchActivity::class.java.name, null, false)
        val homeMonitor = instrumentation.addMonitor(HomeActivity::class.java.name, null, false)
        try {
            launch("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=83s")
            val watch = watchMonitor.waitForActivityWithTimeout(10_000)
            assertNotNull("Playback destination was not opened", watch)
            assertEquals(83L, watch!!.intent.getLongExtra(WatchActivity.EXTRA_START_SECONDS, -1))
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val home = homeMonitor.lastActivity as? HomeActivity
                assertTrue("Home loaded its feed before playback", home == null || runCatching { home.homeViewModel }.isFailure)
            }
            instrumentation.runOnMainSync { watch.finish() }
            val home = homeMonitor.waitForActivityWithTimeout(10_000)
            assertNotNull("Back lost the home destination", home)
            instrumentation.runOnMainSync { home?.finish() }
        } finally {
            instrumentation.removeMonitor(watchMonitor)
            instrumentation.removeMonitor(homeMonitor)
        }
    }

    @Test fun shortsSharesOpenTheShortsDestination() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = instrumentation.addMonitor(ShortsActivity::class.java.name, null, false)
        try {
            launch("https://www.youtube.com/shorts/dQw4w9WgXcQ", shared = true)
            val shorts = monitor.waitForActivityWithTimeout(10_000)
            assertNotNull(shorts)
            instrumentation.runOnMainSync { shorts?.finish() }
        } finally { instrumentation.removeMonitor(monitor) }
    }

    private fun launch(url: String, shared: Boolean = false) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.startActivity(Intent(context, ContentLinkActivity::class.java).apply {
            if (shared) { action = Intent.ACTION_SEND; type = "text/plain"; putExtra(Intent.EXTRA_TEXT, url) }
            else { action = Intent.ACTION_VIEW; data = Uri.parse(url) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
    }
}
