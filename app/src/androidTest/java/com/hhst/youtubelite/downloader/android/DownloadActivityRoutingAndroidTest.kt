package com.hhst.youtubelite.downloader.android

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.downloader.core.BatchSelection
import com.hhst.youtubelite.downloader.core.BatchSnapshot
import com.hhst.youtubelite.downloader.core.BatchSource
import com.hhst.youtubelite.downloader.core.DownloadConfig
import com.hhst.youtubelite.downloader.core.DownloadCoordinator
import com.hhst.youtubelite.downloader.core.DownloadRequest
import com.hhst.youtubelite.downloader.ui.DownloadActivity
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/**
 * DownloadActivity intent routing. Batch names and task titles differ so the
 * detail header and its row are distinct texts.
 */
@RunWith(AndroidJUnit4::class)
class DownloadActivityRoutingAndroidTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun batch(batchName: String, taskTitle: String): String = runBlocking {
        GlobalContext.get().get<DownloadCoordinator>()
            .enqueueBatch(
                BatchSnapshot(source = BatchSource.VIDEO, name = batchName,
                    items = listOf(DownloadRequest(videoId = "routing0000", title = taskTitle,
                        author = "Test", config = DownloadConfig()))),
                BatchSelection(indexes = setOf(0)),
                "routing-${UUID.randomUUID()}",
            ).batchId
    }

    private fun visibleTexts(): Set<String> {
        val texts = HashSet<String>()
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return
            node.text?.toString()?.takeIf(String::isNotBlank)?.let { texts += it }
            for (index in 0 until node.childCount) visit(node.getChild(index))
        }
        visit(InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow)
        return texts
    }

    private fun awaitText(text: String, timeoutMs: Long = 15_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (visibleTexts().contains(text)) return
            Thread.sleep(200)
        }
        assertTrue("Text never became visible: $text; saw ${visibleTexts()}", false)
    }

    private fun awaitGone(text: String, timeoutMs: Long = 15_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!visibleTexts().contains(text)) return
            Thread.sleep(200)
        }
        assertTrue("Text stayed visible: $text", false)
    }

    private fun deliver(activity: DownloadActivity, batchId: String) {
        // Windowing-dependent startActivity routing varies on this host; deliver directly.
        activity.onNewIntent(DownloadActivity.intent(activity, batchId = batchId))
    }

    @Test fun requestBeforeTheCollectorAttachesKeepsTheLatestBatch() {
        val older = batch("Routing older batch", "Routing older task")
        val newer = batch("Routing newer batch", "Routing newer task")
        ActivityScenario.launch<DownloadActivity>(DownloadActivity.intent(context)).use { scenario ->
            var instance: DownloadActivity? = null
            scenario.onActivity { instance = it }
            // Both sends may land before/after the collector attaches; the conflated
            // channel must retain the latest either way.
            scenario.onActivity { deliver(it, older) }
            scenario.onActivity { deliver(it, newer) }
            awaitText("Routing newer batch")
            awaitText("Routing newer task")
            awaitGone("Routing older batch")
            scenario.onActivity { current -> assertTrue(current === instance) }
        }
    }

    @Test fun runtimeIntentOpensTheBatchAndKeepsListState() {
        val first = batch("Routing runtime batch", "Routing runtime task")
        ActivityScenario.launch<DownloadActivity>(DownloadActivity.intent(context)).use { scenario ->
            var instance: DownloadActivity? = null
            scenario.onActivity { instance = it }
            awaitText("Downloads")

            scenario.onActivity { deliver(it, first) }
            awaitText("Routing runtime batch")
            scenario.onActivity { current ->
                assertTrue("onNewIntent must not recreate", current === instance)
            }

            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            awaitText("Downloads")
            awaitText("Routing runtime batch")
            scenario.onActivity { current -> assertTrue(current === instance) }
        }
    }
}
