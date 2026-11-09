package com.hhst.youtubelite.extractor

import android.webkit.CookieManager
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.data.media.MediaSession
import com.hhst.youtubelite.data.media.MemoryAuthStore
import com.hhst.youtubelite.data.media.PreferencesAuthStore
import com.hhst.youtubelite.diagnostics.DiagnosticContext
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestContext
import kotlinx.serialization.json.JsonObject
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Controlled identities and real MSC client queues; no public network or media fixtures. */
class NativeBootstrapAndroidTest {
    @get:Rule val activity = ActivityScenarioRule(ExtractionTestActivity::class.java)
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val domains = listOf(YoutubeSessionProvider.ORIGIN, "https://youtube.com", "https://m.youtube.com", "https://music.youtube.com")
    private val store = MemoryAuthStore()
    private var persisted = emptyMap<String, String?>()
    private val preferences get() = com.hhst.youtubelite.core.PreferenceStore.app(app)
    @Before fun knownAnonymousIdentity() {
        persisted = preferences.keys().filter { it.startsWith("youtube-session:config:") }.associateWith(preferences::readString)
        assertTrue(domains.all { YoutubeSessionProvider.authCookies(CookieManager.getInstance().getCookie(it).orEmpty()).isEmpty() })
        store.write(MediaSession.KEY_VISITOR, "native-bootstrap-fixture")
        MediaSession.initialize(store)
    }
    @After fun restoreIdentity() {
        domains.forEach { CookieManager.getInstance().setCookie(it, "SAPISID=; Max-Age=0; Path=/") }
        MediaSession.initialize(PreferencesAuthStore(app))
        preferences.keys().filter { it.startsWith("youtube-session:config:") }.forEach(preferences::remove)
        persisted.forEach { (key, value) -> if (value != null) preferences.putString(key, value) }
    }
    private fun deadline() = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    private fun sessions(calls: MutableList<String> = mutableListOf()) = YoutubeSessionProvider(app,
        OkHttpClient.Builder().addInterceptor { chain ->
            calls += chain.request().url.encodedPath
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body("""<script>ytcfg.set({"LOGGED_IN":false,"VISITOR_DATA":"web-fixture","PLAYER_JS_URL":"/s/player/fixture/base.js"});</script>""".toResponseBody()).build()
        }.build())
    private fun native(sessions: YoutubeSessionProvider) = requireNotNull(sessions.captureNative(deadline()) { true })
    @Test fun nativeCaptureHasNoPageAndDoesNotWaitForThePagePermit() {
        val calls = mutableListOf<String>(); val sessions = sessions(calls)
        val permit = sessions.javaClass.getDeclaredField("capturePermit").apply { isAccessible = true }.get(sessions) as Semaphore
        permit.acquire()
        try {
            val started = System.nanoTime(); val value = native(sessions)
            assertTrue(value.nativeBootstrap); assertTrue(sessions.isCurrent(value))
            assertEquals("native-bootstrap-fixture", value.visitorData)
            assertNull(value.playerUrl); assertTrue(calls.isEmpty())
            assertTrue((System.nanoTime() - started) / 1_000_000 < 1_000)
        } finally { permit.release() }
    }
    @Test fun unknownVisitorKeepsTheFullBootstrap() {
        MediaSession.initialize(MemoryAuthStore())
        assertNull(sessions().captureNative(deadline()) { true })
    }
    @Test fun browserAuthenticationDisablesNativeCaptureAndInvalidatesAnOlderCapture() {
        val sessions = sessions(); val snapshot = native(sessions)
        CookieManager.getInstance().setCookie("https://m.youtube.com", "SAPISID=fixture; Path=/; Secure")
        assertNull(sessions.captureNative(deadline()) { true })
        assertFalse(sessions.isCurrent(snapshot))
    }
    @Test fun nativeAuthenticationKeepsTheFullBootstrap() {
        store.write(MediaSession.KEY_CLIENT_ID, "fixture"); store.write(MediaSession.KEY_CLIENT_SECRET, "fixture")
        store.write(MediaSession.KEY_DEVICE_ID, "fixture"); store.write(MediaSession.KEY_REFRESH_TOKEN, "fixture")
        store.write(MediaSession.KEY_ACCESS_TOKEN, "fixture"); store.write(MediaSession.KEY_ACCESS_EXPIRES, "9999999999999")
        MediaSession.initialize(store)
        assertTrue(MediaSession.snapshot().signedIn)
        assertNull(sessions().captureNative(deadline()) { true })
    }
    @Test fun explicitRefreshAndRouteChangesInvalidateNativeResults() {
        val sessions = sessions(); val before = native(sessions)
        sessions.captureBounded("fixture0001", true, deadline(), { true })
        assertFalse(sessions.isCurrent(before))
        assertNull(sessions.captureNative(deadline()) { true })
        sessions.javaClass.getDeclaredField("configAt").apply { isAccessible = true }.setLong(sessions, 0L)
        val refreshed = native(sessions); assertNotEquals(before.key, refreshed.key)
        (sessions.javaClass.getDeclaredField("networkGeneration").apply { isAccessible = true }.get(sessions) as AtomicLong).incrementAndGet()
        assertFalse(sessions.isCurrent(refreshed))
    }
    @Test fun cancellationAndDeadlinesDoNotStartARequest() {
        val calls = mutableListOf<String>(); val sessions = sessions(calls)
        assertTrue(runCatching { sessions.captureNative(deadline()) { false } }.exceptionOrNull() is IOException)
        assertTrue(runCatching { sessions.captureNative(System.nanoTime() - 1) { true } }.exceptionOrNull() is IOException)
        assertTrue(calls.isEmpty())
    }
    @Test fun changingOnlyTheNativeVisitorInvalidatesTheCapturedIdentity() {
        val sessions = sessions(); val captured = native(sessions)
        store.write(MediaSession.KEY_VISITOR, "changed-native-visitor")
        MediaSession.initialize(store)
        assertFalse(sessions.isCurrent(captured))
        assertNotEquals(captured.key, native(sessions).key)
    }
    @Test fun webPreparationSharesTheOriginalSingleNetworkRetry() {
        val fixture = Fixture(); var calls = 0
        val transport = object : ExtractionTransport {
            override fun get(context: ExtractionContext, url: String, headers: Map<String, List<String>>): PageResponse = error("Unexpected GET")
            override fun post(context: ExtractionContext, url: String, headers: Map<String, List<String>>, body: ByteArray): PageResponse {
                if (++calls != 2) throw java.io.EOFException()
                return PageResponse(200, emptyMap(), "{}", url)
            }
        }
        val original = ExtractionContext(fixture.native, transport, false, false, false, deadlineNanos = deadline(),
            current = { true }, diagnostics = ExtractionDiagnostics(), webConfiguration = { it.withSession(fixture.web) { true } })
        val url = "https://www.youtube.com/youtubei/v1/player"
        original.withWebConfiguration().post(url, emptyMap(), byteArrayOf())
        assertEquals(2, calls)
        assertTrue(runCatching { original.post(url, emptyMap(), byteArrayOf()) }.exceptionOrNull() is java.io.EOFException)
        assertEquals(3, calls)
    }
    private class Fixture {
        val requests = mutableListOf<String>(); var webPreparations = 0; var scriptLoads = 0; var invalidations = 0
        var directRejected = false; var cancelled = false; var audioCatalog = false
        val native = YoutubeWebSession("native-key", WebAccount.ANONYMOUS, 0, null, null, null,
            "same-visitor", "fixture-UA", 1, null, "native-session", JsonObject(emptyMap())) { "" }
        val web = YoutubeWebSession("web-key", WebAccount.ANONYMOUS, 0, null, null, null,
            "same-visitor", "fixture-UA", 1, "https://www.youtube.com/s/player/fixture/base.js", "fixture", JsonObject(emptyMap())) { "" }
        val solver = object : SignatureSolver {
            override fun signatureTimestamp(playerUrl: String, context: ExtractionContext) = 1
            override fun solve(playerUrl: String, signatures: Set<String>, throttles: Set<String>, context: ExtractionContext) = SignatureSolver.Solutions(emptyMap(), emptyMap())
            override fun playerScript(playerUrl: String, context: ExtractionContext): String {
                assertSame(web, context.session); scriptLoads++; return "var signatureTimestamp=1;"
            }
        }
        val tokens = object : PoTokenMinter {
            override fun mint(request: PoTokenMinter.TokenRequest, context: ExtractionContext): PoTokenMinter.Token {
                assertSame(web, context.session); throw IOException("fixture-no-token")
            }
            override fun invalidate(scope: String) {}
            override fun invalidateAll() { invalidations++ }
        }
        val playback = MscPlayback(solver, tokens, identity = { RequestContext(visitor = "same-visitor") })
        val transport = object : ExtractionTransport {
            override fun get(context: ExtractionContext, url: String, headers: Map<String, List<String>>): PageResponse = error("Unexpected GET")
            override fun post(context: ExtractionContext, url: String, headers: Map<String, List<String>>, body: ByteArray): PageResponse {
                val name = org.json.JSONObject(body.toString(Charsets.UTF_8)).getJSONObject("context").getJSONObject("client").getString("clientName")
                requests += name
                val rejected = directRejected && name == "VISIONOS"
                return PageResponse(if (rejected) 500 else 200, emptyMap(), if (rejected) "{}" else """{"playabilityStatus":{"status":"OK"},
                    "videoDetails":{"videoId":"fixture0001","title":"Fixture","lengthSeconds":"20"},
                    "streamingData":{${if (audioCatalog) """"adaptiveFormats":[{"itag":140,"mimeType":"audio/mp4; codecs=\"mp4a.40.2\"","audioTrack":{"id":"en.1","displayName":"English"},"url":"https://media.invalid/en"},{"itag":141,"mimeType":"audio/mp4; codecs=\"mp4a.40.2\"","audioTrack":{"id":"fr.1","displayName":"French"},"url":"https://media.invalid/fr"}],""" else ""}"formats":[{"itag":18,"height":360,"width":640,"bitrate":300000,
                    "mimeType":"video/mp4; codecs=\"avc1.42001e, mp4a.40.2\"","url":"https://media.invalid/video"}]}}""", url)
            }
        }
        fun context(): ExtractionContext = ExtractionContext(native, transport, false, false, true, deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(10),
            current = { !cancelled }, diagnostics = ExtractionDiagnostics(), webConfiguration = { original ->
                webPreparations++; original.withSession(web) { !cancelled }
            })
        fun resolve(id: String = "fixture0001") = playback.resolve(id, context(), null, false, emptySet()).stream
    }
    @Test fun directClientDoesNotPrepareWebScriptOrTokens() {
        val fixture = Fixture(); val stream = fixture.resolve()
        assertEquals(listOf("VISIONOS"), fixture.requests)
        assertEquals(0, fixture.webPreparations); assertEquals(0, fixture.scriptLoads)
        assertSame(fixture.native, stream.formats.single().requestPlan!!.session)
        fixture.resolve(); assertEquals(1, fixture.requests.size)
    }
    @Test fun fallbackAndCachedWebResultsUseTheFullSessionAndRememberSuccess() {
        val fixture = Fixture().apply { directRejected = true }
        val first = fixture.resolve()
        assertEquals(listOf("VISIONOS", "WEB"), fixture.requests)
        assertSame(fixture.web, first.formats.single().requestPlan!!.session)
        assertEquals(1, fixture.webPreparations)
        val cached = fixture.resolve()
        assertEquals(2, fixture.requests.size)
        assertSame(fixture.web, cached.formats.single().requestPlan!!.session)
        assertEquals(2, fixture.webPreparations)
        fixture.requests.clear(); fixture.resolve("fixture0002")
        assertEquals(listOf("WEB"), fixture.requests)
        assertEquals(3, fixture.webPreparations)
    }
    @Test fun nestedBudgetsRetainCancellationAndPlaybackPriority() {
        val fixture = Fixture(); val root = fixture.context(); val nested = root.withTimeLimit(2_000)
        val prepared = nested.withWebConfiguration()
        assertSame(fixture.web, prepared.session); assertEquals(nested.deadlineNanos, prepared.deadlineNanos)
        root.prioritizePlayback(); assertTrue(prepared.playbackPriority)
        fixture.cancelled = true
        assertTrue(runCatching { prepared.check() }.exceptionOrNull() is IOException)
        assertTrue(fixture.requests.isEmpty())
    }
    @Test fun preparedWebConfigurationPreservesCatalogToStartupResultReuse() {
        val fixture = Fixture().apply { audioCatalog = true }
        val sessions = sessions()
        val full = sessions.captureBounded("fixture0001", true, deadline(), { true })
        assertNull(sessions.captureNative(deadline()) { true })
        val catalog = ExtractionContext(full, fixture.transport, false, true, true,
            deadlineNanos = deadline(), current = { sessions.isCurrent(full) }, diagnostics = ExtractionDiagnostics())
        fixture.playback.resolve("fixture0001", catalog, null, false, emptySet())
        assertEquals(listOf("VISIONOS"), fixture.requests)
        val diagnostics = ExtractionDiagnostics()
        val forbiddenNetwork = OkHttpClient.Builder().addInterceptor { error("Catalog result should satisfy startup without another request") }.build()
        val host = YoutubeExtractionHost(sessions, forbiddenNetwork, fixture.solver, fixture.tokens, diagnostics,
            YoutubeMediaRequests(sessions, diagnostics), BrowserPlayerResponses(sessions), AdaptiveClientOrder(), fixture.playback)
        val startup = host.context("fixture0001", false, false, null, { true }, deadline(), true, DiagnosticContext(videoId = "fixture0001"))
        assertEquals(full.key, startup.session.key)
        val reused = host.resolve("fixture0001", startup, null, false)
        assertTrue(reused.stream.formats.isNotEmpty())
        assertEquals(listOf("VISIONOS"), fixture.requests)
        assertEquals(0, fixture.webPreparations)
    }
    @Test fun hostCacheOwnershipAndFullRecoveryRemainConsistent() {
        val fixture = Fixture(); val calls = mutableListOf<String>(); val sessions = sessions(calls)
        val diagnostics = ExtractionDiagnostics()
        val host = YoutubeExtractionHost(sessions, OkHttpClient(), fixture.solver, fixture.tokens, diagnostics,
            YoutubeMediaRequests(sessions, diagnostics), BrowserPlayerResponses(sessions), AdaptiveClientOrder(), fixture.playback)
        fun context(fresh: Boolean = false) = host.context("fixture0001", fresh, false, null, { true }, deadline(), true, DiagnosticContext(videoId = "fixture0001"))
        val original = context(); assertTrue(original.session.nativeBootstrap); assertTrue(calls.isEmpty())
        val stream = fixture.resolve(); val cache = host.cache(MemCache()) { original }
        cache.putStream("fixture0001", stream)
        val reused = context(); assertEquals(original.session.key, reused.session.key)
        assertSame(stream, host.cache(MemCache()) { reused }.getStream("fixture0001"))
        val full = context(true)
        assertFalse(full.session.nativeBootstrap); assertEquals(listOf("/watch"), calls)
        assertEquals(1, fixture.invalidations); assertFalse(sessions.isCurrent(original.session))
        assertNull(host.cache(MemCache()) { full }.getStream("fixture0001"))
    }
}
