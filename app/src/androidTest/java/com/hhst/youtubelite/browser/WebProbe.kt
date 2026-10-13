package com.hhst.youtubelite.browser

import android.webkit.WebView
import android.os.SystemClock
import com.hhst.youtubelite.core.DeviceEvidence
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.fail
import org.junit.Assert.assertTrue

/** Evaluates page state and waits for bounded conditions. */
internal class WebProbe(private val view: () -> WebView) {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    fun evaluate(source: String): JSONObject {
        val latch = CountDownLatch(1)
        val result = AtomicReference("{}")
        instrumentation.runOnMainSync {
            view().evaluateJavascript("JSON.stringify((()=>{$source})())") { result.set(it); latch.countDown() }
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        return JSONObject(JSONTokener(result.get()).nextValue().toString())
    }

    fun waitFor(source: String): JSONObject {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        var state = JSONObject()
        while (SystemClock.elapsedRealtime() < deadline) {
            state = evaluate(source)
            if (state.optBoolean("ready")) return state
            Thread.sleep(100)
        }
        runCatching {
            DeviceEvidence.captureScene("web-condition")
            DeviceEvidence.dumpUi("web-condition.xml")
        }
        fail("Page condition not met: $state; query: $source")
        return state
    }
}
