package com.hhst.youtubelite.cast

import android.net.ConnectivityManager
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.cast.protocol.CastDeviceAuth
import com.hhst.youtubelite.cast.protocol.CastMessage
import com.hhst.youtubelite.cast.protocol.CastMessageCodec
import com.hhst.youtubelite.cast.protocol.CastV2Channel
import com.hhst.youtubelite.cast.protocol.Proto
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Real Android TLS sockets, with an independent receiver and signed modern/legacy responses. */
class CastChannelAndroidTest {
    @Test fun modernReceiverAuthenticatesBeforeApplicationMessages() = roundTrip(legacy = false)
    @Test fun legacyReceiverAuthenticatesWithoutOptionalNonceAndAlgorithms() = roundTrip(legacy = true)
    @Test fun tlsSocketCanBindToTheReceiverLanNetwork() = roundTrip(legacy = false, bindNetwork = true)

    @Test fun authenticatedBlockedWriteIsAbortedByLocalClose() = roundTrip(legacy = false) { channel ->
        val socket = CastV2Channel::class.java.getDeclaredField("socket")
            .apply { isAccessible = true }.get(channel) as SSLSocket
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocked = object : OutputStream() {
            override fun write(value: Int) {
                entered.countDown()
                while (!socket.isClosed && !release.await(10, TimeUnit.MILLISECONDS)) { }
                throw IOException("blocked output closed")
            }
        }
        CastV2Channel::class.java.getDeclaredField("output").apply { isAccessible = true }.set(channel, blocked)
        try {
            channel.send("sender-0", "receiver-0", "urn:x-cast:com.litube.test", "{}")
            assertTrue("authenticated write did not reach the gate", entered.await(3, TimeUnit.SECONDS))
            InstrumentationRegistry.getInstrumentation().runOnMainSync { channel.close() }
            val writer = CastV2Channel::class.java.getDeclaredField("writer")
                .apply { isAccessible = true }.get(channel) as ScheduledExecutorService
            assertTrue("blocked transport did not close", writer.awaitTermination(3, TimeUnit.SECONDS))
            assertTrue("authenticated socket is still open", socket.isClosed)
        } finally {
            release.countDown()
        }
    }

    @Test fun closeDuringAuthenticationCannotRestartTheTransport() {
        val server = CastVmFixture.context().serverSocketFactory.createServerSocket(
            0, 1, InetAddress.getByName("127.0.0.1"),
        ) as SSLServerSocket
        server.soTimeout = 7_000
        val releaseReceiver = CountDownLatch(1)
        val releaseAuthentication = CountDownLatch(1)
        val authenticated = CountDownLatch(1)
        val terminal = CountDownLatch(1)
        val opened = AtomicInteger()
        val closed = AtomicInteger()
        val errors = AtomicInteger()
        val transport = AtomicReference<SSLSocket>()
        val executor = Executors.newSingleThreadExecutor()
        val receiver = executor.submit(Callable {
            (server.accept() as SSLSocket).use { socket ->
                socket.soTimeout = 7_000
                authenticateReceiver(socket.inputStream, socket.outputStream, legacy = false)
                check(releaseReceiver.await(10, TimeUnit.SECONDS))
            }
        })
        val channel = CastV2Channel("127.0.0.1", server.localPort, object : CastV2Channel.Listener {
            override fun onOpened() { opened.incrementAndGet() }
            override fun onMessage(message: CastMessage) = Unit
            override fun onError(reason: String) { errors.incrementAndGet(); terminal.countDown() }
            override fun onClosed() { closed.incrementAndGet(); terminal.countDown() }
        }, CastV2Channel.Authenticator { socket, input, output ->
            CastVmFixture.authenticator().authenticate(socket, input, output)
            transport.set(socket)
            authenticated.countDown()
            check(releaseAuthentication.await(10, TimeUnit.SECONDS))
        })
        try {
            channel.open()
            assertTrue("DeviceAuth never completed", authenticated.await(10, TimeUnit.SECONDS))
            channel.close()
            assertTrue("authentication blocked local close", terminal.await(3, TimeUnit.SECONDS))
            assertTrue(transport.get().isClosed)
            releaseAuthentication.countDown()
            val writer = CastV2Channel::class.java.getDeclaredField("writer")
                .apply { isAccessible = true }.get(channel) as ScheduledExecutorService
            assertTrue(writer.awaitTermination(3, TimeUnit.SECONDS))
            assertEquals(0, opened.get())
            assertEquals(1, closed.get())
            assertEquals(0, errors.get())
        } finally {
            releaseAuthentication.countDown()
            channel.close()
            releaseReceiver.countDown()
            server.close()
            try { receiver.get(10, TimeUnit.SECONDS) } finally { executor.shutdownNow() }
        }
    }

    private fun roundTrip(legacy: Boolean, bindNetwork: Boolean = false, afterReady: (CastV2Channel) -> Unit = {}) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val network = if (bindNetwork) LocalCastNetwork.select(manager) else null
        val address = if (bindNetwork) manager.getLinkProperties(network)?.linkAddresses
            ?.firstOrNull { it.address is Inet4Address }?.address else InetAddress.getByName("127.0.0.1")
        assumeTrue("A LAN interface is required for the bound-socket test", address != null)
        val server = CastVmFixture.context().serverSocketFactory.createServerSocket(0, 1, address) as SSLServerSocket
        server.soTimeout = 7_000
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val receiver = executor.submit(Callable {
            (server.accept() as SSLSocket).use { socket ->
                socket.soTimeout = 7_000
                val input = socket.inputStream
                val output = socket.outputStream
                authenticateReceiver(input, output, legacy)
                val connect = requireNotNull(CastMessageCodec.readFramed(input))
                assertEquals(CastV2Channel.NS_CONNECTION, connect.namespace)
                assertEquals("CONNECT", JSONObject(connect.payloadUtf8!!).getString("type"))
                CastMessageCodec.writeFramed(output, CastMessage.utf8("receiver-0", "sender-0",
                    "urn:x-cast:com.litube.test", "{\"type\":\"READY\"}"))
                output.flush()
                check(release.await(10, TimeUnit.SECONDS))
            }
        })
        val opened = CountDownLatch(1)
        val received = CountDownLatch(1)
        val failure = AtomicReference<String>()
        lateinit var channel: CastV2Channel
        channel = CastV2Channel(requireNotNull(address).hostAddress!!, server.localPort,
            object : CastV2Channel.Listener {
                override fun onOpened() { channel.connect("receiver-0"); opened.countDown() }
                override fun onMessage(message: CastMessage) {
                    if (message.payloadUtf8 == "{\"type\":\"READY\"}") received.countDown()
                }
                override fun onError(reason: String) { failure.set(reason); opened.countDown(); received.countDown() }
                override fun onClosed() = Unit
            }, CastVmFixture.authenticator(), network)
        try {
            channel.open()
            assertTrue("Channel never opened", opened.await(10, TimeUnit.SECONDS))
            assertNull(failure.get(), failure.get())
            assertTrue("No authenticated application response", received.await(10, TimeUnit.SECONDS))
            assertNull(failure.get(), failure.get())
            assertTrue(channel.isOpen())
            afterReady(channel)
            assertNull(failure.get(), failure.get())
        } finally {
            channel.close()
            release.countDown()
            server.close()
            try { receiver.get(10, TimeUnit.SECONDS) } finally { executor.shutdownNow() }
        }
    }

    private fun authenticateReceiver(input: InputStream, output: OutputStream, legacy: Boolean) {
        val request = requireNotNull(CastMessageCodec.readFramed(input))
        assertEquals(CastDeviceAuth.NAMESPACE, request.namespace)
        val challenge = Proto.parse(Proto.bytes(Proto.parse(request.payloadBinary!!), 1))
        assertEquals(1L, Proto.number(challenge, 1))
        assertEquals(1L, Proto.number(challenge, 3))
        val nonce = Proto.bytes(challenge, 2)
        assertEquals(16, nonce.size)
        CastMessageCodec.writeFramed(output, CastMessage(0, "receiver-0", "sender-0",
            CastDeviceAuth.NAMESPACE, 1, null, Proto.encode(2 to CastVmFixture.response(nonce, legacy = legacy))))
        output.flush()
    }
}
