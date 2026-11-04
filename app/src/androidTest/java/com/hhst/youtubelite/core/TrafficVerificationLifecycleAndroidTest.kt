package com.hhst.youtubelite.core

import android.content.Intent
import android.content.Context
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.koin.core.context.GlobalContext

class TrafficVerificationLifecycleAndroidTest {
    @Test
    fun aQueuedPauseCannotOverrideANewerResume() = checkQueuedTransition(paused = true)

    @Test
    fun aQueuedResumeCannotOverrideANewerPause() = checkQueuedTransition(paused = false)

    @Test
    fun verificationKeepsTimersRunningAndReleasesThemAcrossRecreation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val timers = GlobalContext.get().get<WebViewTimerOccupancy>()
        val previous = timers.totalHolders
        val intent = Intent(instrumentation.targetContext, TrafficVerificationActivity::class.java)
            .putExtra(TrafficVerificationActivity.EXTRA_CHALLENGE, "https://www.google.com/sorry/index")
        ActivityScenario.launch<TrafficVerificationActivity>(intent).use { scenario ->
            scenario.onActivity {
                assertEquals(previous + 1, timers.totalHolders)
                assertFalse(timers.isPaused)
            }
            scenario.recreate()
            scenario.onActivity {
                assertEquals(previous + 1, timers.totalHolders)
                assertFalse(timers.isPaused)
            }
        }
        instrumentation.waitForIdleSync()
        assertEquals(previous, timers.totalHolders)
        if (previous == 0) assertTrue(timers.isPaused)
    }

    private fun checkQueuedTransition(paused: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val gate = DeferredGate()
            val host = RecordingWebView(instrumentation.targetContext)
            try {
                val clock = AndroidWebViewTimerClock(gate)
                clock.attach(host)
                if (paused) clock.pauseTimers() else clock.resumeTimers()
                gate.immediate = true
                if (paused) clock.resumeTimers() else clock.pauseTimers()
                gate.drain()
                assertEquals(listOf(!paused), host.transitions)
            } finally {
                host.destroy()
            }
        }
    }

    private class RecordingWebView(context: Context) : WebView(context) {
        val transitions = mutableListOf<Boolean>()
        override fun pauseTimers() { transitions += true }
        override fun resumeTimers() { transitions += false }
    }

    private class DeferredGate : WebViewMainGate {
        private val queued = mutableListOf<() -> Unit>()
        var immediate = false
        override val isOnMain = true
        override fun <T> run(block: () -> T) = block()
        override fun post(block: () -> Unit) {
            if (immediate) block() else queued += block
        }
        fun drain() {
            queued.toList().forEach { it() }
            queued.clear()
        }
    }
}
