package com.hhst.youtubelite.cast

import fi.iki.elonen.NanoHTTPD
import org.junit.Assert.*
import org.junit.Test
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BoundedProxyRunnerTest {
    @Test fun saturatedProxyClosesExcessConnectionsAndRecoversAfterTransfersFinish() {
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            init { setAsyncRunner(BoundedProxyRunner(2)) }
            override fun serve(session: IHTTPSession): Response {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                return newFixedLengthResponse("ok")
            }
        }
        val sockets = mutableListOf<Socket>()
        try {
            server.start()
            repeat(2) { sockets += request(server.listeningPort) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            request(server.listeningPort).use { excess ->
                try { assertEquals(-1, excess.getInputStream().read()) }
                catch (_: java.net.SocketException) { /* closed with unread request bytes */ }
            }
            release.countDown()
            sockets.forEach { assertTrue(it.getInputStream().readBytes().toString(Charsets.UTF_8).endsWith("ok")) }
            eventuallyAccepts(server.listeningPort)
        } finally { release.countDown(); sockets.forEach { it.close() }; server.stop() }
    }

    @Test fun stopClosesActiveClientsAndTheRunnerSupportsRestart() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            init { setAsyncRunner(BoundedProxyRunner(1)) }
            override fun serve(session: IHTTPSession): Response {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                return newFixedLengthResponse("ok")
            }
        }
        try {
            server.start()
            request(server.listeningPort).use { active ->
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                server.stop()
                assertEquals(-1, active.getInputStream().read())
            }
            release.countDown()
            server.start()
            eventuallyAccepts(server.listeningPort)
        } finally { release.countDown(); server.stop() }
    }

    private fun request(port: Int): Socket = Socket("127.0.0.1", port).apply {
        soTimeout = 2_000
        getOutputStream().write("GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".toByteArray())
        getOutputStream().flush()
    }

    private fun eventuallyAccepts(port: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (true) {
            val response = try { request(port).use { it.getInputStream().readBytes().toString(Charsets.UTF_8) } }
                catch (_: java.net.SocketException) { "" }
            if (response.endsWith("ok")) return
            check(System.nanoTime() < deadline) { "Proxy did not release its connection slots" }
            Thread.sleep(10)
        }
    }
}
