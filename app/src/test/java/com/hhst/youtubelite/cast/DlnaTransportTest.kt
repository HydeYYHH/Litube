package com.hhst.youtubelite.cast

import com.yinnho.upnpcast.DLNACast
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class DlnaTransportTest {
    private val client = OkHttpClient.Builder().callTimeout(2, TimeUnit.SECONDS).build()

    private fun description(name: String = "Living &amp; room", service: String = "<controlURL>../control</controlURL>") = """
        <d:root xmlns:d="urn:schemas-upnp-org:device-1-0"><d:device>
        <d:friendlyName>$name</d:friendlyName><d:modelName>Renderer</d:modelName>
        <d:serviceList><d:service test="yes"><d:serviceType>urn:schemas-upnp-org:service:AVTransport:2</d:serviceType>
        ${service.replace("<controlURL>", "<d:controlURL>").replace("</controlURL>", "</d:controlURL>")}
        </d:service></d:serviceList></d:device></d:root>
    """.trimIndent()

    private fun response(values: String = "") = MockResponse().setBody(
        "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>$values</s:Body></s:Envelope>")

    @Test fun pauseIsRetriedWhenALateMediaOpenResumesTheRenderer() = runBlocking {
        MockWebServer().use { server ->
            val pauses = AtomicInteger()
            val polls = AtomicInteger()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.getHeader("SOAPAction").orEmpty().contains("#Pause") -> response().also { pauses.incrementAndGet() }
                    request.getHeader("SOAPAction").orEmpty().contains("#GetTransportInfo") -> {
                        val firstPausedEcho = polls.incrementAndGet() == 1
                        response("<CurrentTransportState>${if (firstPausedEcho || pauses.get() >= 2) "PAUSED_PLAYBACK" else "PLAYING"}</CurrentTransportState>")
                    }
                    else -> response()
                }
            }
            val transport = DlnaTransport(client)
            transport.cast(DlnaDescription.parse(description().toByteArray(), server.url("/desc"))!!,
                "http://phone/video", "Title", "video/mp4")
            assertTrue(transport.pauseAndConfirm())
            assertEquals(2, pauses.get())
            assertTrue(polls.get() >= 5)
        }
    }

    @Test fun everySoapCommandOpensANewConnectionForRenderersWithBrokenKeepAlive() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(description()))
            repeat(3) { server.enqueue(response()) }
            val transport = DlnaTransport(client)
            val renderer = requireNotNull(transport.describe(server.url("/desc.xml")))
            assertTrue(transport.cast(renderer, "http://phone/stream/v", "Video", "video/mp4"))
            assertTrue(transport.pause())
            repeat(4) {
                val request = server.takeRequest(3, TimeUnit.SECONDS)!!
                assertEquals("close", request.getHeader("Connection"))
                assertEquals("A renderer must not receive Play on a reused SetURI socket", 0, request.sequenceNumber)
            }
        }
    }

    @Test fun namesNamespacesAndRelativeControlPathsAreParsed() {
        MockWebServer().use { server ->
            val target = DlnaDescription.parse(description().toByteArray(), server.url("/renderer/desc.xml"))!!
            assertEquals("Living & room", target.name)
            assertEquals(server.url("/control"), target.control)
            assertEquals("urn:schemas-upnp-org:service:AVTransport:2", target.serviceType)
        }
    }

    @Test fun blankNamesFallBackAndNestedServicesStayWithTheirDevice() {
        MockWebServer().use { server ->
            val xml = """<root><device><friendlyName>Media server</friendlyName><deviceList><device>
                <friendlyName> </friendlyName><modelName>Projector</modelName><serviceList><service>
                <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><controlURL>/av</controlURL>
                </service></serviceList></device></deviceList></device></root>"""
            assertEquals("Projector", DlnaDescription.parse(xml.toByteArray(), server.url("/desc"))!!.name)
            assertNull(DlnaDescription.parse("<root><device><friendlyName>NAS</friendlyName></device></root>".toByteArray(), server.url("/desc")))
        }
    }

    @Test fun urlBaseAndAbsoluteServiceUrlsKeepTheirPorts() {
        MockWebServer().use { server ->
            val xml = description().replace("<d:device>", "<d:URLBase>http://127.0.0.1:1234/base/</d:URLBase><d:device>")
            assertEquals("http://127.0.0.1:1234/control", DlnaDescription.parse(xml.toByteArray(), server.url("/desc"))!!.control.toString())
        }
    }

    @Test fun malformedXmlKeepsTheStageEndpointAndHttpStatus() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("<root><unclosed>"))
            val transport = DlnaTransport(client)
            val error = runCatching { transport.describe(server.url("/desc.xml")) }.exceptionOrNull()
            assertTrue("expected DlnaRequestException, got $error", error is DlnaRequestException)
            val typed = error as DlnaRequestException
            assertEquals("description", typed.stage)
            assertEquals(server.url("/desc.xml"), typed.endpoint)
            assertEquals(200, typed.status)
        }
    }

    @Test fun commandXmlParseFailuresCarryTheActionStage() = runBlocking {
        MockWebServer().use { server ->
            val target = DlnaDescription.parse(description().toByteArray(), server.url("/renderer/desc.xml"))!!
            val transport = DlnaTransport(client)
            server.enqueue(response()); server.enqueue(response())
            assertTrue(transport.cast(target, "http://upstream/v.mp4", "t", "video/mp4"))
            server.enqueue(MockResponse().setBody("<not-xml"))
            val error = runCatching { transport.progress() }.exceptionOrNull()
            assertTrue(error is DlnaRequestException)
            val typed = error as DlnaRequestException
            assertEquals("GetPositionInfo", typed.stage)
            assertEquals(server.url("/control"), typed.endpoint)
            assertEquals(200, typed.status)
        }
    }

    @Test fun externalEntitiesAndOversizedResponsesAreRejected() {
        MockWebServer().use { server ->
            assertThrows(IOException::class.java) {
                DlnaDescription.parse("<!DOCTYPE root [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><root>&x;</root>".toByteArray(), server.url("/desc"))
            }
            assertThrows(IllegalArgumentException::class.java) {
                DlnaDescription.parse(ByteArray(DlnaDescription.MAX_BYTES + 1), server.url("/desc"))
            }
        }
    }

    @Test fun discoveryUsesCompleteCrLfAndValidatesLocationHeaders() {
        val request = ssdpSearch("ssdp:all")
        assertTrue(request.endsWith("\r\n\r\n"))
        assertFalse(request.replace("\r\n", "").contains('\n'))
        assertEquals("http://192.168.1.2:1234/desc", ssdpLocation("HTTP/1.1 200 OK\r\nLoCaTiOn: http://192.168.1.2:1234/desc\r\n\r\n"))
        assertNull(ssdpLocation("HTTP/1.1 200 OK\r\nLOCATION: file:///tmp/desc\r\n"))
        assertNull(ssdpLocation("NOTIFY * HTTP/1.1\r\nLOCATION: http://192.168.1.2/desc\r\n"))
    }

    @Test fun descriptionFailureIsRetriedInsteadOfCachingAnEmptyDevice() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setBody(description()))
            val transport = DlnaTransport(client)
            try { transport.describe(server.url("/desc")); fail("Expected HTTP failure") } catch (error: DlnaRequestException) {
                assertEquals("description", error.stage); assertEquals(503, error.status)
            }
            assertEquals("Living & room", transport.describe(server.url("/desc"))!!.name)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun setUriPlayAndSeekUseAdvertisedServiceAndPreserveEscaping() = runBlocking {
        MockWebServer().use { server ->
            repeat(3) { server.enqueue(response()) }
            val target = DlnaDescription.parse(description().toByteArray(), server.url("/device/desc"))!!
            val transport = DlnaTransport(client)
            assertTrue(transport.cast(target, "http://phone/stream?x=1&y=2", "A & <B>", "video/mp4"))
            assertTrue(transport.seek(65_000))
            val set = server.takeRequest()
            assertEquals("/control", set.path)
            assertEquals("\"${target.serviceType}#SetAVTransportURI\"", set.getHeader("SOAPAction"))
            val fields = DlnaDescription.values(set.body.readByteArray())
            assertEquals("http://phone/stream?x=1&y=2", fields["CurrentURI"])
            assertEquals("A & <B>", DlnaDescription.values(fields["CurrentURIMetaData"]!!.toByteArray())["title"])
            assertTrue(server.takeRequest().getHeader("SOAPAction")!!.endsWith("#Play\""))
            assertEquals("00:01:05", DlnaDescription.values(server.takeRequest().body.readByteArray())["Target"])
        }
    }

    @Test fun waitsForTheNewTrackBeforeAllowingResumeSeekAndPause() = runBlocking {
        MockWebServer().use { server ->
            val positions = AtomicInteger()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.getHeader("SOAPAction").orEmpty().contains("#GetTransportInfo") ->
                        response("<CurrentTransportState>PLAYING</CurrentTransportState>")
                    request.getHeader("SOAPAction").orEmpty().contains("#GetPositionInfo") -> {
                        val track = if (positions.incrementAndGet() <= 2) "old" else "new"
                        response("<TrackURI>http://phone/$track</TrackURI><TrackDuration>00:00:00</TrackDuration>")
                    }
                    else -> response()
                }
            }
            val transport = DlnaTransport(client)
            val target = DlnaDescription.parse(description().toByteArray(), server.url("/desc"))!!
            transport.cast(target, "http://phone/new", "Title", "video/mp4")
            transport.awaitMediaReady("http://phone/new")
            transport.pause()
            assertTrue(positions.get() >= 3)
            val actions = List(server.requestCount) { server.takeRequest().getHeader("SOAPAction")!!.substringAfter('#').trimEnd('"') }
            assertEquals(listOf("SetAVTransportURI", "Play"), actions.take(2))
            assertEquals("Pause", actions.last())
        }
    }

    @Test fun playingDoesNotRequireDurationOrAnUnmodifiedTrackUri() = runBlocking {
        val metadata = listOf(
            "<TrackURI>http://phone/new</TrackURI><TrackDuration>00:00:00</TrackDuration>",
            "<TrackURI>NOT_IMPLEMENTED</TrackURI><TrackDuration>NOT_IMPLEMENTED</TrackDuration>",
            "",
            "<TrackURI>http://renderer/internal/42</TrackURI>",
        )
        for (values in metadata) MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = when {
                    request.getHeader("SOAPAction").orEmpty().contains("#GetTransportInfo") ->
                        response("<CurrentTransportState>PLAYING</CurrentTransportState>")
                    request.getHeader("SOAPAction").orEmpty().contains("#GetPositionInfo") -> response(values)
                    else -> response()
                }
            }
            val transport = DlnaTransport(client)
            transport.cast(DlnaDescription.parse(description().toByteArray(), server.url("/desc"))!!,
                "http://phone/new", "Title", "video/mp4")
            transport.awaitMediaReady("http://phone/new", timeoutMs = 4_000)
        }
    }

    @Test fun unsupportedPositionInfoDoesNotBlockAPlayingRenderer() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = when {
                    request.getHeader("SOAPAction").orEmpty().contains("#GetTransportInfo") ->
                        response("<CurrentTransportState>PLAYING</CurrentTransportState>")
                    request.getHeader("SOAPAction").orEmpty().contains("#GetPositionInfo") ->
                        response("<Fault><errorCode>401</errorCode></Fault>").setResponseCode(500)
                    else -> response()
                }
            }
            val transport = DlnaTransport(client)
            transport.cast(DlnaDescription.parse(description().toByteArray(), server.url("/desc"))!!,
                "http://phone/new", "Title", "video/mp4")
            transport.awaitMediaReady("http://phone/new", timeoutMs = 3_000)
        }
    }

    @Test fun bufferingAloneDoesNotCountAsReadyAndReportsTheLastState() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) =
                    response("<CurrentTransportState>TRANSITIONING</CurrentTransportState>")
            }
            val transport = DlnaTransport(client)
            transport.cast(DlnaDescription.parse(description().toByteArray(), server.url("/desc"))!!,
                "http://phone/new", "Title", "video/mp4")
            val error = runCatching { transport.awaitMediaReady("http://phone/new", timeoutMs = 350) }.exceptionOrNull()
            assertTrue(error is DlnaRequestException)
            assertEquals("media_ready", (error as DlnaRequestException).stage)
            assertTrue(error.message!!.contains("BUFFERING"))
        }
    }

    @Test fun soapFaultKeepsTheFailingActionAndDeviceError() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(response("<Fault><detail><UPnPError><errorCode>714</errorCode><errorDescription>Illegal MIME-Type</errorDescription></UPnPError></detail></Fault>").setResponseCode(500))
            val transport = DlnaTransport(client)
            val target = DlnaDescription.parse(description().toByteArray(), server.url("/desc"))!!
            try { transport.cast(target, "http://phone/video", "Title", "video/mp4"); fail("Expected rejection") } catch (error: DlnaRequestException) {
                assertEquals("SetAVTransportURI", error.stage); assertEquals("714", error.code); assertEquals(500, error.status)
                assertTrue(error.message!!.contains("Illegal MIME-Type"))
            }
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun transientSeekRejectionIsRetriedAndProgressComesFromTheRenderer() = runBlocking {
        MockWebServer().use { server ->
            repeat(2) { server.enqueue(response()) }
            server.enqueue(response("<Fault><errorCode>701</errorCode></Fault>").setResponseCode(500))
            server.enqueue(response())
            server.enqueue(response("<RelTime>00:01:05.500</RelTime><TrackDuration>01:00:00</TrackDuration>"))
            server.enqueue(response("<CurrentTransportState>PAUSED_PLAYBACK</CurrentTransportState>"))
            val transport = DlnaTransport(client)
            transport.cast(DlnaDescription.parse(description().toByteArray(), server.url("/desc"))!!, "http://phone/video", "Title", "video/mp4")
            assertTrue(transport.seek(65_500))
            assertEquals(65_500L to 3_600_000L, transport.progress())
            assertEquals(DLNACast.PlaybackState.PAUSED, transport.state())
            assertEquals(6, server.requestCount)
        }
    }

    @Test fun cancellationClosesAnInFlightDescriptionRequest() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(description()).setHeadersDelay(1, TimeUnit.SECONDS))
            val pending = async { DlnaTransport(client).describe(server.url("/desc")) }
            delay(100)
            pending.cancelAndJoin()
            assertTrue(pending.isCancelled)
        }
    }

    @Test fun aRendererThatRejectsPausedSeekIsResumedAndPausedAgain() = runBlocking {
        MockWebServer().use { server ->
            repeat(2) { server.enqueue(response()) }
            server.enqueue(response("<Fault><errorCode>501</errorCode></Fault>").setResponseCode(500))
            server.enqueue(response("<CurrentTransportState>PAUSED_PLAYBACK</CurrentTransportState>"))
            server.enqueue(response())
            server.enqueue(response("<CurrentTransportState>PLAYING</CurrentTransportState>"))
            repeat(2) { server.enqueue(response()) }
            val transport = DlnaTransport(client)
            transport.cast(DlnaDescription.parse(description().toByteArray(), server.url("/desc"))!!,
                "http://phone/video", "Title", "video/mp4")
            assertTrue(transport.seek(20_000))
            val actions = List(8) { server.takeRequest().getHeader("SOAPAction")!!.substringAfter('#').trimEnd('"') }
            assertEquals(listOf("SetAVTransportURI", "Play", "Seek", "GetTransportInfo", "Play", "GetTransportInfo", "Seek", "Pause"), actions)
        }
    }

    @Test fun aPlayingRendererIsNotResumedOrPausedAfterASeekFailure() = runBlocking {
        MockWebServer().use { server ->
            repeat(2) { server.enqueue(response()) }
            server.enqueue(response("<Fault><errorCode>501</errorCode></Fault>").setResponseCode(500))
            server.enqueue(response("<CurrentTransportState>PLAYING</CurrentTransportState>"))
            val transport = DlnaTransport(client)
            transport.cast(DlnaDescription.parse(description().toByteArray(), server.url("/desc"))!!,
                "http://phone/video", "Title", "video/mp4")
            val error = runCatching { transport.seek(20_000) }.exceptionOrNull() as DlnaRequestException
            assertEquals("Seek", error.stage)
            assertEquals("501", error.code)
            assertEquals(4, server.requestCount)
        }
    }

    @Test fun aFailedPausedSeekRestoresPauseAndKeepsTheSeekError() = runBlocking {
        MockWebServer().use { server ->
            repeat(2) { server.enqueue(response()) }
            server.enqueue(response("<Fault><errorCode>501</errorCode></Fault>").setResponseCode(500))
            server.enqueue(response("<CurrentTransportState>PAUSED_PLAYBACK</CurrentTransportState>"))
            server.enqueue(response())
            server.enqueue(response("<CurrentTransportState>PLAYING</CurrentTransportState>"))
            server.enqueue(response("<Fault><errorCode>710</errorCode></Fault>").setResponseCode(500))
            server.enqueue(response())
            val transport = DlnaTransport(client)
            transport.cast(DlnaDescription.parse(description().toByteArray(), server.url("/desc"))!!,
                "http://phone/video", "Title", "video/mp4")
            val error = runCatching { transport.seek(20_000) }.exceptionOrNull() as DlnaRequestException
            assertEquals("Seek", error.stage)
            assertEquals("710", error.code)
            val actions = List(8) { server.takeRequest().getHeader("SOAPAction")!! }
            assertTrue(actions.last().endsWith("#Pause\""))
        }
    }
}
