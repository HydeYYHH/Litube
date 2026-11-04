package com.hhst.youtubelite.dearrow

import com.hhst.youtubelite.extension.ExtensionManager
import com.hhst.youtubelite.extension.MemoryPrefStore
import com.hhst.youtubelite.extension.PreferenceKeys
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class DeArrowServiceTest {
    @Test fun resettingPreferencesUpdatesTheActiveService() {
        val prefs = enabledPrefs()
        val service = DeArrowService(OkHttpClient(), prefs)
        prefs.resetToDefault()
        assertTrue(service.inactive())
        assertFalse(service.titlesEnabled.value)
        assertFalse(service.thumbnailsEnabled.value)
    }

    @Test fun importingDisabledTogglesUpdatesTheActiveService() {
        val prefs = enabledPrefs()
        val service = DeArrowService(OkHttpClient(), prefs)
        prefs.importPreferences(mapOf(
            PreferenceKeys.DEARROW_TITLES to false,
            PreferenceKeys.DEARROW_THUMBNAILS to false,
        ), null)
        assertTrue(service.inactive())
    }

    private fun bucket(vararg entries: Pair<String, String>): String =
        entries.joinToString(",", prefix = "{", postfix = "}") { (id, json) -> "\"$id\":$json" }

    private fun entry(
        titles: String? = null,
        thumbs: String? = null,
        duration: Double? = null,
    ): String {
        val parts = mutableListOf<String>()
        if (titles != null) parts.add("\"titles\":[$titles]")
        if (thumbs != null) parts.add("\"thumbnails\":[$thumbs]")
        if (duration != null) parts.add("\"videoDuration\":$duration")
        return "{" + parts.joinToString(",") + "}"
    }

    private fun title(value: String, votes: Int = 1, original: Boolean = false, locked: Boolean = false): String =
        "{\"title\":\"$value\",\"original\":$original,\"votes\":$votes,\"locked\":$locked,\"UUID\":\"u\"}"

    private fun thumb(timestamp: Double?, votes: Int = 1, original: Boolean = false, locked: Boolean = false): String {
        val time = if (timestamp == null) "null" else timestamp.toString()
        return "{\"timestamp\":$time,\"original\":$original,\"votes\":$votes,\"locked\":$locked,\"UUID\":\"u\"}"
    }

    private fun enabledPrefs(): ExtensionManager = ExtensionManager(MemoryPrefStore()).apply {
        setEnabled(PreferenceKeys.DEARROW_TITLES, true)
        setEnabled(PreferenceKeys.DEARROW_THUMBNAILS, true)
    }

    private fun service(server: MockWebServer, prefs: ExtensionManager = enabledPrefs()) =
        DeArrowService(OkHttpClient(), prefs, brandingUrl = server.url("/api/branding").toString())

    @Test fun brandingIsStrictlyMatchedToTheRequestedVideoId() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            val body = bucket("AAAAAAAAAAA" to entry(titles = title("Other video title")),
                "BBBBBBBBBBB" to entry(titles = title("Target title")))
            server.enqueue(MockResponse().setBody(body))
            val result = service(server).branding("BBBBBBBBBBB")
            assertEquals("Target title", result?.title)
            val request = server.takeRequest(1, TimeUnit.SECONDS)!!
            // Only the hash prefix leaves the app, never the plain video ID.
            assertFalse(request.path!!.contains("BBBBBBBBBBB"))
        } finally { server.shutdown() }
    }

    @Test fun aBucketWithoutTheTargetVideoFallsBackToOriginal() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody(bucket("AAAAAAAAAAA" to entry(titles = title("Other video title")))))
            val result = service(server).branding("BBBBBBBBBBB")
            assertNull(result?.title); assertNull(result?.thumbTime)
        } finally { server.shutdown() }
    }

    @Test fun theFirstTitleCandidateDecidesAndLowerRankedOnesAreNeverPromoted() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody(bucket("V" to entry(
                titles = "${title("Original", votes = 5, original = true)},${title("Replacement", votes = 100)}"))))
            assertEquals(null, service(server).branding("V")?.title)

            server.enqueue(MockResponse().setBody(bucket("V" to entry(
                titles = "${title("Bad", votes = -2)},${title("Great", votes = 9)}"))))
            assertEquals(null, service(server).branding("V")?.title)

            server.enqueue(MockResponse().setBody(bucket("V" to entry(
                titles = "${title("Locked choice", votes = -3, locked = true)},${title("Runner up", votes = 9)}"))))
            assertEquals("Locked choice", service(server).branding("V")?.title)

            server.enqueue(MockResponse().setBody(bucket("V" to entry(titles = title("Community title", votes = 2)))))
            assertEquals("Community title", service(server).branding("V")?.title)
        } finally { server.shutdown() }
    }

    @Test fun formatterDirectivesAreStripped() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody(bucket("V" to entry(titles = title(">the >real >title")))))
            assertEquals("the real title", service(server).branding("V")?.title)
        } finally { server.shutdown() }
    }

    @Test fun thumbnailTimesMustBeValidAndInsideKnownDurations() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody(bucket("V" to entry(thumbs = thumb(-1.0)))))
            assertNull(service(server).branding("V")?.thumbTime)

            server.enqueue(MockResponse().setBody(bucket("V" to entry(thumbs = thumb(120.0), duration = 100.0))))
            assertNull(service(server).branding("V")?.thumbTime)

            server.enqueue(MockResponse().setBody(bucket("V" to entry(thumbs = thumb(42.0), duration = 100.0))))
            assertEquals(42.0, service(server).branding("V")?.thumbTime!!, 0.0)

            server.enqueue(MockResponse().setBody(bucket("V" to entry(thumbs = thumb(3.5), duration = null))))
            assertEquals(3.5, service(server).branding("V")?.thumbTime!!, 0.0)

            server.enqueue(MockResponse().setBody(bucket("V" to entry(thumbs = thumb(null)))))
            assertNull(service(server).branding("V")?.thumbTime)
        } finally { server.shutdown() }
    }

    @Test fun http204And404MeanNoSubmissionForTheBucket() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(204))
            assertEquals(null, service(server).branding("V")?.title)
            server.enqueue(MockResponse().setResponseCode(404))
            assertEquals(null, service(server).branding("V")?.thumbTime)
            assertEquals(2, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun rateLimitedFetchBacksOffWithoutHammering() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(429))
            val svc = service(server)
            assertNull(svc.branding("V")?.title)
            assertNull(svc.branding("V")?.thumbTime)
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun disabledTogglesIssueNoRequests() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            val svc = DeArrowService(OkHttpClient(), ExtensionManager(MemoryPrefStore()),
                brandingUrl = server.url("/api/branding").toString())
            assertTrue(svc.inactive())
            assertNull(svc.branding("V"))
            assertEquals(0, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun enablingOnlyTitlesStillFetchesTheSharedBucket() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            val prefs = ExtensionManager(MemoryPrefStore())
            prefs.setEnabled(PreferenceKeys.DEARROW_TITLES, true)
            assertFalse(service(server, prefs).inactive())
            server.enqueue(MockResponse().setBody(bucket("V" to entry(titles = title("T")))))
            assertEquals("T", service(server, prefs).branding("V")?.title)
        } finally { server.shutdown() }
    }

    @Test fun preferenceListenersRefreshTheToggleFlows() {
        val prefs = ExtensionManager(MemoryPrefStore())
        val svc = DeArrowService(OkHttpClient(), prefs)
        assertFalse(svc.titlesEnabled.value)
        prefs.setEnabled(PreferenceKeys.DEARROW_TITLES, true)
        assertTrue(svc.titlesEnabled.value)
        prefs.setEnabled(PreferenceKeys.DEARROW_THUMBNAILS, true)
        assertTrue(svc.thumbnailsEnabled.value)
    }

    @Test fun concurrentBrandingsMergeOntoOneBucketFetch() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody(bucket("V" to entry(titles = title("Shared"))))
                .setBodyDelay(300, TimeUnit.MILLISECONDS))
            val svc = service(server)
            val first = async { svc.branding("V") }
            val second = async { svc.branding("V") }
            assertEquals("Shared", first.await()?.title)
            assertEquals("Shared", second.await()?.title)
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun theMetadataCacheIsBounded() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            val svc = service(server)
            repeat(DeArrowService.MAX_ITEMS) { i ->
                val id = "V%010d".format(i)
                server.enqueue(MockResponse().setBody(bucket(id to entry(titles = title("t$i")))))
                assertEquals("t$i", svc.branding(id)?.title)
            }
            assertEquals(DeArrowService.MAX_ITEMS, server.requestCount)
            server.enqueue(MockResponse().setBody(bucket("EXTRA_ID" to entry(titles = title("extra")))))
            assertEquals("extra", svc.branding("EXTRA_ID")?.title)
            assertEquals(DeArrowService.MAX_ITEMS + 1, server.requestCount)
            server.enqueue(MockResponse().setBody(bucket("V0000000000" to entry(titles = title("t0")))))
            assertEquals("t0", svc.branding("V0000000000")?.title)
            assertEquals(DeArrowService.MAX_ITEMS + 2, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun theThumbnailServiceUrlCarriesTheVideoIdAndTime() {
        assertEquals("https://dearrow-thumb.ajay.app/api/v1/getThumbnail?videoID=V&time=1.5",
            deArrowThumbnailUrl("V", 1.5))
        assertNull(deArrowThumbnailUrl("V", null))
    }

    @Test fun cancellingOneWaiterKeepsTheSharedRequestForOtherCards() = runBlocking<Unit> {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody(bucket("V" to entry(titles = title("Shared"))))
                .setBodyDelay(400, TimeUnit.MILLISECONDS))
            val service = service(server)
            val first = async(Dispatchers.Default) { service.branding("V") }
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            val second = async(Dispatchers.Default) { service.branding("V") }
            first.cancel()
            val third = async(Dispatchers.Default) { service.branding("V") }
            kotlinx.coroutines.withTimeout(3_000) {
                assertEquals("Shared", second.await()?.title)
                assertEquals("Shared", third.await()?.title)
            }
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun simultaneousCardsStartOnlyOneRequest() = runBlocking<Unit> {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody(bucket("V" to entry(titles = title("Shared"))))
                .setBodyDelay(200, TimeUnit.MILLISECONDS))
            val service = service(server)
            val requests = (1..32).map { async(Dispatchers.Default) { service.branding("V") } }
            kotlinx.coroutines.withTimeout(3_000) { requests.forEach { assertEquals("Shared", it.await()?.title) } }
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun disablingDecorationCancelsAStalledHttpCall() = runBlocking<Unit> {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
            val prefs = enabledPrefs()
            val service = service(server, prefs)
            val pending = async(Dispatchers.Default) { service.branding("V") }
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            prefs.importPreferences(mapOf(PreferenceKeys.DEARROW_TITLES to false,
                PreferenceKeys.DEARROW_THUMBNAILS to false), null)
            val failure = kotlinx.coroutines.withTimeout(1_000) { runCatching { pending.await() }.exceptionOrNull() }
            assertTrue(failure is kotlinx.coroutines.CancellationException)
            prefs.setEnabled(PreferenceKeys.DEARROW_TITLES, true)
            server.enqueue(MockResponse().setBody(bucket("V" to entry(titles = title("Retry")))))
            assertEquals("Retry", kotlinx.coroutines.withTimeout(3_000) { service.branding("V") }?.title)
            assertEquals(2, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun oversizedBrandingResponseFallsBackWithoutParsingIt() = runBlocking<Unit> {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody(" ".repeat(DeArrowService.MAX_RESPONSE_BYTES.toInt() + 1)))
            assertNull(service(server).branding("V"))
        } finally { server.shutdown() }
    }

    @Test fun clearingDecorationCancelsRequestsAndAllowsFreshReads() = runBlocking<Unit> {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
            val service = service(server)
            val pending = async(Dispatchers.Default) { service.branding("V") }
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            service.clear()
            val failure = kotlinx.coroutines.withTimeout(1_000) { runCatching { pending.await() }.exceptionOrNull() }
            assertTrue(failure is kotlinx.coroutines.CancellationException)
            server.enqueue(MockResponse().setBody(bucket("V" to entry(titles = title("Fresh")))))
            assertEquals("Fresh", kotlinx.coroutines.withTimeout(3_000) { service.branding("V") }?.title)
            service.clear()
            server.enqueue(MockResponse().setBody(bucket("V" to entry(titles = title("Newest")))))
            assertEquals("Newest", kotlinx.coroutines.withTimeout(3_000) { service.branding("V") }?.title)
            assertEquals(3, server.requestCount)
        } finally { server.shutdown() }
    }
}
