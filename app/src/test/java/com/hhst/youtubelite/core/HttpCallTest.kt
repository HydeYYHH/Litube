package com.hhst.youtubelite.core

import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpCallTest {
    @Test fun cancellationAbortsWaitingHeadersAndBodyReads() = runBlocking {
        for (delayBody in listOf(false, true)) {
            MockWebServer().use { server ->
                val response = MockResponse().setBody("artwork")
                if (delayBody) response.setBodyDelay(2, TimeUnit.SECONDS)
                else response.setHeadersDelay(2, TimeUnit.SECONDS)
                server.enqueue(response)
                val client = OkHttpClient()
                val call = client.newCall(Request.Builder().url(server.url("/image")).build())
                val reading = CountDownLatch(1)
                val task = async(Dispatchers.Default) { call.readCancellable { reading.countDown(); it.body!!.string() } }
                assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                if (delayBody) assertTrue(reading.await(5, TimeUnit.SECONDS))
                withTimeout(500) { task.cancelAndJoin() }
                assertTrue(call.isCanceled())
                withTimeout(2_000) { while (client.dispatcher.runningCallsCount() > 0) delay(10) }
                client.dispatcher.executorService.shutdownNow()
                client.connectionPool.evictAll()
            }
        }
    }

    @Test fun aCompletedResponseIsReadBeforeItCloses() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("artwork"))
            val client = OkHttpClient()
            try {
                val call = client.newCall(Request.Builder().url(server.url("/image")).build())
                assertEquals("artwork", call.readCancellable { it.body!!.string() })
            } finally {
                client.dispatcher.executorService.shutdownNow()
                client.connectionPool.evictAll()
            }
        }
    }
}
