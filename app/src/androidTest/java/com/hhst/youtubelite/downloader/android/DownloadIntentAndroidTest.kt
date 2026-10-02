package com.hhst.youtubelite.downloader.android

import android.app.Instrumentation
import android.content.Intent
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.downloader.pip.PipAutoEnter
import com.hhst.youtubelite.downloader.share.DownloadShareOnce
import com.hhst.youtubelite.downloader.share.DownloadShareParser
import com.hhst.youtubelite.downloader.ui.DownloadActionActivity
import com.hhst.youtubelite.downloader.ui.DownloadActions
import com.hhst.youtubelite.downloader.ui.DownloadActivity
import com.hhst.youtubelite.downloader.ui.DownloadShareActivity
import com.hhst.youtubelite.downloader.ui.DownloadSheetActivity
import com.hhst.youtubelite.downloader.ui.DownloadUi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadIntentAndroidTest {

    private val instrumentation: Instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    @Before
    fun reset() {
        resetOverlayState()
    }

    @After
    fun teardown() {
        resetOverlayState()
    }

    @Test
    fun downloadActivity_batchExtraAndPipSuppress() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = DownloadActivity.intent(ctx, batchId = "batch-device")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<DownloadActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals("batch-device", activity.intent.getStringExtra(DownloadUi.EXTRA_BATCH_ID))
                assertTrue(PipAutoEnter.isSuppressed())
                assertFalse(PipAutoEnter.shouldAutoEnter(eligible = true, sdk = Build.VERSION.SDK_INT))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    assertFalse(
                        "API 31+ auto-enter must stay off while DownloadActivity is in front",
                        PipAutoEnter.shouldAutoEnter(eligible = true, sdk = Build.VERSION.SDK_INT),
                    )
                }
            }
            DeviceEvidence.captureScene("download-activity-batch")
            DeviceEvidence.dumpUi("download-activity-batch.xml")
        }
    }

    @Test
    fun notificationTrampoline_viewOpensDownloadActivityWithBatchId() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val monitor = instrumentation.addMonitor(DownloadActivity::class.java.name, null, false)
        var launched: android.app.Activity? = null
        try {
            ctx.startActivity(
                DownloadActionActivity.intent(ctx, DownloadActions.VIEW, "batch-notify")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            launched = monitor.waitForActivityWithTimeout(15_000)
            assertNotNull("DownloadActivity should open from the notification trampoline", launched)
            assertEquals(
                "batch-notify",
                launched!!.intent.getStringExtra(DownloadActions.EXTRA_BATCH_ID),
            )
            assertTrue(PipAutoEnter.isSuppressed())
        } finally {
            runCatching { launched?.finish() }
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test
    fun shareColdStart_opensSheetAndDuplicateIsConsumed() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        DownloadShareOnce.reset()
        val url = "https://youtu.be/dQw4w9wgGcQ"
        val first = DownloadShareParser.parse(Intent.ACTION_SEND, url, null)
        assertNotNull(first)
        assertTrue(DownloadShareOnce.consume(first!!.dedupeKey, now = 2_000L))
        assertFalse(DownloadShareOnce.consume(first.dedupeKey, now = 2_400L))
        DownloadShareOnce.reset()

        val sheetMonitor = instrumentation.addMonitor(DownloadSheetActivity::class.java.name, null, false)
        var sheet: android.app.Activity? = null
        try {
            ctx.startActivity(
                Intent(ctx, DownloadShareActivity::class.java)
                    .setAction(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, url)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            sheet = sheetMonitor.waitForActivityWithTimeout(20_000)
            assertNotNull("Cold-start share should open the confirm sheet", sheet)
            assertEquals(
                "dQw4w9wgGcQ",
                sheet!!.intent.getStringExtra(DownloadUi.EXTRA_VIDEO_ID),
            )
            DeviceEvidence.captureScene("share-cold-start")

            ctx.startActivity(
                Intent(ctx, DownloadShareActivity::class.java)
                    .setAction(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, url)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            Thread.sleep(1_000)
            assertEquals(
                "duplicate share must not open a second sheet",
                1,
                sheetMonitor.hits,
            )
        } finally {
            runCatching { sheet?.finish() }
            instrumentation.removeMonitor(sheetMonitor)
        }
    }

    @Test
    fun sheetActivity_suppressesPipWhileInFront() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val monitor = instrumentation.addMonitor(DownloadSheetActivity::class.java.name, null, false)
        var launched: android.app.Activity? = null
        try {
            ctx.startActivity(
                DownloadSheetActivity.singleIntent(
                    ctx,
                    "dQw4w9wgGcQ",
                    "Sample clip",
                    "Channel",
                    null,
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            launched = monitor.waitForActivityWithTimeout(15_000)
            assertNotNull(launched)
            assertEquals("dQw4w9wgGcQ", launched!!.intent.getStringExtra(DownloadUi.EXTRA_VIDEO_ID))
            Thread.sleep(400)
            assertTrue("DownloadSheetActivity onStart must suppress auto-PiP", PipAutoEnter.isSuppressed())
        } finally {
            runCatching { launched?.finish() }
            instrumentation.removeMonitor(monitor)
        }
    }
}
