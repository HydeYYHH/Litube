package com.hhst.youtubelite.account

import android.content.Intent
import android.webkit.CookieManager
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewFeature
import com.hhst.youtubelite.data.media.*
import com.hhst.youtubelite.watch.chat.ChatWebSessionStore
import com.hhst.youtubelite.watch.chat.MediaLiveChatGateway
import com.hhst.youtubelite.watch.domain.UnconfirmedActionException
import com.liskovsoft.youtubeapi.videoinfo.V2.chatMessageParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** No live writes in normal test runs. An opted-in invocation can post at most once. */
class LiveChatSendProbeAndroidTest {
    @Test fun inspectWebChatPosting(): Unit = runBlocking(Dispatchers.IO) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("chatReadProbe") == "1")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = ChatWebSessionStore(PreferencesAuthStore(context))
        val report = org.json.JSONArray()
        val http = GlobalContext.get().get<OkHttpClient>().newBuilder().addInterceptor { chain ->
            val endpoint = chain.request().url.encodedPath.substringAfterLast('/')
            check(endpoint in setOf("next", "get_live_chat", "token"))
            chain.proceed(chain.request()).also { response ->
                if (endpoint == "get_live_chat") File(context.filesDir, "chat-posting-private-page.json")
                    .writeText(response.peekBody(4L * 1024 * 1024).string())
            }
        }.build()
        MediaClient(http, MediaSession::freshContext).read { native, transport ->
            val web = requireNotNull(store.context(native))
            for (id in listOf("_wnjeiATFk4", "Dx5qFachd3A", "HqZ1VJ0w_ao", "UrD05Zsz2ms")) {
              try {
                val page = com.liskovsoft.youtubeapi.videoinfo.V2.loadWatch(id, web, http = transport).requireValue()
                val chat = com.liskovsoft.youtubeapi.videoinfo.V2.loadChat(requireNotNull(page.chatKey), web, transport).requireValue()
                val root = JSONObject(File(context.filesDir, "chat-posting-private-page.json").readText())
                val restrictions = mutableListOf<String>()
                fun visit(value: Any?) {
                    when (value) {
                        is JSONObject -> value.keys().forEach { key ->
                            if (key == "liveChatRestrictedParticipationRenderer") restrictions += value.optJSONObject(key)?.optJSONObject("message").toString()
                            visit(value.opt(key))
                        }
                        is org.json.JSONArray -> for (index in 0 until value.length()) visit(value.opt(index))
                    }
                }
                visit(root)
                report.put(JSONObject().put("id", id).put("hasServerSendParams", !chat.sendParams.isNullOrBlank())
                    .put("messageCount", chat.messages.size).put("restrictions", restrictions.joinToString()))
              } catch (error: MediaException) {
                report.put(JSONObject().put("id", id).put("failureCode", error.failure.code))
              } finally {
                File(context.filesDir, "chat-posting-web-read.json").writeText(report.toString(2))
              }
            }
        }
        File(context.filesDir, "chat-posting-web-read.json").writeText(report.toString(2))
    }

    @Test fun authorizeChatInteractively() {
        val args = InstrumentationRegistry.getArguments()
        val interactive = args.getString("chatAuthProbe") == "1"
        val reuse = args.getString("chatReuseProbe") == "1"
        assumeTrue(interactive || reuse || args.getString("chatAuthUiProbe") == "1")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val native = MediaSession.snapshot()
        assertTrue(native.signedIn)
        val store = ChatWebSessionStore(PreferencesAuthStore(context))
        var before: String? = null
        instrumentation.runOnMainSync {
            assertTrue(WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE))
            before = CookieManager.getInstance().getCookie("https://www.youtube.com")
        }
        ActivityScenario.launch<ChatWebSignInActivity>(Intent(context, ChatWebSignInActivity::class.java)).use { scenario ->
            if (reuse) {
                var attempted = false
                val deadline = System.currentTimeMillis() + 60_000L
                while (store.context(native) == null && System.currentTimeMillis() < deadline) {
                    if (!attempted) scenario.onActivity { activity ->
                        val browser = findBrowser(activity.window.decorView)
                        if (android.net.Uri.parse(browser?.url.orEmpty()).host in setOf("www.youtube.com", "m.youtube.com")) {
                            val root = activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0) as android.view.ViewGroup
                            (root.getChildAt(root.childCount - 1) as android.widget.Button).performClick()
                            attempted = true
                        }
                    }
                    Thread.sleep(500)
                }
                assertNotNull("Previously signed-in profile must pass account verification", store.context(native))
                return@use
            }
            if (!interactive) {
                Thread.sleep(15_000)
                val ready = java.util.concurrent.CountDownLatch(1)
                var metadata = ""
                scenario.onActivity { activity ->
                    requireNotNull(findBrowser(activity.window.decorView)).evaluateJavascript(
                        "JSON.stringify({origin:location.origin,title:document.title,hasEmailInput:!!document.querySelector('input[type=email]'),blocked:/browser or app may not be secure|disallowed_useragent/i.test(document.body.innerText)})") {
                        metadata = it; ready.countDown()
                    }
                }
                assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS))
                File(context.filesDir, "chat-auth-page.json").writeText(metadata)
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    File(context.filesDir, "chat-auth-page.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
                assertNull("Opening authorization must not persist an unverified session", store.context(native))
                return@use
            }
            val deadline = System.currentTimeMillis() + 15 * 60_000L
            while (store.context(native) == null && System.currentTimeMillis() < deadline) Thread.sleep(500)
            assertNotNull("Finish web sign-in and tap Continue in the authorization window", store.context(native))
        }
        instrumentation.runOnMainSync {
            assertEquals("Chat sign-in must not change playback cookies", before,
                CookieManager.getInstance().getCookie("https://www.youtube.com"))
        }
    }

    private fun findBrowser(view: android.view.View): android.webkit.WebView? {
        if (view is android.webkit.WebView) return view
        if (view is android.view.ViewGroup) for (index in 0 until view.childCount) findBrowser(view.getChildAt(index))?.let { return it }
        return null
    }

    @Test fun sendOneAuthorizedMessage(): Unit = runBlocking(Dispatchers.IO) {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("chatWriteProbe") == "1")
        val videoId = requireNotNull(args.getString("videoId")) { "Specify the authorized test stream" }
        val message = requireNotNull(args.getString("chatMessage")) { "Specify the authorized test message" }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = ChatWebSessionStore(PreferencesAuthStore(context))
        assertNotNull("Authorize chat before running the write probe", store.context(MediaSession.snapshot()))
        val sends = AtomicInteger()
        val report = JSONObject()
        val http = GlobalContext.get().get<OkHttpClient>().newBuilder().addNetworkInterceptor { chain ->
            val endpoint = chain.request().url.encodedPath.substringAfterLast('/')
            check(endpoint in setOf("next", "player", "get_live_chat", "send_message", "token"))
            if (endpoint == "send_message") check(sends.incrementAndGet() == 1) { "No automatic write retry" }
            chain.proceed(chain.request())
        }.addInterceptor { chain ->
            val endpoint = chain.request().url.encodedPath.substringAfterLast('/')
            chain.proceed(chain.request()).also { response ->
                if (endpoint == "send_message") {
                    val body = response.peekBody(2L * 1024 * 1024).string()
                    // Retained only in app-private test storage for read-only follow-up diagnostics.
                    File(context.filesDir, "chat-send-private-response.json").writeText(body)
                    val root = runCatching { JSONObject(body) }.getOrNull()
                    val kinds = linkedSetOf<String>()
                    fun visit(value: Any?) {
                        when (value) {
                            is JSONObject -> value.keys().forEach { key ->
                                if (key.endsWith("Command") || key.endsWith("Action") || key.endsWith("Renderer")) kinds += key
                                visit(value.opt(key))
                            }
                            is org.json.JSONArray -> for (index in 0 until value.length()) visit(value.opt(index))
                        }
                    }
                    visit(root)
                    report.put("httpStatus", response.code)
                        .put("responseKinds", kinds.sorted().joinToString(","))
                        .put("serviceStatus", root?.optJSONObject("error")?.optString("status"))
                        .put("rootKeys", root?.keys()?.asSequence()?.toList()?.sorted()?.joinToString(","))
                }
            }
        }.build()
        try {
            val client = MediaClient(http, MediaSession::freshContext)
            val video = MediaWatchRepository(client).watch(videoId).video
            assertTrue("Only an active live stream may be tested", video.live)
            val params = requireNotNull(chatMessageParameters(video.id, video.channelId))
            MediaLiveChatGateway(client, store).send(requireNotNull(video.chatKey), params, message)
            report.put("confirmed", true)
        } catch (error: Exception) {
            report.put("confirmed", false).put("failureType", error.javaClass.simpleName)
            if (error is MediaException) report.put("failureCode", error.failure.code)
            if (error is UnconfirmedActionException) report.put("unconfirmed", true)
            throw error
        } finally {
            report.put("writeAttempts", sends.get())
            File(context.filesDir, "chat-send-result.json").writeText(report.toString(2))
        }
    }
}
