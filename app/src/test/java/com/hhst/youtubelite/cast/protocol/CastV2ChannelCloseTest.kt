package com.hhst.youtubelite.cast.protocol

import java.io.IOException
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CastV2ChannelCloseTest {
    @Test fun openingAfterCloseCannotRestartTheTransport() = withBlockedOutput { fixture ->
        fixture.channel.close()
        fixture.channel.open()
        fixture.assertClosedOnce()
    }

    @Test fun healthyCloseSendsCourtesyFrameBeforeClosingTheSocket() = withBlockedOutput { fixture ->
        val output = ByteArrayOutputStream()
        setField(fixture.channel, "output", output)
        @Suppress("UNCHECKED_CAST")
        val destinations = field(fixture.channel, "connectedDestinations") as MutableSet<String>
        destinations += "receiver-0"

        fixture.channel.close()
        fixture.assertClosedOnce()
        val message = CastMessageCodec.readFramed(output.toByteArray().inputStream())!!
        assertEquals(CastV2Channel.NS_CONNECTION, message.namespace)
        assertEquals("receiver-0", message.destinationId)
        assertEquals("{\"type\":\"CLOSE\"}", message.payloadUtf8)
    }

    @Test fun closeAbortsAWriteAlreadyBlockingTheWriter() = withBlockedOutput { fixture ->
        fixture.channel.send("sender-0", "receiver-0", "urn:x-cast:com.litube.test", "{}")
        assertTrue("writer never entered output", fixture.output.entered.await(3, TimeUnit.SECONDS))
        fixture.channel.close()
        fixture.assertClosedOnce()
    }

    @Test fun closeAbortsItsOwnBlockedCourtesyFrame() = withBlockedOutput { fixture ->
        @Suppress("UNCHECKED_CAST")
        val destinations = field(fixture.channel, "connectedDestinations") as MutableSet<String>
        destinations += "receiver-0"
        fixture.channel.close()
        assertTrue("courtesy CLOSE was not attempted", fixture.output.entered.await(3, TimeUnit.SECONDS))
        fixture.assertClosedOnce()
    }

    private fun withBlockedOutput(check: (Fixture) -> Unit) {
        val socket = SSLSocketFactory.getDefault().createSocket() as SSLSocket
        val output = BlockedOutput(socket)
        val closed = AtomicInteger()
        val errors = AtomicInteger()
        val terminal = CountDownLatch(1)
        val channel = CastV2Channel("unused", 8009, object : CastV2Channel.Listener {
            override fun onOpened() = Unit
            override fun onMessage(message: CastMessage) = Unit
            override fun onError(reason: String) { errors.incrementAndGet(); terminal.countDown() }
            override fun onClosed() { closed.incrementAndGet(); terminal.countDown() }
        })
        // This isolates transport shutdown after authentication. The Android
        // regression reaches the same gate through real TLS and DeviceAuth.
        setField(channel, "socket", socket)
        setField(channel, "output", output)
        val fixture = Fixture(channel, socket, output, closed, errors, terminal)
        try {
            check(fixture)
        } finally {
            output.release.countDown()
            socket.close()
            channel.close()
            (field(channel, "writer") as ScheduledExecutorService).shutdownNow()
        }
    }

    private class Fixture(
        val channel: CastV2Channel,
        val socket: SSLSocket,
        val output: BlockedOutput,
        val closed: AtomicInteger,
        val errors: AtomicInteger,
        val terminal: CountDownLatch,
    ) {
        fun assertClosedOnce() {
            assertTrue("blocked writer prevented close", terminal.await(3, TimeUnit.SECONDS))
            assertTrue("underlying socket is still open", socket.isClosed)
            output.release.countDown()
            assertTrue("writer did not stop", (field(channel, "writer") as ScheduledExecutorService)
                .awaitTermination(3, TimeUnit.SECONDS))
            channel.close()
            assertEquals(1, closed.get())
            assertEquals(0, errors.get())
        }
    }

    private class BlockedOutput(private val socket: SSLSocket) : OutputStream() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        override fun write(value: Int) {
            entered.countDown()
            while (!socket.isClosed && !release.await(10, TimeUnit.MILLISECONDS)) { /* wait for transport abort */ }
            throw IOException("blocked output closed")
        }
    }

    companion object {
        private fun field(channel: CastV2Channel, name: String): Any? =
            CastV2Channel::class.java.getDeclaredField(name).apply { isAccessible = true }.get(channel)

        private fun setField(channel: CastV2Channel, name: String, value: Any) {
            CastV2Channel::class.java.getDeclaredField(name).apply { isAccessible = true }.set(channel, value)
        }
    }
}
