package com.hhst.youtubelite.shorts

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.core.PhoneWindow
import com.hhst.youtubelite.shorts.domain.ShortsContent
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Read-only real-server shorts marathon: 30 forward swipes, reverse swipes through visited pages,
 * then a rapid un-settled burst. Asserts only the host contract (settled id follows the gesture,
 * no duplicate items, reverse travel lands on previously seen pages, burst leaves a consistent
 * page); network shortfalls are recorded observations, not pass criteria.
 */
@RunWith(AndroidJUnit4::class)
class ShortsMarathonAndroidTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var shorts: ActivityScenario<ShortsActivity>? = null

    @Before fun enterPhoneWindow() = PhoneWindow.enter()
    @After fun exitPhoneWindow() = PhoneWindow.exit()

    @After fun close() {
        shorts?.close()
        shorts = null
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun state(): com.hhst.youtubelite.shorts.domain.ShortsUiState? {
        var snapshot: com.hhst.youtubelite.shorts.domain.ShortsUiState? = null
        shorts?.onActivity { snapshot = it.shortsViewModel.uiState.value }
        return snapshot
    }

    private fun ready() = state()?.content as? ShortsContent.Ready

    private fun awaitReady() {
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            if (ready() != null && ready()!!.page.items.isNotEmpty()) return
            Thread.sleep(250)
        }
        assertTrue("Shorts feed never became ready; state=${state()?.content?.javaClass?.simpleName}", false)
    }

    private fun awaitCurrentItem(previous: String?, timeoutMs: Long = 15_000): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val current = state()?.currentItemId
            if (current != null && current != previous) return current
            Thread.sleep(200)
        }
        return null
    }

    private fun awaitAppendSettled() {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            val value = state()
            if (value != null && !value.appendLoading) return
            Thread.sleep(200)
        }
    }

    private fun swipe(forward: Boolean) {
        shorts?.onActivity { }
        compose.onNodeWithTag("shorts-pager").performTouchInput { if (forward) swipeUp() else swipeDown() }
    }

    private fun duplicates(items: List<String>): List<String> =
        items.groupBy { it }.filterValues { it.size > 1 }.keys.toList()

    private fun noDuplicates(step: String) {
        val page = ready()?.page ?: return
        assertEquals("$step: feed grew a duplicate item", emptyList<String>(),
            duplicates(page.items.map { it.id }))
    }

    @Test fun marathonSwipesFollowTheFeedContract() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("network") == "1" && arguments.getString("shortsMarathon") == "1")

        shorts = ActivityScenario.launch(ShortsActivity::class.java)
        awaitReady()
        val trace = JSONArray()
        var current = state()!!.currentItemId
        val path = mutableListOf(current)
        var forwardSettled = 0
        var feedExhausted = false

        for (step in 1..30) {
            swipe(forward = true)
            val next = awaitCurrentItem(current)
            awaitAppendSettled()
            noDuplicates("forward step $step")
            val record = JSONObject().put("step", step).put("direction", "forward")
                .put("from", current).put("to", next)
                .put("items", ready()?.page?.items?.size ?: -1)
                .put("appendFailed", state()?.appendFailed == true)
                .put("nextKey", ready()?.page?.nextKey != null)
            trace.put(record)
            if (next == null) {
                feedExhausted = true
                break
            }
            current = next
            path.add(next)
            forwardSettled += 1
        }

        var reverseSettled = 0
        while (path.size > 1 && reverseSettled < 6) {
            val expected = path[path.size - 2]
            swipe(forward = false)
            val back = awaitCurrentItem(current)
            awaitAppendSettled()
            noDuplicates("reverse step ${reverseSettled + 1}")
            trace.put(JSONObject().put("step", "reverse-${reverseSettled + 1}")
                .put("expected", expected).put("to", back)
                .put("items", ready()?.page?.items?.size ?: -1))
            if (back == null) break
            assertEquals("Reverse swipe must land on the previously seen page", expected, back)
            current = back
            path.removeAt(path.size - 1)
            reverseSettled += 1
        }

        // Rapid un-settled burst: stale generations must leave a consistent page.
        val burstFrom = current
        repeat(3) { swipe(forward = true); Thread.sleep(120) }
        awaitAppendSettled()
        val burstTo = state()?.currentItemId
        noDuplicates("burst")
        trace.put(JSONObject().put("step", "burst").put("from", burstFrom).put("to", burstTo)
            .put("items", ready()?.page?.items?.size ?: -1))

        val report = JSONObject()
            .put("forwardSettled", forwardSettled).put("requested", 30)
            .put("feedExhausted", feedExhausted)
            .put("reverseSettled", reverseSettled)
            .put("burstSettled", burstTo != null)
            .put("finalItems", ready()?.page?.items?.size ?: -1)
            .put("appendFailedObserved", state()?.appendFailed == true)
            .put("trace", trace)
        File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "shorts-marathon.json")
            .writeText(report.toString(2))
        assertTrue("Not a single forward swipe settled", forwardSettled > 0)
    }
}
