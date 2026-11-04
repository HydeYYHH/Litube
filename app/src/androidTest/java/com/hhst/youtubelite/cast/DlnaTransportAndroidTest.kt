package com.hhst.youtubelite.cast

import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class DlnaTransportAndroidTest {
    @Test fun platformXmlAndSoapRoundTripUseTheAdvertisedEndpoint() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""<root xmlns="urn:schemas-upnp-org:device-1-0"><device>
                <friendlyName>Projector &amp; TV</friendlyName><serviceList><service>
                <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><controlURL>/render/control</controlURL>
                </service></serviceList></device></root>"""))
            repeat(4) { server.enqueue(MockResponse().setBody("<Envelope><Body><Response/></Body></Envelope>")) }
            val client = OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).build()
            val transport = DlnaTransport(client)
            val renderer = requireNotNull(transport.describe(server.url("/description.xml")))
            assertEquals("Projector & TV", renderer.name)
            assertTrue(transport.cast(renderer, "http://phone/stream/v", "Video", "video/mp4"))
            assertTrue(transport.seek(20_000))
            assertTrue(transport.pause())
            assertEquals("GET", server.takeRequest().method)
            for (action in listOf("SetAVTransportURI", "Play", "Seek", "Pause")) {
                val request = server.takeRequest()
                assertEquals("/render/control", request.path)
                assertEquals("\"${renderer.serviceType}#$action\"", request.getHeader("SOAPAction"))
            }
        }
    }

    @Test fun readsTheAdvertisedLanDescriptionWithoutSendingPlaybackCommands() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val url = arguments.getString("dlnaDescriptionUrl")
        assumeTrue(arguments.getString("network") == "1" && url != null)
        val client = OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).callTimeout(5, TimeUnit.SECONDS).build()
        val renderer = DlnaTransport(client).describe(requireNotNull(DlnaDescription.location(requireNotNull(url))))
        assertNotNull(renderer)
        assertTrue(requireNotNull(renderer).name.isNotBlank())
        assertTrue(renderer.serviceType.contains("AVTransport"))
        assertTrue(renderer.control.encodedPath.isNotBlank())
    }
}
