package com.hhst.youtubelite.browser

import android.annotation.SuppressLint
import android.os.SystemClock
import android.view.MotionEvent
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.core.DeviceEvidence
import com.hhst.youtubelite.extension.ExtensionManager
import com.hhst.youtubelite.gallery.GalleryActivity
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext

class GalleryTapAndroidTest {
    @get:Rule val activity = ActivityScenarioRule(ComponentActivity::class.java)

    @SuppressLint("SetJavaScriptEnabled")
    @Test fun realTouchOpensLazyAttachmentThroughBridgeBeforeImageDownload() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var view: WebView? = null
        val launches = AtomicInteger()
        val monitor = instrumentation.addMonitor(GalleryActivity::class.java.name, null, false)
        val urls = (1..2).map { "https://unreachable.gallery-test.ytimg.com/${System.nanoTime()}-$it.jpg" }
        try {
            activity.scenario.onActivity { context ->
                view = WebView(context).apply {
                    settings.javaScriptEnabled = true
                    addJavascriptInterface(Bridge({}, {}, extensionManager = GlobalContext.get().get<ExtensionManager>(),
                        onGallery = { images, index ->
                            assertEquals(urls, images); assertEquals(1, index)
                            launches.incrementAndGet(); GalleryActivity.open(context, images, index)
                        }), Bridge.NAME)
                }
                context.setContentView(view)
                view!!.loadDataWithBaseURL("https://m.youtube.com/watch?v=abcdefghijk", """
                    <html><meta name=viewport content="width=device-width,initial-scale=1"><body>
                    <div class=watch-below-the-player><ytm-backstage-post-renderer><ytm-post-multi-image-renderer>
                    <ytm-backstage-image-renderer id=one></ytm-backstage-image-renderer>
                    <ytm-backstage-image-renderer id=two role=button style="display:block;width:250px;height:200px;background:#4488cc"><img><span>Attachment</span></ytm-backstage-image-renderer>
                    </ytm-post-multi-image-renderer></ytm-backstage-post-renderer></div>
                    <script>document.getElementById('one').data={image:{thumbnails:[{url:'${urls[0]}',width:1200}]}};
                    document.getElementById('two').data={image:{thumbnails:[{url:'${urls[1]}',width:1200}]}};window.fixtureReady=true;</script>
                    </body></html>
                """.trimIndent(), "text/html", "UTF-8", null)
            }
            val probe = WebProbe { view!! }
            probe.waitFor("return {ready:!!window.fixtureReady}")
            for (name in listOf("core.js", "player-hook.js", "gallery.js")) {
                val source = instrumentation.targetContext.assets.open("script/$name").bufferedReader().use { it.readText() }
                instrumentation.runOnMainSync { view!!.evaluateJavascript(source, null) }
            }
            val coordinates = probe.evaluate("const r=document.getElementById('two').getBoundingClientRect();return {x:r.left+100,y:r.top+100,ratio:devicePixelRatio}")
            val location = IntArray(2)
            instrumentation.runOnMainSync { view!!.getLocationOnScreen(location) }
            val x = location[0] + (coordinates.getDouble("x") * coordinates.getDouble("ratio")).toFloat()
            val y = location[1] + (coordinates.getDouble("y") * coordinates.getDouble("ratio")).toFloat()
            fun gesture(drag: Boolean) {
                val start = SystemClock.uptimeMillis()
                fun send(action: Int, at: Long, px: Float) {
                    MotionEvent.obtain(start, at, action, px, y, 0).also {
                        instrumentation.sendPointerSync(it); it.recycle()
                    }
                }
                send(MotionEvent.ACTION_DOWN, start, x)
                if (drag) send(MotionEvent.ACTION_MOVE, start + 40, x + 90)
                send(MotionEvent.ACTION_UP, start + 80, if (drag) x + 90 else x)
            }
            gesture(true)
            instrumentation.waitForIdleSync()
            assertEquals("A carousel swipe must not launch Gallery", 0, launches.get())
            val start = SystemClock.elapsedRealtime()
            gesture(false)
            val gallery = monitor.waitForActivityWithTimeout(3_000) as? GalleryActivity
            assertNotNull("Tap never reached GalleryActivity", gallery)
            assertTrue("Opening Gallery waited for its image", SystemClock.elapsedRealtime() - start < 3_000)
            instrumentation.waitForIdleSync()
            assertEquals(1, launches.get())
            assertEquals(1, gallery!!.intent.getIntExtra("index", -1))
            DeviceEvidence.captureScene("gallery-before-image")
            instrumentation.runOnMainSync { gallery.finish() }
        } finally {
            instrumentation.removeMonitor(monitor)
            instrumentation.runOnMainSync { view?.destroy() }
        }
    }
}
