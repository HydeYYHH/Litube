package com.hhst.youtubelite.data.media

import com.hhst.youtubelite.watch.domain.UnconfirmedActionException
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestContext
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class MediaSaveRecoveryPublicApiTest {
    private val context = RequestContext("primary", "access", generation = 7)
    private fun response(request: Request, body: String) = Response.Builder().request(request)
        .protocol(Protocol.HTTP_1_1).code(200).message("test").body(body.toResponseBody()).build()
    private fun choices(state: String?) = """{"contents":[{"addToPlaylistRenderer":{"playlists":[
        {"playlistAddToOptionRenderer":{"playlistId":"PLone"${state?.let { ",\"containsSelectedVideos\":\"$it\"" }.orEmpty()}}}
    ]}}]}"""

    @Test fun anAmbiguousAddCanBeConfirmedByFreshMembershipWithoutAppendingAgain() = runBlocking {
        val writes = AtomicInteger()
        val reads = AtomicInteger()
        val client = MediaClient(OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            if (request.url.encodedPath.endsWith("/playlist/get_add_to_playlist")) {
                reads.incrementAndGet()
                response(request, choices(if (writes.get() == 0) "NONE" else "ALL"))
            } else {
                writes.incrementAndGet()
                response(request, """{"responseContext":{},"frameworkUpdates":{}}""")
            }
        }.build()) { context }
        MediaSaveRepository(client).setContaining("PLone", "abcdefghijk", true)
        assertEquals(1, writes.get())
        assertTrue(reads.get() >= 1)
    }

    @Test fun aFreshOldMembershipAfterAnAmbiguousAddDoesNotAuthorizeAnotherAppend() = runBlocking {
        val writes = AtomicInteger()
        val reads = AtomicInteger()
        val client = MediaClient(OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            if (request.url.encodedPath.endsWith("/playlist/get_add_to_playlist")) {
                reads.incrementAndGet(); response(request, choices("NONE"))
            } else {
                writes.incrementAndGet(); response(request, """{"responseContext":{}}""")
            }
        }.build()) { context }
        assertTrue(runCatching { MediaSaveRepository(client).setContaining("PLone", "abcdefghijk", true) }
            .exceptionOrNull() is UnconfirmedActionException)
        assertEquals(1, writes.get())
        assertTrue(reads.get() >= 1)
    }

    @Test fun anUnknownMembershipFlagCannotConfirmRemoval() = runBlocking {
        val writes = AtomicInteger()
        val reads = AtomicInteger()
        val client = MediaClient(OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            if (request.url.encodedPath.endsWith("/playlist/get_add_to_playlist")) {
                reads.incrementAndGet(); response(request, choices(null))
            } else {
                writes.incrementAndGet(); response(request, """{"responseContext":{}}""")
            }
        }.build()) { context }
        assertTrue(runCatching { MediaSaveRepository(client).setContaining("PLone", "abcdefghijk", false) }
            .exceptionOrNull() is UnconfirmedActionException)
        assertEquals(1, writes.get())
        assertTrue(reads.get() >= 1)
    }

    @Test fun aPlaylistCreationWithALostReplyIsNeverCreatedAgain() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            server.enqueue(MockResponse().setBody("""{"playlistId":"duplicate"}"""))
            val client = MediaClient(OkHttpClient.Builder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
            }.build()) { context }
            assertTrue(runCatching { MediaSaveRepository(client).create("Title", "", "abcdefghijk") }.isFailure)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun anExistingWatchLaterMembershipSkipsTheAppend() = runBlocking {
        val writes = AtomicInteger()
        val reads = AtomicInteger()
        val client = MediaClient(OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            if (request.url.encodedPath.endsWith("/playlist/get_add_to_playlist")) {
                reads.incrementAndGet()
                response(request, choices("ALL").replace("PLone", "WL"))
            } else {
                writes.incrementAndGet()
                response(request, """{"status":"SUCCESS"}""")
            }
        }.build()) { context }
        MediaSaveRepository(client).saveWatchLater("abcdefghijk")
        assertEquals(1, reads.get())
        assertEquals(0, writes.get())
    }

    @Test fun aDisconnectedPreflightDoesNotSpendTheFirstAppendAttempt() = runBlocking {
        val writes = AtomicInteger()
        val reads = AtomicInteger()
        val client = MediaClient(OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            if (request.url.encodedPath.endsWith("/playlist/get_add_to_playlist")) {
                if (reads.incrementAndGet() == 1) throw java.net.SocketException("Connection reset")
                response(request, choices("NONE"))
            } else {
                writes.incrementAndGet()
                response(request, """{"status":"SUCCESS"}""")
            }
        }.build()) { context }
        MediaSaveRepository(client).setContaining("PLone", "abcdefghijk", true)
        assertEquals(1, reads.get())
        assertEquals(1, writes.get())
    }

    @Test fun aMissingPlaylistOptionDoesNotConfirmRemoval() = runBlocking {
        val writes = AtomicInteger()
        val client = MediaClient(OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            if (request.url.encodedPath.endsWith("/playlist/get_add_to_playlist")) response(request, choices("ALL").replace("PLone", "PLother"))
            else {
                writes.incrementAndGet()
                response(request, """{"responseContext":{}}""")
            }
        }.build()) { context }
        assertTrue(runCatching { MediaSaveRepository(client).setContaining("PLone", "abcdefghijk", false) }
            .exceptionOrNull() is UnconfirmedActionException)
        assertEquals(1, writes.get())
    }

    @Test fun aLibrarySaveIsConfirmedByAFreshSecondPageWithoutAnotherWrite() = runBlocking {
        val writes = AtomicInteger()
        val reads = AtomicInteger()
        val client = MediaClient(OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            if (request.url.encodedPath.endsWith("/browse")) {
                reads.incrementAndGet()
                val next = request.body!!.let { body -> okio.Buffer().also(body::writeTo).readUtf8() }.contains("page-2")
                response(request, if (next) """{"continuationContents":{"gridContinuation":{"items":[
                    {"gridPlaylistRenderer":{"playlistId":"VLPLone"}}]}}}"""
                else """{"contents":{"gridRenderer":{"items":[{"gridPlaylistRenderer":{"playlistId":"PLother"}}],
                    "continuations":[{"nextContinuationData":{"continuation":"page-2"}}]}}}""")
            } else {
                writes.incrementAndGet()
                response(request, """{"responseContext":{}}""")
            }
        }.build()) { context }
        MediaSaveRepository(client).savePlaylistToLibrary("PLone")
        assertEquals(1, writes.get())
        assertEquals(2, reads.get())
    }

    @Test fun anUnrecognizedEmptyLibraryDoesNotAuthorizeRepeatingTheSave() = runBlocking {
        val writes = AtomicInteger()
        val reads = AtomicInteger()
        val client = MediaClient(OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            if (request.url.encodedPath.endsWith("/browse")) {
                reads.incrementAndGet()
                response(request, """{"responseContext":{}}""")
            } else {
                writes.incrementAndGet()
                response(request, """{"responseContext":{}}""")
            }
        }.build()) { context }
        assertTrue(runCatching { MediaSaveRepository(client).savePlaylistToLibrary("PLone") }
            .exceptionOrNull() is UnconfirmedActionException)
        assertEquals(1, writes.get())
        assertEquals(2, reads.get())
    }
}
