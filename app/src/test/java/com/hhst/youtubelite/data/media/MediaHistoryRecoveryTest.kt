package com.hhst.youtubelite.data.media

import com.hhst.youtubelite.watch.domain.UnconfirmedActionException
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestContext
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class MediaHistoryRecoveryTest {
    private fun state(paused: Boolean?) = if (paused == null) "{}" else
        """{"buttonRenderer":{"serviceEndpoint":{"commandMetadata":{"webCommandMetadata":{
            "apiUrl":"/youtubei/v1/history/${if (paused) "resume" else "pause"}_watch_history"}}}}}"""

    @Test fun aPausedStateCanSettleWithoutRepeatingTheManagementWrite() = runBlocking {
        val writes = AtomicInteger()
        val reads = AtomicInteger()
        val client = MediaClient(OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = if (request.url.encodedPath.endsWith("/browse")) {
                state(reads.incrementAndGet() >= 2)
            } else { writes.incrementAndGet(); """{"responseContext":{}}""" }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("test").body(body.toResponseBody()).build()
        }.build()) { RequestContext("primary", "access", generation = 7) }
        MediaLibraryRepository(client).setHistoryPaused(true)
        assertEquals(1, writes.get())
        assertEquals(2, reads.get())
    }

    @Test fun knownOldPauseStateAllowsOneBoundedIdempotentRetry() = runBlocking {
        val writes = AtomicInteger()
        val reads = AtomicInteger()
        val client = MediaClient(OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = if (request.url.encodedPath.endsWith("/browse")) {
                reads.incrementAndGet(); state(false)
            } else if (writes.incrementAndGet() == 1) """{"responseContext":{}}""" else """{"success":true}"""
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("test").body(body.toResponseBody()).build()
        }.build()) { RequestContext("primary", "access", generation = 7) }
        MediaLibraryRepository(client).setHistoryPaused(true)
        assertEquals(2, writes.get())
        assertEquals(2, reads.get())
    }

    @Test fun missingManagementStateIsNotInferredFromAnEmptyHistory() = runBlocking {
        val writes = AtomicInteger()
        val reads = AtomicInteger()
        val client = MediaClient(OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = if (request.url.encodedPath.endsWith("/browse")) {
                reads.incrementAndGet(); """{"contents":[]}"""
            } else { writes.incrementAndGet(); """{"responseContext":{}}""" }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("test").body(body.toResponseBody()).build()
        }.build()) { RequestContext("primary", "access", generation = 7) }
        assertTrue(runCatching { MediaLibraryRepository(client).setHistoryPaused(true) }.exceptionOrNull() is UnconfirmedActionException)
        assertEquals(1, writes.get())
        assertEquals(2, reads.get())
    }
}
