package com.hhst.youtubelite.menus

import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.browser.WebProbe
import com.hhst.youtubelite.core.DeviceEvidence
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Read-only sampling for the search filter surface, ordinary-video action menus,
 * comment/reply menus and the share entry; requires `-e network 1`.
 */
class MenuShareReferenceAndroidTest {

    @Test fun collectAllSurfaces() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("network") == "1")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        WebView.setDataDirectorySuffix("clean-menu-share-reference")
        val originalSize = DeviceEvidence.shell("wm size").substringAfterLast(":").trim()
        val originalDensity = DeviceEvidence.shell("wm density").substringAfterLast(":").trim()
        try {
            val requestedWidth = InstrumentationRegistry.getArguments().getString("referenceWidth")?.toIntOrNull()
            val viewports = requestedWidth?.let { listOf(it to 800) } ?: listOf(390 to 844, 1280 to 800)
            for ((width, height) in viewports) {
                DeviceEvidence.shell("wm density 160")
                DeviceEvidence.shell("wm size ${width}x$height")
                Thread.sleep(600)
                collectViewport(width, height)
            }
        } finally {
            DeviceEvidence.shell("wm density $originalDensity")
            DeviceEvidence.shell("wm size $originalSize")
        }
    }

    @Test fun collectCommentSurfacesOnly() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("network") == "1")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        WebView.setDataDirectorySuffix("clean-comment-menu-reference")
        val originalSize = DeviceEvidence.shell("wm size").substringAfterLast(":").trim()
        val originalDensity = DeviceEvidence.shell("wm density").substringAfterLast(":").trim()
        try {
            val width = InstrumentationRegistry.getArguments().getString("referenceWidth")?.toIntOrNull() ?: 390
            DeviceEvidence.shell("wm density 160")
            DeviceEvidence.shell("wm size ${width}x844")
            Thread.sleep(500)
            val scenario = ActivityScenario.launch<ComponentActivity>(Intent(instrumentation.targetContext, ComponentActivity::class.java))
            lateinit var web: WebView
            var origin = intArrayOf(0, 0)
            scenario.onActivity { host -> web = WebView(host).apply {
                settings.javaScriptEnabled = true; settings.domStorageEnabled = true
                settings.useWideViewPort = true; settings.loadWithOverviewMode = true
                webViewClient = android.webkit.WebViewClient()
            }; host.setContentView(web) }
            try {
                val probe = WebProbe { web }
                load(web, "https://m.youtube.com/watch?v=aqz-KE-bpKQ&hl=en", "/watch")
                Thread.sleep(2_000)
                probeUntil(probe, COLLECT_WATCH_PAGE, 20_000) { it.optBoolean("ready") }
                instrumentation.runOnMainSync { web.getLocationOnScreen(origin) }
                val entry = probeUntil(probe, FIND_COMMENTS_ENTRY, 10_000) { it.optBoolean("found") }
                DeviceEvidence.writeJson("comment2-entry-$width.json", entry.toString(2))
                if (!entry.optBoolean("found")) return
                tapTarget(probe, origin, entry.optJSONObject("target"))
                Thread.sleep(1_500)
                var open = probeUntil(probe, COMMENTS_OPEN, 8_000) { it.optBoolean("open") }
                if (!open.optBoolean("open")) {
                    probe.evaluate(COMMENTS_ENTRY_CLICK)
                    open = probeUntil(probe, COMMENTS_OPEN, 8_000) { it.optBoolean("open") }
                }
                DeviceEvidence.writeJson("comment2-open-$width.json", open.toString(2))
                DeviceEvidence.captureScene("comment2-open-$width")
                if (!open.optBoolean("open")) return
                Thread.sleep(800)

                val target = probeUntil(probe, COMMENT_MENU_TARGET, 8_000) { it.optBoolean("found") }
                DeviceEvidence.writeJson("comment2-target-$width.json", target.toString(2))
                DeviceEvidence.writeJson("comment2-element-$width.json", probe.evaluate(COMMENT_ELEMENT_AT).toString(2))
                val clickResult = probe.evaluate(COMMENT_MENU_CLICK)
                DeviceEvidence.writeJson("comment2-click-$width.json", clickResult.toString(2))
                Thread.sleep(2_500)
                var after = probe.evaluate(COMMENT_MENU_JS_STATE)
                DeviceEvidence.writeJson("comment2-after-click-$width.json", after.toString(2))
                if (after.optJSONArray("dialogs")?.length() == 0) {
                    tapTarget(probe, origin, target.optJSONObject("target"))
                    Thread.sleep(2_000)
                    after = probe.evaluate(COMMENT_MENU_JS_STATE)
                    DeviceEvidence.writeJson("comment2-after-tap-$width.json", after.toString(2))
                }
                DeviceEvidence.captureScene("comment2-after-click-$width")

                var expanded = false
                repeat(8) {
                    if (expanded) return@repeat
                    val toggle = probe.evaluate(REPLY_TOGGLE)
                    DeviceEvidence.writeJson("comment2-reply-toggle-$width.json", toggle.toString(2))
                    if (toggle.optBoolean("found")) {
                        tapTarget(probe, origin, toggle.optJSONObject("target"))
                        Thread.sleep(1_400)
                        if (!probe.evaluate(COMMENT_MENU_TARGET_ALL).optBoolean("hasReply")) {
                            probe.evaluate(REPLY_TOGGLE_CLICK)
                            Thread.sleep(1_400)
                        }
                        val reply = probeUntil(probe, REPLY_MENU_TARGET, 6_000) { it.optBoolean("found") }
                        DeviceEvidence.writeJson("comment2-reply-$width.json", reply.toString(2))
                        if (reply.optBoolean("found")) {
                            tapTarget(probe, origin, reply.optJSONObject("target"))
                            Thread.sleep(1_000)
                            var menu = probe.evaluate(SHEET_DUMP)
                            if (!menu.optBoolean("open")) {
                                probe.evaluate(REPLY_MENU_CLICK)
                                Thread.sleep(2_000)
                                menu = probe.evaluate(SHEET_DUMP)
                            }
                            DeviceEvidence.writeJson("comment2-reply-menu-any-$width.json", probe.evaluate(MENU_ANY).toString(2))
                            DeviceEvidence.writeJson("comment2-reply-menu-$width.json", menu.toString(2))
                            DeviceEvidence.captureScene("comment2-reply-menu-$width")
                            if (probe.evaluate(SHEET_OPEN_STATE).optBoolean("open")) {
                                DeviceEvidence.shell("input keyevent KEYCODE_BACK"); Thread.sleep(400)
                            }
                            expanded = true
                        }
                    } else {
                        probe.evaluate(SCROLL_COMMENTS)
                        Thread.sleep(600)
                    }
                }
            } finally {
                runCatching { scenario.onActivity { web.destroy() } }
                runCatching { scenario.close() }
            }
        } finally {
            DeviceEvidence.shell("wm density $originalDensity")
            DeviceEvidence.shell("wm size $originalSize")
        }
    }

    @Test fun collectFilterSurfaceOnly() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("network") == "1")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        WebView.setDataDirectorySuffix("clean-filter-reference")
        val originalSize = DeviceEvidence.shell("wm size").substringAfterLast(":").trim()
        val originalDensity = DeviceEvidence.shell("wm density").substringAfterLast(":").trim()
        try {
            val width = InstrumentationRegistry.getArguments().getString("referenceWidth")?.toIntOrNull() ?: 390
            DeviceEvidence.shell("wm density 160")
            DeviceEvidence.shell("wm size ${width}x844")
            Thread.sleep(500)
            val scenario = ActivityScenario.launch<ComponentActivity>(Intent(instrumentation.targetContext, ComponentActivity::class.java))
            lateinit var web: WebView
            scenario.onActivity { host -> web = WebView(host).apply {
                settings.javaScriptEnabled = true; settings.domStorageEnabled = true
                settings.useWideViewPort = true; settings.loadWithOverviewMode = true
                webViewClient = android.webkit.WebViewClient()
            }; host.setContentView(web) }
            try {
                val probe = WebProbe { web }
                load(web, "https://m.youtube.com/results?search_query=blender+open+movie&hl=en", "/results")
                probeUntil(probe, COLLECT_PAGE, 20_000) { it.optBoolean("ready") }
                DeviceEvidence.writeJson("filter-click.json", probe.evaluate(FILTER_CLICK).toString(2))
                Thread.sleep(3_000)
                DeviceEvidence.writeJson("filter-visible-$width.json", probe.evaluate(FILTER_SURFACE).toString(2))
                DeviceEvidence.captureScene("filter-visible-$width")
                if (InstrumentationRegistry.getArguments().getString("pickerOnly") == "true") {
                    val rect = probe.evaluate(FILTER_SURFACE).getJSONArray("groups").getJSONObject(1).getJSONObject("selectRect")
                    val location = IntArray(2)
                    scenario.onActivity { web.getLocationOnScreen(location) }
                    val x = location[0] + rect.getDouble("x") + rect.getDouble("width") / 2
                    val y = location[1] + rect.getDouble("y") + rect.getDouble("height") / 2
                    DeviceEvidence.shell("input tap ${x.toInt()} ${y.toInt()}")
                    Thread.sleep(1000)
                    DeviceEvidence.captureScene("filter-picker-$width")
                    DeviceEvidence.dumpUi("filter-picker-$width.xml")
                    return
                }
                DeviceEvidence.writeJson("filter-hidden-$width.json", probe.evaluate(FILTER_HIDDEN).toString(2))
                DeviceEvidence.writeJson("filter-css-$width.json", probe.evaluate(FILTER_CSS).toString(2))
                DeviceEvidence.writeJson("filter-before-$width.json", probe.evaluate(PAGE_STATE_FULL).toString(2))
                val apply = probe.evaluate(FILTER_SELECT_CHANNELS)
                DeviceEvidence.writeJson("filter-apply-$width.json", apply.toString(2))
                Thread.sleep(2_500)
                DeviceEvidence.writeJson("filter-after-$width.json", probe.evaluate(PAGE_STATE_FULL).toString(2))
                DeviceEvidence.captureScene("filter-after-$width")
                val date = probe.evaluate(FILTER_SELECT_TODAY)
                DeviceEvidence.writeJson("filter-date-$width.json", date.toString(2))
                Thread.sleep(2_500)
                DeviceEvidence.writeJson("filter-date-after-$width.json", probe.evaluate(PAGE_STATE_FULL).toString(2))
                DeviceEvidence.captureScene("filter-date-after-$width")
            } finally { runCatching { scenario.onActivity { web.destroy() } }; runCatching { scenario.close() } }
        } finally {
            DeviceEvidence.shell("wm density $originalDensity")
            DeviceEvidence.shell("wm size $originalSize")
        }
    }

    @Test fun collectStandardVideoMenuOnly() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("network") == "1")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        WebView.setDataDirectorySuffix("clean-video-menu-reference")
        val originalSize = DeviceEvidence.shell("wm size").substringAfterLast(":").trim()
        val originalDensity = DeviceEvidence.shell("wm density").substringAfterLast(":").trim()
        try {
            DeviceEvidence.shell("wm density 160")
            DeviceEvidence.shell("wm size 390x844")
            Thread.sleep(500)
            val scenario = ActivityScenario.launch<ComponentActivity>(Intent(instrumentation.targetContext, ComponentActivity::class.java))
            lateinit var web: WebView
            var origin = intArrayOf(0, 0)
            scenario.onActivity { host -> web = WebView(host).apply {
                settings.javaScriptEnabled = true; settings.domStorageEnabled = true
                settings.useWideViewPort = true; settings.loadWithOverviewMode = true
                webViewClient = android.webkit.WebViewClient()
            }; host.setContentView(web) }
            try {
                val probe = WebProbe { web }
                load(web, "https://m.youtube.com/results?search_query=blender+open+movie&hl=en", "/results")
                probeUntil(probe, COLLECT_PAGE, 20_000) { it.optBoolean("ready") }
                probe.evaluate(INSTALL_SHARE_STUB)
                instrumentation.runOnMainSync { web.getLocationOnScreen(origin) }
                val entry = probeUntil(probe, FIND_STANDARD_VIDEO_MENU, 10_000) { it.optBoolean("found") }
                DeviceEvidence.writeJson("video2-entry.json", entry.toString(2))
                tapTarget(probe, origin, entry.optJSONObject("target"))
                Thread.sleep(2_500)
                val menu = probe.evaluate(SHEET_DUMP)
                DeviceEvidence.writeJson("video2-menu.json", menu.toString(2))
                DeviceEvidence.captureScene("video2-menu")
                val share = menu.optJSONObject("shareRow")
                if (share != null) {
                    tapTarget(probe, origin, share)
                    Thread.sleep(1_200)
                    DeviceEvidence.writeJson("video2-share.json", probe.evaluate(SHARED_STATE).toString(2))
                    DeviceEvidence.captureScene("video2-share")
                }
                if (probe.evaluate(SHEET_OPEN_STATE).optBoolean("open")) {
                    DeviceEvidence.shell("input keyevent KEYCODE_BACK"); Thread.sleep(500)
                }
            } finally {
                runCatching { scenario.onActivity { web.destroy() } }
                runCatching { scenario.close() }
            }
        } finally {
            DeviceEvidence.shell("wm density $originalDensity")
            DeviceEvidence.shell("wm size $originalSize")
        }
    }

    @Test fun collectShareSurfaceOnly() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("network") == "1")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        WebView.setDataDirectorySuffix("clean-share-reference")
        val originalSize = DeviceEvidence.shell("wm size").substringAfterLast(":").trim()
        val originalDensity = DeviceEvidence.shell("wm density").substringAfterLast(":").trim()
        try {
            DeviceEvidence.shell("wm density 160")
            DeviceEvidence.shell("wm size 390x844")
            Thread.sleep(500)
            val scenario = ActivityScenario.launch<ComponentActivity>(Intent(instrumentation.targetContext, ComponentActivity::class.java))
            lateinit var web: WebView
            var origin = intArrayOf(0, 0)
            scenario.onActivity { host -> web = WebView(host).apply {
                settings.javaScriptEnabled = true; settings.domStorageEnabled = true
                settings.useWideViewPort = true; settings.loadWithOverviewMode = true
                webViewClient = android.webkit.WebViewClient()
            }; host.setContentView(web) }
            try {
                val probe = WebProbe { web }
                load(web, "https://m.youtube.com/watch?v=aqz-KE-bpKQ&hl=en", "/watch")
                Thread.sleep(2_000)
                probeUntil(probe, COLLECT_WATCH_PAGE, 20_000) { it.optBoolean("ready") }
                probe.evaluate(INSTALL_SHARE_STUB)
                instrumentation.runOnMainSync { web.getLocationOnScreen(origin) }
                val entry = probeUntil(probe, FIND_SHARE_SCROLL, 10_000) { it.optBoolean("found") }
                DeviceEvidence.writeJson("share2-entry.json", entry.toString(2))
                tapTarget(probe, origin, entry.optJSONObject("target"))
                Thread.sleep(1_500)
                DeviceEvidence.writeJson("share2-after.json", probe.evaluate(SHARED_STATE).toString(2))
                DeviceEvidence.captureScene("share2-after")
                DeviceEvidence.dumpUi("share2-ui.xml")
                val dump = DeviceEvidence.shell("cat /data/local/tmp/device-evidence/share2-ui.xml")
                if (dump.contains("Copy link") || dump.contains("Share with") || dump.contains("Nearby")) {
                    DeviceEvidence.shell("input keyevent KEYCODE_BACK"); Thread.sleep(500)
                }
            } finally {
                runCatching { scenario.onActivity { web.destroy() } }
                runCatching { scenario.close() }
            }
        } finally {
            DeviceEvidence.shell("wm density $originalDensity")
            DeviceEvidence.shell("wm size $originalSize")
        }
    }

    private fun collectViewport(width: Int, height: Int) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val scenario = ActivityScenario.launch<ComponentActivity>(
            Intent(instrumentation.targetContext, ComponentActivity::class.java),
        )
        lateinit var web: WebView
        var origin = intArrayOf(0, 0)
        scenario.onActivity { host ->
            web = WebView(host).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.mediaPlaybackRequiresUserGesture = true
                webViewClient = android.webkit.WebViewClient()
            }
            host.setContentView(web)
        }
        try {
            val probe = WebProbe { web }
            load(web, "https://m.youtube.com/results?search_query=blender+open+movie&hl=en", "/results")
            instrumentation.runOnMainSync { web.getLocationOnScreen(origin) }
            val probeRoot = probeUntil(probe, COLLECT_PAGE, 20_000) { it.optBoolean("ready") }
            DeviceEvidence.writeJson("menu-share-results-$width.json", probeRoot.toString(2))
            DeviceEvidence.captureScene("menu-share-results-$width")

            collectFilter(probe, width, origin)

            load(web, "https://m.youtube.com/results?search_query=blender+open+movie&hl=en", "/results")
            probeUntil(probe, COLLECT_PAGE, 20_000) { it.optBoolean("ready") }
            probe.evaluate(INSTALL_SHARE_STUB)
            collectResultsItemMenu(probe, width, origin)

            load(web, "https://m.youtube.com/watch?v=aqz-KE-bpKQ&hl=en", "/watch")
            Thread.sleep(2_000)
            probeUntil(probe, COLLECT_WATCH_PAGE, 20_000) { it.optBoolean("ready") }
            probe.evaluate(INSTALL_SHARE_STUB)
            collectRelatedMenu(probe, width, origin)
            collectShare(probe, width, origin)
            collectCommentMenus(probe, width, origin)
        } finally {
            scenario.onActivity { web.destroy() }
            scenario.close()
        }
    }

    private fun load(web: WebView, url: String, path: String) {
        val inst = InstrumentationRegistry.getInstrumentation()
        inst.runOnMainSync { web.loadUrl(url) }
        val deadline = SystemClock.elapsedRealtime() + 45_000
        var loaded = false
        while (!loaded && SystemClock.elapsedRealtime() < deadline) {
            inst.runOnMainSync {
                loaded = web.progress == 100 && web.url?.let { java.net.URI(it).path } == path
            }
            if (!loaded) Thread.sleep(200)
        }
        assertTrue("Reference page did not load: $url", loaded)
        Thread.sleep(1_800)
        dismissSystemDialogs()
    }

    private fun dismissSystemDialogs() {
        val dump = DeviceEvidence.shell(
            "uiautomator dump /data/local/tmp/menu-ui.xml >/dev/null 2>&1; cat /data/local/tmp/menu-ui.xml 2>/dev/null",
        )
        if (!dump.contains("Don't allow") && !dump.contains("Don’t allow")) return
        val match = Regex("text=\"Don.t allow\"[^>]*bounds=\"\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]\"").find(dump) ?: return
        val (l, t, r, b) = match.destructured.toList().map { it.toInt() }
        DeviceEvidence.shell("input tap ${(l + r) / 2} ${(t + b) / 2}")
        Thread.sleep(400)
    }

    private fun probeUntil(probe: WebProbe, source: String, timeoutMs: Long, ready: (JSONObject) -> Boolean): JSONObject {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var payload = probe.evaluate(source)
        while (!ready(payload) && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(350)
            payload = probe.evaluate(source)
        }
        return payload
    }

    private fun tapTarget(probe: WebProbe, origin: IntArray, target: JSONObject?): Boolean {
        if (target == null) return false
        val rect = target.optJSONObject("rect") ?: return false
        val x = origin[0] + rect.optInt("x") + rect.optInt("width") / 2
        val y = origin[1] + rect.optInt("y") + rect.optInt("height") / 2
        DeviceEvidence.shell("input tap $x $y")
        return true
    }

    private fun collectFilter(probe: WebProbe, width: Int, origin: IntArray) {
        val entries = probeUntil(probe, FIND_FILTER, 8_000) { it.optBoolean("found") }
        DeviceEvidence.writeJson("menu-share-filter-entry-$width.json", entries.toString(2))
        DeviceEvidence.captureScene("menu-share-filter-entry-$width")
        val target = entries.optJSONObject("target") ?: return
        probe.evaluate(FILTER_CLICK)
        Thread.sleep(1_800)
        var open = probeUntil(probe, FILTER_OPEN, 8_000) { it.optBoolean("open") }
        if (!open.optBoolean("open")) {
            tapTarget(probe, origin, target)
            Thread.sleep(1_800)
            open = probeUntil(probe, FILTER_OPEN, 8_000) { it.optBoolean("open") }
        }
        DeviceEvidence.writeJson("menu-share-filter-open-$width.json", open.toString(2))
        DeviceEvidence.captureScene("menu-share-filter-open-$width")
        if (!open.optBoolean("open")) return

        val before = probe.evaluate(PAGE_STATE)
        val option = open.optJSONArray("options")?.let { options ->
            (0 until options.length()).map { options.getJSONObject(it) }
                .firstOrNull { Regex("video", RegexOption.IGNORE_CASE).containsMatchIn(it.optString("text")) }
                ?.optJSONObject("rect")
        }
        if (option != null) {
            DeviceEvidence.shell(
                "input tap ${origin[0] + option.optInt("x") + option.optInt("width") / 2} " +
                    "${origin[1] + option.optInt("y") + option.optInt("height") / 2}",
            )
            Thread.sleep(1_500)
            var after = probe.evaluate(FILTER_OPEN)
            if (!after.optBoolean("open")) after = probe.evaluate(PAGE_STATE)
            DeviceEvidence.writeJson(
                "menu-share-filter-selected-$width.json",
                JSONObject().put("before", before).put("after", after).toString(2),
            )
            DeviceEvidence.captureScene("menu-share-filter-selected-$width")
            if (probe.evaluate(FILTER_OPEN).optBoolean("open")) probe.evaluate(CLOSE_SURFACE)
        }
    }

    private fun collectResultsItemMenu(probe: WebProbe, width: Int, origin: IntArray) {
        val entry = probeUntil(probe, FIND_RESULT_MENU, 10_000) { it.optBoolean("found") }
        DeviceEvidence.writeJson("menu-share-video-entry-$width.json", entry.toString(2))
        if (!entry.optBoolean("found")) return
        tapTarget(probe, origin, entry.optJSONObject("target"))
        Thread.sleep(1_000)
        var menu = probeUntil(probe, MENU_OPEN, 8_000) { it.optBoolean("open") }
        if (!menu.optBoolean("open")) {
            probe.evaluate(RESULT_MENU_CLICK)
            Thread.sleep(1_000)
            menu = probeUntil(probe, MENU_OPEN, 8_000) { it.optBoolean("open") }
        }
        Thread.sleep(1_800)
        menu = probe.evaluate(SHEET_DUMP)
        DeviceEvidence.writeJson("menu-share-video-menu-$width.json", menu.toString(2))
        DeviceEvidence.captureScene("menu-share-video-menu-$width")
        val shareRow = menu.optJSONObject("shareRow")
        if (shareRow != null) {
            tapTarget(probe, origin, shareRow)
            Thread.sleep(1_000)
            DeviceEvidence.writeJson("menu-share-video-menu-share-$width.json", probe.evaluate(SHARED_STATE).toString(2))
        }
        DeviceEvidence.shell("input keyevent KEYCODE_BACK")
        Thread.sleep(600)
    }

    private fun collectRelatedMenu(probe: WebProbe, width: Int, origin: IntArray) {
        val entry = probeUntil(probe, FIND_RELATED_MENU, 10_000) { it.optBoolean("found") }
        DeviceEvidence.writeJson("menu-share-related-entry-$width.json", entry.toString(2))
        if (!entry.optBoolean("found")) return
        tapTarget(probe, origin, entry.optJSONObject("target"))
        Thread.sleep(1_000)
        var menu = probeUntil(probe, MENU_OPEN, 8_000) { it.optBoolean("open") }
        if (!menu.optBoolean("open")) {
            probe.evaluate(RELATED_MENU_CLICK)
            Thread.sleep(1_000)
            menu = probeUntil(probe, MENU_OPEN, 8_000) { it.optBoolean("open") }
        }
        Thread.sleep(1_800)
        menu = probe.evaluate(SHEET_DUMP)
        DeviceEvidence.writeJson("menu-share-related-menu-$width.json", menu.toString(2))
        DeviceEvidence.captureScene("menu-share-related-menu-$width")
        DeviceEvidence.shell("input keyevent KEYCODE_BACK")
        Thread.sleep(600)
    }

    private fun collectShare(probe: WebProbe, width: Int, origin: IntArray) {
        val entry = probeUntil(probe, FIND_SHARE, 8_000) { it.optBoolean("found") }
        entry.put("navigatorShare", probe.evaluate("return {share:typeof navigator.share,canShare:typeof navigator.canShare};"))
        DeviceEvidence.writeJson("menu-share-share-entry-$width.json", entry.toString(2))
        if (!entry.optBoolean("found")) return
        tapTarget(probe, origin, entry.optJSONObject("target"))
        Thread.sleep(1_500)
        val after = probe.evaluate(SHARE_STATE)
        DeviceEvidence.writeJson("menu-share-share-after-$width.json", after.toString(2))
        DeviceEvidence.captureScene("menu-share-share-after-$width")
        // The native sheet is outside the WebView; record the UI tree and close it.
        DeviceEvidence.dumpUi("menu-share-share-ui-$width.xml")
        val dump = DeviceEvidence.shell("cat /data/local/tmp/menu-share-share-ui-$width.xml")
        if (dump.contains("Copy link") || dump.contains("Share with") || dump.contains("Nearby")) {
            DeviceEvidence.shell("input keyevent KEYCODE_BACK")
            Thread.sleep(500)
        } else {
            probe.evaluate(CLOSE_SURFACE)
        }
    }

    private fun collectCommentMenus(probe: WebProbe, width: Int, origin: IntArray) {
        val entry = probeUntil(probe, FIND_COMMENTS_ENTRY, 12_000) { it.optBoolean("found") }
        DeviceEvidence.writeJson("menu-share-comments-entry-$width.json", entry.toString(2))
        if (!entry.optBoolean("found")) return
        tapTarget(probe, origin, entry.optJSONObject("target"))
        var panel = probeUntil(probe, COMMENTS_OPEN, 12_000) { it.optBoolean("open") }
        if (!panel.optBoolean("open")) {
            probe.evaluate(COMMENTS_ENTRY_CLICK)
            panel = probeUntil(probe, COMMENTS_OPEN, 12_000) { it.optBoolean("open") }
        }
        if (!panel.optBoolean("open")) {
            DeviceEvidence.writeJson("menu-share-comments-open-$width.json", panel.toString(2))
            return
        }
        var detail = probeUntil(probe, COMMENT_DETAIL, 15_000) { it.optBoolean("hasComment") }
        if (!detail.optBoolean("hasComment")) {
            repeat(6) { probe.evaluate(SCROLL_COMMENTS); Thread.sleep(600) }
            detail = probeUntil(probe, COMMENT_DETAIL, 10_000) { it.optBoolean("hasComment") }
        }
        DeviceEvidence.writeJson("menu-share-comment-detail-$width.json", detail.toString(2))
        if (!detail.optBoolean("hasComment")) return

        tapTarget(probe, origin, detail.optJSONObject("target"))
        Thread.sleep(900)
        var menu = probeUntil(probe, MENU_OPEN, 8_000) { it.optBoolean("open") }
        if (!menu.optBoolean("open")) {
            probe.evaluate(COMMENT_MENU_CLICK)
            Thread.sleep(900)
            menu = probeUntil(probe, MENU_OPEN, 8_000) { it.optBoolean("open") }
        }
        Thread.sleep(1_400)
        menu = probe.evaluate(SHEET_DUMP)
        DeviceEvidence.writeJson("menu-share-comment-menu-$width.json", menu.toString(2))
        DeviceEvidence.captureScene("menu-share-comment-menu-$width")
        if (probe.evaluate(SHEET_OPEN_STATE).optBoolean("open")) {
            DeviceEvidence.shell("input keyevent KEYCODE_BACK"); Thread.sleep(600)
        }

        val expand = probe.evaluate(EXPAND_REPLIES)
        DeviceEvidence.writeJson("menu-share-replies-expand-$width.json", expand.toString(2))
        if (expand.optBoolean("clicked")) {
            tapTarget(probe, origin, expand.optJSONObject("rect")?.let { JSONObject().put("rect", it) })
            Thread.sleep(1_400)
        }
        var reply = probeUntil(probe, FIND_REPLY_MENU, 10_000) { it.optBoolean("found") }
        if (!reply.optBoolean("found")) {
            probe.evaluate(SCROLL_COMMENTS)
            Thread.sleep(700)
            reply = probeUntil(probe, FIND_REPLY_MENU, 8_000) { it.optBoolean("found") }
        }
        DeviceEvidence.writeJson("menu-share-reply-entry-$width.json", reply.toString(2))
        if (!reply.optBoolean("found")) return
        tapTarget(probe, origin, reply.optJSONObject("target"))
        Thread.sleep(900)
        var replyMenu = probeUntil(probe, MENU_OPEN, 8_000) { it.optBoolean("open") }
        if (!replyMenu.optBoolean("open")) {
            probe.evaluate(REPLY_MENU_CLICK)
            Thread.sleep(900)
            replyMenu = probeUntil(probe, MENU_OPEN, 8_000) { it.optBoolean("open") }
        }
        Thread.sleep(1_400)
        replyMenu = probe.evaluate(SHEET_DUMP)
        DeviceEvidence.writeJson("menu-share-reply-menu-$width.json", replyMenu.toString(2))
        DeviceEvidence.captureScene("menu-share-reply-menu-$width")
        DeviceEvidence.shell("input keyevent KEYCODE_BACK")
        Thread.sleep(400)
    }

    private companion object {
        val JS_COMMON = """
            const vis = e => { const r = e.getBoundingClientRect(); const s = getComputedStyle(e);
              return r.width > 0 && r.height > 0 && s.visibility !== 'hidden' && s.display !== 'none' && Number(s.opacity) > 0; };
            const R = e => { const r = e.getBoundingClientRect(); return {x:r.x,y:r.y,width:r.width,height:r.height}; };
            const S = e => { const s = getComputedStyle(e); return {fontSize:s.fontSize,fontWeight:s.fontWeight,lineHeight:s.lineHeight,
              fontFamily:s.fontFamily,letterSpacing:s.letterSpacing,color:s.color,backgroundColor:s.backgroundColor,padding:s.padding,
              margin:s.margin,borderRadius:s.borderRadius,boxShadow:s.boxShadow,display:s.display,flexDirection:s.flexDirection,
              alignItems:s.alignItems,justifyContent:s.justifyContent,gap:s.gap,position:s.position,overflow:s.overflow,
              width:s.width,height:s.height,minWidth:s.minWidth,maxWidth:s.maxWidth,whiteSpace:s.whiteSpace,textOverflow:s.textOverflow,
              webkitLineClamp:s.webkitLineClamp,opacity:s.opacity,zIndex:s.zIndex,transform:s.transform,top:s.top,left:s.left,
              border:s.border,boxSizing:s.boxSizing}; };
            const svgOf = e => [...e.querySelectorAll('svg')].map(s => s.outerHTML);
            const label = e => (e.getAttribute('aria-label') || e.innerText || '').trim().replace(/\s+/g, ' ').slice(0, 100);
            const full = (e, deep) => ({tag:e.tagName, cls:String(e.className).slice(0,120), rect:R(e), style:S(e),
              text:(e.innerText||'').trim().slice(0,120), label:label(e), role:e.getAttribute('role')||'',
              svg:svgOf(e), html: deep ? (e.outerHTML||'').slice(0, 1600) : ''});
            const descendants = (e, limit) => [...e.querySelectorAll('*')].filter(vis).slice(0, limit).map(n => full(n, false));
            const PAGE_STATE = {url:location.href, items:document.querySelectorAll('ytm-video-with-context-renderer').length,
              bodyText:(document.body.innerText||'').slice(0,300), scrollY};
        """

        val COLLECT_PAGE = JS_COMMON + """
            const filters = [...document.querySelectorAll('button, [role="button"], a, ytm-chip-cloud-chip-renderer')]
              .filter(vis).filter(e => /filter|refine/i.test(label(e))).slice(0, 12).map(e => full(e, true));
            const chips = [...document.querySelectorAll('ytm-chip-cloud-chip-renderer, ytm-chip-cloud-renderer [role="tab"], [role="tab"]')]
              .filter(vis).slice(0, 20).map(e => full(e, true));
            const searchBoxes = [...document.querySelectorAll('ytm-searchbox, ytm-search-box, form, [role="search"]')]
              .filter(vis).slice(0, 4).map(e => ({tag:e.tagName, rect:R(e), style:S(e), html:(e.outerHTML||'').slice(0,2500)}));
            return {ready: document.querySelectorAll('ytm-video-with-context-renderer').length > 0,
              url:location.href, viewport:{width:innerWidth,height:innerHeight,dpr:devicePixelRatio,clientWidth:document.documentElement.clientWidth},
              ua:navigator.userAgent, lang:document.documentElement.lang, filters, chips, searchBoxes,
              bodyText:(document.body.innerText||'').slice(0,500)};
        """.trimIndent()

        const val PAGE_STATE = """
            const out = {url:location.href, items:document.querySelectorAll('ytm-video-with-context-renderer').length,
              bodyText:(document.body.innerText||'').slice(0,300), scrollY};
            return out;
        """

        val FIND_FILTER = JS_COMMON + """
            const candidates = [...document.querySelectorAll('button, [role="button"], a, ytm-chip-cloud-chip-renderer')].filter(vis);
            const target = candidates.find(e => /^filters?$/i.test(label(e).trim())) ||
              candidates.find(e => /filter/i.test(label(e))) ||
              candidates.find(e => { const s = svgOf(e).join(''); return /filter|tune/i.test(s) && e.querySelector('svg'); });
            if (target) target.scrollIntoView({block:'center', inline:'nearest'});
            return {found:!!target, target: target ? {...full(target, true), rect: target ? R(target) : null} : null,
              candidates: candidates.slice(0, 60).map(e => ({label:label(e), rect:R(e), tag:e.tagName})),
              page: PAGE_STATE};
        """.trimIndent()

        val FILTER_OPEN = JS_COMMON + """
            const texts = [...document.querySelectorAll('*')].filter(vis)
              .filter(e => /upload date|sort by|^type$|^duration$|^features$|^filters?$/i.test((e.innerText||'').trim()));
            const sheet = [...document.querySelectorAll('div, ytm-action-sheet-renderer, ytm-search-filter-renderer')]
              .filter(e => vis(e) && getComputedStyle(e).position === 'fixed')
              .filter(e => Number(getComputedStyle(e).zIndex) >= 10 && (e.innerText||'').trim().length > 0)
              .filter(e => !/TOPBAR|PIVOT|ENGAGEMENT-PANEL|SCRIM/.test(e.tagName));
            return {open: sheet.length > 0 && texts.length > 0, hash: location.hash, url: location.href,
              markerCount: texts.length, markers: texts.slice(0, 20).map(e => ({tag:e.tagName, rect:R(e), text:(e.innerText||'').trim().slice(0,80)})),
              sheets: sheet.slice(0, 3).map(e => full(e, true)),
              sheetHtml: sheet.slice(0, 3).map(e => (e.outerHTML||'').slice(0, 6000)),
              options: [...document.querySelectorAll('button, [role="button"], [role="radio"], [role="checkbox"], label, ytm-chip-cloud-chip-renderer')]
                .filter(vis).map(e => ({...full(e, true), selected: e.getAttribute('aria-selected') || e.getAttribute('aria-checked') || ''})).slice(0, 40),
              page: PAGE_STATE, fixedOverlays: [...document.querySelectorAll('body *')].filter(e => vis(e) && getComputedStyle(e).position === 'fixed')
                .filter(e => Number(getComputedStyle(e).zIndex) >= 2).slice(0, 12).map(e => full(e, false))};
        """.trimIndent()

        const val FILTER_CLICK = """
            const vis = e => { const r = e.getBoundingClientRect(); return r.width > 0 && r.height > 0; };
            const el = [...document.querySelectorAll('button, [role="button"], ytm-chip-cloud-chip-renderer')].filter(vis)
              .find(e => /filter/i.test((e.getAttribute('aria-label')||e.innerText||'')));
            if (el) { el.click(); return {clicked:true}; } return {clicked:false};
        """

        val MENU_OPEN = JS_COMMON + """
            const candidates = [...document.querySelectorAll('*')].filter(e => vis(e) && (
              /ACTION-SHEET|BOTTOM-SHEET|SHEET-VIEW|DIALOG|POPUP|MENU-RENDERER/.test(e.tagName) ||
              e.getAttribute('role') === 'dialog' || e.getAttribute('role') === 'menu'));
            const roots = candidates.filter(e => !candidates.some(o => o !== e && o.contains(e))).slice(0, 3);
            const rows = roots.flatMap(r => [...r.querySelectorAll('button, [role="menuitem"], [role="button"], a')])
              .filter(vis).map(e => ({...full(e, true), selected: e.getAttribute('aria-selected')}));
            return {open: roots.some(r => (r.innerText||'').trim().length > 0), roots: roots.map(e => full(e, true)),
              rows: rows.slice(0, 30), page: PAGE_STATE,
              fixedOverlays: [...document.querySelectorAll('body *')].filter(e => vis(e) && getComputedStyle(e).position === 'fixed')
                .slice(0, 8).map(e => full(e, false))};
        """.trimIndent()

        const val RESULT_MENU_CLICK = """
            const vis = e => { const r = e.getBoundingClientRect(); return r.width > 0 && r.height > 0; };
            const item = [...document.querySelectorAll('ytm-video-with-context-renderer')].filter(vis)[0];
            if (!item) return {clicked:false};
            const b = [...item.querySelectorAll('button, [role="button"]')].filter(vis)
              .find(e => /action menu|more actions|menu/i.test(e.getAttribute('aria-label')||''));
            if (!b) return {clicked:false}; b.click(); return {clicked:true};
        """

        val FIND_RESULT_MENU = JS_COMMON + """
            const items = [...document.querySelectorAll('ytm-video-with-context-renderer, ytm-compact-video-renderer')].filter(vis);
            let item = items.find(e => [...e.querySelectorAll('button, [role="button"]')].filter(vis)
              .some(b => /action menu|more actions|more options|menu/i.test(label(b))));
            item = item || items[0];
            const buttons = item ? [...item.querySelectorAll('button, [role="button"]')].filter(vis) : [];
            const target = buttons.find(e => /action menu|more actions|menu|more options/i.test(label(e)));
            if (target) { target.scrollIntoView({block:'center', inline:'nearest'}); }
            const r = target ? R(target) : null;
            return {found:!!target && r.y >= 0 && r.y < innerHeight, target: target ? {...full(target, true), rect:r} : null,
              buttons: buttons.map(e => ({label:label(e), rect:R(e), tag:e.tagName})),
              item: item ? {rect:R(item), text:(item.innerText||'').slice(0,160)} : null, scrollY};
        """.trimIndent()

        val COLLECT_WATCH_PAGE = JS_COMMON + """
            return {ready: document.querySelectorAll('ytm-compact-video-renderer, ytm-video-with-context-renderer').length > 0,
              url:location.href, viewport:{width:innerWidth,height:innerHeight,dpr:devicePixelRatio},
              title:(document.querySelector('h1, .slim-video-information-title')?.innerText||'').slice(0,120),
              actionButtons:[...document.querySelectorAll('button, [role="button"]')].filter(vis)
                .map(e => ({label:label(e), rect:R(e), tag:e.tagName})).filter(o => /share|like|dislike|save|download|more/i.test(o.label)).slice(0,20)};
        """.trimIndent()

        val FIND_RELATED_MENU = JS_COMMON + """
            const items = [...document.querySelectorAll('ytm-compact-video-renderer, ytm-video-with-context-renderer')].filter(vis);
            for (const item of items) {
              const buttons = [...item.querySelectorAll('button, [role="button"]')].filter(vis);
              const b = buttons.find(e => /action menu|more actions|more options|menu/i.test(label(e)));
              if (b) {
                b.scrollIntoView({block:'center', inline:'nearest'});
                const r = R(b);
                return {found:true, target:{...full(b, true), rect:r}, item:{rect:R(item), text:(item.innerText||'').slice(0,120)}, scrollY};
              }
            }
            return {found:false, count:items.length, items:items.slice(0,5).map(e => ({rect:R(e), text:(e.innerText||'').slice(0,90)}))};
        """.trimIndent()

        const val RELATED_MENU_CLICK = """
            const vis = e => { const r = e.getBoundingClientRect(); return r.width > 0 && r.height > 0; };
            for (const item of [...document.querySelectorAll('ytm-compact-video-renderer, ytm-video-with-context-renderer')].filter(vis)) {
              const b = [...item.querySelectorAll('button, [role="button"]')].filter(vis)
                .find(e => /action menu|more actions|menu/i.test(e.getAttribute('aria-label')||''));
              if (b) { b.click(); return {clicked:true}; }
            }
            return {clicked:false};
        """

        val FIND_SHARE = JS_COMMON + """
            const buttons = [...document.querySelectorAll('button, [role="button"], a')].filter(vis);
            const target = buttons.find(e => /^share$/i.test(label(e))) ||
              buttons.find(e => /share/i.test(label(e)));
            if (target) target.scrollIntoView({block:'center', inline:'nearest'});
            return {found:!!target, target:target ? {...full(target, true), rect: target ? R(target) : null} : null,
              buttons:buttons.filter(e => /share/i.test(label(e))).map(e => full(e, true)).slice(0,6)};
        """.trimIndent()

        val SHARE_STATE = JS_COMMON + """
            const candidates = [...document.querySelectorAll('*')].filter(e => vis(e) && (
              /SHARE|ACTION-SHEET|BOTTOM-SHEET|SHEET-VIEW|DIALOG/.test(e.tagName) || e.getAttribute('role') === 'dialog'));
            const roots = candidates.filter(e => !candidates.some(o => o !== e && o.contains(e))).slice(0, 3);
            return {navigatorShare:typeof navigator.share, canShare:typeof navigator.canShare,
              open:roots.length > 0, roots:roots.map(e => full(e, true)), page:PAGE_STATE,
              bodyText:(document.body.innerText||'').slice(0,600)};
        """.trimIndent()

        val FIND_COMMENTS_ENTRY = JS_COMMON + """
            const buttons = [...document.querySelectorAll('button, [role="button"], a')].filter(vis);
            const target = buttons.find(e => /^comments?$|view comments|comments/i.test(label(e)) && /comment/i.test(label(e)));
            if (target) target.scrollIntoView({block:'center', inline:'nearest'});
            return {found:!!target, target:target ? {...full(target, true), rect: target ? R(target) : null} : null,
              buttons:buttons.filter(e => /comment/i.test(label(e))).map(e => full(e, true)).slice(0,8)};
        """.trimIndent()

        const val COMMENTS_ENTRY_CLICK = """
            const vis = e => { const r = e.getBoundingClientRect(); return r.width > 0 && r.height > 0; };
            const b = [...document.querySelectorAll('button, [role="button"], a')].filter(vis)
              .find(e => /comment/i.test((e.getAttribute('aria-label')||e.innerText||'')));
            if (b) { b.click(); return {clicked:true}; } return {clicked:false};
        """

        val COMMENTS_OPEN = JS_COMMON + """
            const panels = [...document.querySelectorAll('ytm-engagement-panel, ytm-comments-renderer, ytm-comments-section-renderer')].filter(vis);
            const threads = [...document.querySelectorAll('ytm-comment-renderer')].filter(vis);
            return {open: panels.length > 0 && threads.length > 0, panelCount: panels.length, threadCount: threads.length,
              panels: panels.slice(0,4).map(e => ({tag:e.tagName, rect:R(e), text:(e.innerText||'').slice(0,80)})), page:PAGE_STATE};
        """.trimIndent()

        val COMMENT_DETAIL = JS_COMMON + """
            const threads = [...document.querySelectorAll('ytm-comment-renderer')].filter(vis);
            let target = null, host = null;
            for (const t of threads) {
              const b = [...t.querySelectorAll('button, [role="button"]')].filter(vis)
                .find(e => /action menu|more options|more actions|menu/i.test(label(e)));
              if (b) { target = b; host = t; break; }
            }
            if (target) target.scrollIntoView({block:'center', inline:'nearest'});
            const rect = target ? R(target) : null;
            const replies = [...document.querySelectorAll('ytm-comment-replies-renderer')].filter(vis);
            return {hasComment: threads.length > 0, found:!!target, target: target ? {...full(target, true), rect, threadText:(host.innerText||'').slice(0,160)} : null,
              comments: threads.slice(0, 4).map(e => ({rect:R(e), text:(e.innerText||'').slice(0,140),
                buttons:[...e.querySelectorAll('button, [role="button"]')].filter(vis).map(b => ({label:label(b), rect:R(b)}))})),
              replyHosts: replies.map(e => ({rect:R(e), text:(e.innerText||'').slice(0,120)})), scrollY};
        """.trimIndent()

        const val COMMENT_MENU_CLICK = """
            const vis = e => { const r = e.getBoundingClientRect(); return r.width > 0 && r.height > 0; };
            const t = [...document.querySelectorAll('ytm-comment-renderer')].filter(vis)[0];
            if (!t) return {clicked:false};
            const b = [...t.querySelectorAll('button, [role="button"]')].filter(vis)
              .find(e => /action menu|more actions|menu/i.test(e.getAttribute('aria-label')||''));
            if (b) { b.click(); return {clicked:true}; } return {clicked:false};
        """

        const val SCROLL_COMMENTS = """
            const vis = e => { const r = e.getBoundingClientRect(); return r.width > 0 && r.height > 0; };
            const list = [...document.querySelectorAll('ytm-comments-renderer, ytm-comment-section-renderer, ytm-comments-section-renderer')].filter(vis)[0];
            if (list) { list.scrollTop += 500; window.scrollBy(0, 400); return {scrolled:true}; }
            window.scrollBy(0, 500); return {scrolled:false};
        """

        val EXPAND_REPLIES = JS_COMMON + """
            const hosts = [...document.querySelectorAll('ytm-comment-replies-renderer')].filter(vis);
            let b = null, host = null;
            for (const h of hosts) {
              const candidate = [...h.querySelectorAll('button, [role="button"]')].filter(vis)
                .find(e => /repl/i.test(label(e)));
              if (candidate) { b = candidate; host = h; break; }
            }
            if (!b) {
              b = [...document.querySelectorAll('button, [role="button"]')].filter(vis)
                .find(e => /repl/i.test(label(e)));
            }
            if (b) b.scrollIntoView({block:'center', inline:'nearest'});
            return {clicked:!!b, rect:b ? R(b) : null, label:b ? label(b) : '', hostRect:host ? R(host) : null,
              hosts:hosts.map(h => ({rect:R(h), text:(h.innerText||'').slice(0,100)})),
              all:[...document.querySelectorAll('button, [role="button"]')].filter(vis).filter(e => /repl/i.test(label(e))).slice(0,8).map(e => ({label:label(e), rect:R(e)}))};
        """.trimIndent()

        val FIND_REPLY_MENU = JS_COMMON + """
            const all = [...document.querySelectorAll('ytm-comment-renderer')].filter(vis);
            const replies = all.slice(1);
            for (const t of replies) {
              const b = [...t.querySelectorAll('button, [role="button"]')].filter(vis)
                .find(e => /action menu|more options|more actions|menu/i.test(label(e)));
              if (b) { b.scrollIntoView({block:'center', inline:'nearest'}); return {found:true,
                target:{...full(b, true), rect:R(b)}, reply:{rect:R(t), text:(t.innerText||'').slice(0,140)}}; }
            }
            return {found:false, count:all.length, comments:all.slice(0,5).map(e => ({rect:R(e), text:(e.innerText||'').slice(0,100)}))};
        """.trimIndent()

        const val REPLY_MENU_CLICK = """
            const vis = e => { const r = e.getBoundingClientRect(); return r.width > 0 && r.height > 0; };
            const all = [...document.querySelectorAll('ytm-comment-renderer')].filter(vis).slice(1);
            for (const t of all) {
              const b = [...t.querySelectorAll('button, [role="button"]')].filter(vis)
                .find(e => /action menu|more actions|menu/i.test(e.getAttribute('aria-label')||''));
              if (b) { b.click(); return {clicked:true}; }
            }
            return {clicked:false};
        """

        const val INSTALL_SHARE_STUB = """
            try { Object.defineProperty(navigator, 'share', {configurable: true, value: d => { window.__sharedData = d; return Promise.resolve(); }}); } catch (e) {}
            try { Object.defineProperty(navigator, 'canShare', {configurable: true, value: () => true}); } catch (e) {}
            return {share: typeof navigator.share};
        """

        const val SHARED_STATE = """
            return {shared: window.__sharedData || null, hash: location.hash, url: location.href,
              bodyText:(document.body.innerText||'').slice(0,300)};
        """

        val SHEET_DUMP = JS_COMMON + """
            const fixed = [...document.querySelectorAll('body div')]
              .filter(vis).filter(e => getComputedStyle(e).position === 'fixed')
              .filter(e => Number(getComputedStyle(e).zIndex) >= 10)
              .filter(e => (e.innerText||'').trim().length > 0 && R(e).height > 40 && R(e).width > 120);
            const roots = fixed.filter(e => !fixed.some(o => o !== e && o.contains(e)));
            roots.sort((a,b) => R(b).y - R(a).y);
            const root = roots[0] || null;
            const rows = root ? [...root.querySelectorAll('button, [role="button"], [role="menuitem"], a')].filter(vis)
              .map(e => ({...full(e, true), selected:e.getAttribute('aria-selected')||e.getAttribute('aria-checked')||''})) : [];
            const shareRow = rows.find(r => /^share$/i.test((r.label||'').trim())) || rows.find(r => /share/i.test(r.label||''));
            return {open: roots.length > 0, hash:location.hash, root: root ? full(root, true) : null,
              rootHtml: root ? (root.outerHTML||'').slice(0, 12000) : '', rows,
              shareRow: shareRow ? {rect:shareRow.rect} : null,
              overlays: roots.slice(0,3).map(e => ({tag:e.tagName, rect:R(e), text:(e.innerText||'').slice(0,200)})),
              page:PAGE_STATE};
        """.trimIndent()

        val FILTER_SURFACE = JS_COMMON + """
            const names = ['All','Channels','Playlists','Anytime','Today','This week','This month','This year','Videos','Shorts','Live'];
            const hits = [...document.querySelectorAll('*')].filter(e => {
              const t = (e.innerText || '').trim();
              return names.includes(t) && e.children.length === 0;
            }).slice(0, 80).map(e => {
              const chain = []; let n = e;
              for (let i = 0; i < 5 && n; i++) { chain.push({tag:n.tagName, cls:String(n.className).slice(0,80), rect:R(n), style:S(n)}); n = n.parentElement; }
              return {text:(e.innerText||'').trim(), tag:e.tagName, rect:R(e), style:S(e), chain, html:(e.parentElement?.outerHTML||'').slice(0,500)};
            });
            const hosts = [...document.querySelectorAll('*')].filter(e => /FILTER|CHIP|SEARCH-REFINEMENT/.test(e.tagName)).slice(0, 40)
              .map(e => ({tag:e.tagName, rect:R(e), style:S(e), text:(e.innerText||'').slice(0,120), visible:vis(e), html:(e.outerHTML||'').slice(0,1200)}));
            const groups = [...document.querySelectorAll('ytm-search-filter-group-renderer')].map(g => {
              const select = g.querySelector('select');
              const host = g.querySelector('ytm-select');
              return {rect:R(g), style:S(g), selectRect:select?R(select):null, selectStyle:select?S(select):null,
                hostRect:host?R(host):null, hostStyle:host?S(host):null,
                hostBg:host?getComputedStyle(host).backgroundColor:null, hostBorder:host?getComputedStyle(host).border:null,
                hostRadius:host?getComputedStyle(host).borderRadius:null, hostBox:host?getComputedStyle(host).boxShadow:null,
                containerBg:g.parentElement?getComputedStyle(g.parentElement).backgroundColor:null,
                containerBorder:g.parentElement?getComputedStyle(g.parentElement).borderBottom:null,
                options:select?[...select.options].map(o => ({text:o.text.trim(), selected:o.selected})):[],
                iconRect:g.querySelector('c3-icon')?R(g.querySelector('c3-icon')):null,
                iconSvg:g.querySelector('svg')?g.querySelector('svg').outerHTML:'', html:(g.outerHTML||'').slice(0,3000)};
            });
            return {hash:location.hash, hits, hosts, groups, container:(() => { const g=document.querySelector('ytm-search-filter-group-renderer'); if(!g) return null; const p=g.parentElement; return {tag:p.tagName, rect:R(p), style:S(p), childCount:p.children.length}; })()};
        """.trimIndent()

        val FILTER_HIDDEN = JS_COMMON + """
            const all = [...document.querySelectorAll('*')].filter(e => /upload date|anytime|this week|this month|this year|sort by/i.test(e.innerText||''));
            return {count: all.length, nodes: all.slice(0,30).map(e => ({tag:e.tagName, cls:String(e.className).slice(0,80), rect:R(e), style:S(e), visible:vis(e),
              text:(e.innerText||'').slice(0,100), html:(e.outerHTML||'').slice(0,1000)}))};
        """.trimIndent()

        val FILTER_CSS = JS_COMMON + """
            const out = [];
            const walk = (rules, contexts=[]) => { for (const r of rules) {
              if (r.selectorText && /filter|chip|refine/i.test(r.selectorText)) { let css=r.cssText; for (const c of [...contexts].reverse()) css=c+' { '+css+' }'; out.push(css); }
              else if (r.cssRules) { const p=r.cssText.slice(0,r.cssText.indexOf('{')).trim(); walk(r.cssRules,[...contexts,p]); }
            }};
            for (const sheet of document.styleSheets) { let rules=null; try { rules=sheet.cssRules; } catch(e) {} if (rules) walk(rules); }
            return {rules:out.slice(0,400)};
        """.trimIndent()

        val FILTER_SELECT_CHANNELS = JS_COMMON + """
            const groups = [...document.querySelectorAll('ytm-search-filter-group-renderer')];
            if (!groups.length) return {selected:false, reason:'no groups'};
            const group = groups[0];
            const select = group.querySelector('select');
            const options = [...select.options].map(o => o.text.trim());
            select.selectedIndex = options.findIndex(t => /channels/i.test(t));
            select.dispatchEvent(new Event('change', {bubbles:true}));
            return {selected:true, options, index:select.selectedIndex, value:select.value};
        """.trimIndent()

        val FILTER_SELECT_TODAY = JS_COMMON + """
            const groups = [...document.querySelectorAll('ytm-search-filter-group-renderer')];
            const group = groups.find(g => [...g.querySelectorAll('option')].some(o => /anytime/i.test(o.text)));
            if (!group) return {selected:false};
            const select = group.querySelector('select');
            const options = [...select.options].map(o => o.text.trim());
            select.selectedIndex = options.findIndex(t => /today/i.test(t));
            select.dispatchEvent(new Event('change', {bubbles:true}));
            return {selected:true, options, index:select.selectedIndex, value:select.value};
        """.trimIndent()

        val PAGE_STATE_FULL = JS_COMMON + """
            const out = PAGE_STATE;
            out.filterTexts = [...document.querySelectorAll('*')].filter(e => e.children.length === 0 && /^(All|Videos|Channels|Playlists|Anytime|Today|This week|This month|This year)$/.test((e.innerText||'').trim()))
              .map(e => ({text:(e.innerText||'').trim(), rect:R(e)}));
            return out;
        """.trimIndent()

        val FIND_STANDARD_VIDEO_MENU = JS_COMMON + """
            const items = [...document.querySelectorAll('ytm-video-with-context-renderer, ytm-compact-video-renderer')].filter(vis);
            let item = null, target = null;
            for (const e of items) {
              const text = e.innerText || '';
              if (/playlist/i.test(text)) continue;
              const b = [...e.querySelectorAll('button, [role="button"]')].filter(vis)
                .find(x => /action menu|more actions|more options/i.test(label(x)));
              if (b) { item = e; target = b; break; }
            }
            if (target) target.scrollIntoView({block:'center'});
            return {found:!!target, target: target ? {...full(target, true), rect:R(target)} : null,
              item: item ? {rect:R(item), text:(item.innerText||'').slice(0,200)} : null,
              candidates: items.slice(0,6).map(e => ({text:(e.innerText||'').slice(0,120), rect:R(e)}))};
        """.trimIndent()

        const val SHEET_OPEN_STATE = """
            return {open: location.hash.includes('bottom-sheet') || [...document.querySelectorAll('div')].some(e => {
              const s = getComputedStyle(e); const r = e.getBoundingClientRect();
              return s.position === 'fixed' && Number(s.zIndex) >= 100 && r.height > 60 && r.width > 120 && (e.innerText||'').trim().length > 0;
            }), hash:location.hash};
        """

        val FIND_SHARE_SCROLL = JS_COMMON + """
            window.scrollTo(0, 0);
            const buttons = [...document.querySelectorAll('button, [role="button"], a')].filter(vis);
            const target = buttons.find(e => /^share$/i.test(label(e))) || buttons.find(e => /share/i.test(label(e)));
            if (target) target.scrollIntoView({block:'center'});
            return {found:!!target, target: target ? {...full(target, true), rect:target ? R(target) : null} : null,
              shareButtons: buttons.filter(e => /share/i.test(label(e))).map(e => full(e, true)).slice(0,6),
              allLabels: buttons.map(e => label(e)).slice(0,80)};
        """.trimIndent()

        val COMMENT_ELEMENT_AT = JS_COMMON + """
            const threads = [...document.querySelectorAll('ytm-comment-renderer')].filter(vis);
            const t = threads[0];
            const b = t ? [...t.querySelectorAll('button, [role="button"]')].filter(vis).find(e => /more options|action menu/i.test(label(e))) : null;
            if (!b) return {found:false};
            const r = b.getBoundingClientRect();
            const x = r.x + r.width / 2, y = r.y + r.height / 2;
            const stack = document.elementsFromPoint(x, y).slice(0, 6).map(e => ({tag:e.tagName, cls:String(e.className).slice(0,90),
              label:(e.getAttribute('aria-label')||e.innerText||'').trim().slice(0,80), rect:R(e), style:S(e)}));
            return {found:true, x, y, kebab:{...full(b,true), rect:R(b), disabled:b.disabled, tabIndex:b.tabIndex},
              stack, signedOut:/sign in/i.test(document.body.innerText||'')};
        """.trimIndent()

        val COMMENT_MENU_JS_STATE = JS_COMMON + """
            const dialogs = [...document.querySelectorAll('.dialog-container, ytm-dialog-renderer, [role="dialog"], ytm-bottom-sheet-renderer')]
              .filter(e => vis(e) && (e.innerText||'').trim().length > 0)
              .map(e => ({tag:e.tagName, cls:String(e.className).slice(0,100), rect:R(e), style:S(e), text:(e.innerText||'').trim().slice(0,300),
                html:(e.outerHTML||'').slice(0,2500)}));
            const textHits = [...document.querySelectorAll('*')].filter(e => e.children.length === 0 &&
              /^(reply|report|edit|delete|copy link|save to watch later|save to playlist|share)$/i.test((e.innerText||'').trim()))
              .map(e => ({text:(e.innerText||'').trim(), rect:R(e), parent:e.parentElement?.tagName||'',
                parentHtml:(e.parentElement?.outerHTML||'').slice(0,800)}));
            const kebabs = [...document.querySelectorAll('button, [role="button"]')].filter(vis)
              .filter(e => /more options|action menu/i.test(label(e))).slice(0,5)
              .map(e => ({label:label(e), rect:R(e), expanded:e.getAttribute('aria-expanded'), html:(e.outerHTML||'').slice(0,600)}));
            return {hash:location.hash, dialogs, textHits, kebabs, bodyText:(document.body.innerText||'').slice(0,1200)};
        """.trimIndent()

        val MENU_ANY = JS_COMMON + """
            const candidates = [...document.querySelectorAll('*')].filter(e => {
              const tag = e.tagName; const role = e.getAttribute('role') || '';
              return /MENU|POPUP|DIALOG|SHEET/.test(tag) || role === 'menu' || role === 'menuitem' || role === 'dialog';
            });
            const visible = candidates.filter(vis).map(e => ({tag:e.tagName, cls:String(e.className).slice(0,90), rect:R(e), style:S(e),
              text:(e.innerText||'').trim().slice(0,200), role:e.getAttribute('role')||'', html:(e.outerHTML||'').slice(0,1500)}));
            const textHits = [...document.querySelectorAll('*')].filter(e => e.children.length === 0 && /^(reply|report|edit|delete|copy link)$/i.test((e.innerText||'').trim()))
              .map(e => { let p=e; for(let i=0;i<4&&p;i++) p=p.parentElement; return {text:(e.innerText||'').trim(), rect:R(e), parentTag:p?p.tagName:'', parentCls:p?String(p.className).slice(0,90):'', parentHtml:p?(p.outerHTML||'').slice(0,1200):''}; });
            return {hash:location.hash, count:visible.length, visible:visible.slice(0,20), textHits: textHits.slice(0,20),
              tail:[...document.body.children].slice(-4).map(e => ({tag:e.tagName, cls:String(e.className).slice(0,80), rect:R(e), text:(e.innerText||'').slice(0,120)}))};
        """.trimIndent()

        val COMMENT_MENU_TARGET = JS_COMMON + """
            const threads = [...document.querySelectorAll('ytm-comment-renderer')].filter(vis);
            for (const t of threads) {
              const b = [...t.querySelectorAll('button, [role="button"]')].filter(vis)
                .find(e => /more options|action menu|more actions/i.test(label(e)));
              if (b) { b.scrollIntoView({block:'center'}); return {found:true, target:{...full(b,true), rect:R(b)},
                thread:(t.innerText||'').slice(0,120)}; }
            }
            return {found:false, count:threads.length};
        """.trimIndent()

        val COMMENT_MENU_TARGET_ALL = JS_COMMON + """
            const all = [...document.querySelectorAll('ytm-comment-renderer')].filter(vis);
            return {count:all.length, hasReply:all.length > 1 && all.slice(1).some(t => [...t.querySelectorAll('button')].some(b => /more options/i.test(label(b))))};
        """.trimIndent()

        val REPLY_TOGGLE = JS_COMMON + """
            const vis2 = e => { const r=e.getBoundingClientRect(); return r.width>0 && r.height>0; };
            const hosts = [...document.querySelectorAll('ytm-comment-replies-renderer')].filter(vis2);
            let b = null;
            for (const h of hosts) {
              b = [...h.querySelectorAll('button, [role="button"]')].filter(vis2).find(e => /repl/i.test(label(e)));
              if (b) break;
            }
            if (!b) b = [...document.querySelectorAll('button, [role="button"]')].filter(vis2)
              .find(e => /^\s*(view|show|read)?\s*[\d.,KkMm]*\s*repl(y|ies)\s*$/i.test(label(e)));
            if (b) b.scrollIntoView({block:'center'});
            return {found:!!b, target:b ? {...full(b,true), rect:R(b)} : null,
              hosts:hosts.map(h => ({rect:R(h), text:(h.innerText||'').slice(0,100)})),
              labels:[...document.querySelectorAll('button, [role="button"]')].filter(vis2).filter(e => /repl/i.test(label(e))).map(e => ({label:label(e), rect:R(e)})).slice(0,10)};
        """.trimIndent()

        const val REPLY_TOGGLE_CLICK = """
            const vis = e => { const r=e.getBoundingClientRect(); return r.width>0 && r.height>0; };
            const h = [...document.querySelectorAll('ytm-comment-replies-renderer')].filter(vis)[0];
            let b = h ? [...h.querySelectorAll('button, [role="button"]')].filter(vis).find(e => /repl/i.test(e.getAttribute('aria-label')||e.innerText||'')) : null;
            if (!b) b = [...document.querySelectorAll('button, [role="button"]')].filter(vis).find(e => /repl/i.test(e.getAttribute('aria-label')||e.innerText||''));
            if (b) { b.click(); return {clicked:true, label:(b.getAttribute('aria-label')||b.innerText||'').trim()}; }
            return {clicked:false};
        """

        val REPLY_MENU_TARGET = JS_COMMON + """
            const all = [...document.querySelectorAll('ytm-comment-renderer')].filter(vis);
            const replies = all.slice(1);
            for (const t of replies) {
              const b = [...t.querySelectorAll('button, [role="button"]')].filter(vis)
                .find(e => /more options|action menu|more actions/i.test(label(e)));
              if (b) { b.scrollIntoView({block:'center'}); return {found:true, target:{...full(b,true), rect:R(b)},
                reply:(t.innerText||'').slice(0,140)}; }
            }
            return {found:false, count:all.length, texts:all.map(t => (t.innerText||'').slice(0,80))};
        """.trimIndent()

        const val CLOSE_SURFACE = """
            const vis = e => { const r = e.getBoundingClientRect(); return r.width > 0 && r.height > 0; };
            const b = [...document.querySelectorAll('button, [role="button"]')].filter(vis)
              .find(e => /close|cancel|dismiss/i.test((e.getAttribute('aria-label')||e.innerText||'')));
            if (b) { b.click(); return {closed:true}; }
            return {closed:false};
        """
    }
}
