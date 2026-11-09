package com.hhst.youtubelite.cast

import com.hhst.youtubelite.extractor.Format
import com.hhst.youtubelite.player.engine.CastSource
import com.yinnho.upnpcast.DLNACast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DlnaControllerTest {
    private class FakeEngine : DlnaEngine {
        var searchResult: List<DLNACast.Device> = emptyList()
        var castResult = true
        var castError: Exception? = null
        var transport = DLNACast.PlaybackState.PLAYING
        var progress: Pair<Long, Long>? = 0L to 100_000L
        val castCalls = mutableListOf<Triple<String, String, String>>()
        val seeks = mutableListOf<Long>()
        var stopCalls = 0
        var pauseCalls = 0
        var clearedProgress = 0
        var castGate: CompletableDeferred<Boolean>? = null
        var castDelay = 0L
        var readyDelay = 0L
        var seekDelay = 0L
        var pauseDelay = 0L
        var readyCalls = 0

        override suspend fun search(timeoutMs: Long) = searchResult
        override suspend fun cast(device: DLNACast.Device, url: String, title: String, mimeType: String): Boolean {
            castCalls += Triple(device.id, url, title)
            castError?.let { throw it }
            delay(castDelay)
            return castGate?.await() ?: castResult
        }
        override suspend fun awaitMediaReady(url: String) { readyCalls++; delay(readyDelay) }
        override suspend fun play(): Boolean = true
        override suspend fun pause(): Boolean { pauseCalls++; delay(pauseDelay); return true }
        override suspend fun stop(): Boolean { stopCalls++; return true }
        override suspend fun seek(positionMs: Long): Boolean { seeks += positionMs; delay(seekDelay); return true }
        override suspend fun progress() = progress
        override suspend fun transportState() = transport
        override fun clearProgressCache() { clearedProgress++ }
    }

    private class FakePlayer : DlnaPlayerDelegate {
        var expects = true
        var pos = 0L
        var loads = 0
        var resets = 0
        override fun loading(source: CastSource, startMs: Long, autoplay: Boolean) { loads++; pos = startMs; expects = autoplay }
        override fun status(transport: DLNACast.PlaybackState?, position: Long?, duration: Long?) {
            if (position != null) pos = position
        }
        override fun reset() { resets++ }
        override fun expectsPlayback() = expects
        override fun position() = pos
    }

    private val engine = FakeEngine()
    private val player = FakePlayer()
    private var castSource = CastSource(
        videoId = "abc12345678",
        title = "Test video",
        durationSec = 100,
        video = Format(),
        audio = null,
        author = "Channel",
        thumbnailUrl = null,
    )

    private fun controller(
        scope: CoroutineScope,
        playerFactory: (CoroutineScope, DlnaEngine, (Long) -> Unit) -> DlnaPlayerDelegate? =
            { _, _, _ -> player },
    ) = DlnaController(
        appContext = android.content.ContextWrapper(null),
        engine = engine,
        scope = scope,
        initLibrary = {},
        playerFactory = playerFactory,
    )

    @Test
    fun `startCasting without a selected device is refused`() = runTest {
        val dlna = controller(backgroundScope)
        val started = dlna.startCasting(castSource, 0L, "http://phone/stream/v")
        assertFalse(started)
        assertTrue(engine.castCalls.isEmpty())
        assertFalse(dlna.state.value.session)
        assertTrue(requireNotNull(dlna.state.value.failure).text.contains("Stage: selection"))
    }

    @Test fun `quality reload restores paused playback after loading and seeking`() = runTest {
        val dlna = controller(backgroundScope)
        engine.searchResult = listOf(DLNACast.Device("http://192.168.1.2:1234/desc", "Projector", "192.168.1.2", true))
        dlna.setDiscoveryEnabled(true)
        runCurrent()
        dlna.selectDevice(dlna.state.value.devices.single().id)
        assertTrue(dlna.startCasting(castSource, 12_000, "http://phone/stream/v?g=2", autoplay = false))
        assertEquals(listOf(12_000L), engine.seeks)
        assertEquals(1, engine.pauseCalls)
        assertFalse(player.expects)
        assertEquals(12_000L, player.pos)
        assertEquals(1, engine.readyCalls)
    }

    @Test fun `slow load readiness seek and pause have independent deadlines`() = runTest {
        val dlna = controller(backgroundScope)
        engine.searchResult = listOf(DLNACast.Device("udn-1", "TV", "192.168.1.50", true))
        dlna.setDiscoveryEnabled(true)
        runCurrent()
        dlna.selectDevice(dlna.state.value.devices.single().id)
        engine.castDelay = 9_000
        engine.readyDelay = 9_000
        engine.seekDelay = 9_000
        engine.pauseDelay = 9_000
        assertTrue(dlna.startCasting(castSource, 12_000, "http://phone/video", autoplay = false))
        assertEquals(listOf(12_000L), engine.seeks)
        assertEquals(1, engine.pauseCalls)
        assertFalse(player.expects)
        assertNull(dlna.state.value.failure)
        dlna.endSession()
    }

    @Test fun `normal startup confirms readiness and tolerates missing position metadata`() = runTest {
        val dlna = controller(backgroundScope)
        engine.searchResult = listOf(DLNACast.Device("udn-1", "TV", "192.168.1.50", true))
        dlna.setDiscoveryEnabled(true)
        runCurrent()
        dlna.selectDevice(dlna.state.value.devices.single().id)
        engine.readyDelay = 12_000
        engine.progress = null
        assertTrue(dlna.startCasting(castSource, 0, "http://phone/video"))
        advanceTimeBy(35_000)
        assertEquals(1, engine.readyCalls)
        assertTrue(dlna.state.value.session)
        assertNull(dlna.state.value.failure)
        dlna.endSession()
    }

    @Test fun `readiness timeout reports its stage and never issues seek or pause`() = runTest {
        val dlna = controller(backgroundScope)
        engine.searchResult = listOf(DLNACast.Device("udn-1", "TV", "192.168.1.50", true))
        dlna.setDiscoveryEnabled(true)
        runCurrent()
        dlna.selectDevice(dlna.state.value.devices.single().id)
        engine.readyDelay = 60_000
        assertFalse(dlna.startCasting(castSource, 12_000, "http://phone/video", autoplay = false))
        assertTrue(requireNotNull(dlna.state.value.failure).text.contains("Stage: media_ready"))
        assertTrue(engine.seeks.isEmpty())
        assertEquals(0, engine.pauseCalls)
        dlna.stopCasting()
    }

    @Test fun `quality reload fences old player commands before waiting for the transport`() = runTest {
        val dlna = controller(backgroundScope)
        engine.searchResult = listOf(DLNACast.Device("http://192.168.1.2:1234/desc", "Projector", "192.168.1.2", true))
        dlna.setDiscoveryEnabled(true)
        runCurrent()
        dlna.selectDevice(dlna.state.value.devices.single().id)
        assertTrue(dlna.startCasting(castSource, 0, "http://phone/stream/v?g=1"))
        engine.castGate = CompletableDeferred()
        val reload = backgroundScope.async { dlna.startCasting(castSource, 12_000, "http://phone/stream/v?g=2", autoplay = false) }
        runCurrent()
        assertEquals(2, player.loads)
        assertFalse(player.expects)
        assertFalse(reload.isCompleted)
        engine.castGate!!.complete(true)
        runCurrent()
        assertTrue(reload.await())
        assertEquals(2, player.loads)
    }

    @Test fun `cast failure retains the device stage and response code until dismissed`() = runTest {
        val dlna = controller(backgroundScope)
        engine.searchResult = listOf(DLNACast.Device("http://192.168.1.2:1234/desc", "Projector", "192.168.1.2", true))
        dlna.setDiscoveryEnabled(true)
        advanceTimeBy(5_000)
        dlna.selectDevice(dlna.state.value.devices.single().id)
        engine.castError = DlnaRequestException("SetAVTransportURI",
            requireNotNull(DlnaDescription.location("http://192.168.1.2:1234/control")), 500, "714", "Illegal MIME-Type")
        assertFalse(dlna.startCasting(castSource, 0, "http://phone/stream/v"))
        val report = requireNotNull(dlna.state.value.failure)
        assertTrue(report.text.contains("Projector"))
        assertTrue(report.text.contains("Stage: SetAVTransportURI"))
        assertTrue(report.text.contains("HTTP: 500; UPnP: 714"))
        assertFalse(report.text.contains("http://phone/stream/v"))
        dlna.endSession()
        assertEquals(report, dlna.state.value.failure)
        dlna.clearFailure()
        assertNull(dlna.state.value.failure)
    }

    @Test fun `a local network change ends the session with a report`() = runTest {
        val dlna = controller(backgroundScope)
        engine.searchResult = listOf(DLNACast.Device("http://192.168.1.2:1234/desc", "Projector", "192.168.1.2", true))
        dlna.setDiscoveryEnabled(true)
        advanceTimeBy(5_000)
        dlna.selectDevice(dlna.state.value.devices.single().id)
        assertTrue(dlna.startCasting(castSource, 0, "http://phone/stream/v"))
        assertTrue(dlna.state.value.session)
        dlna.onLocalNetworkChanged()
        advanceTimeBy(2_000)
        runCurrent()
        assertFalse(dlna.state.value.session)
        assertTrue(dlna.state.value.failure?.text?.contains("network") == true)
        dlna.endSession()
    }

    @Test
    fun `startCasting pushes the muxed url to the prefixed device route`() = runTest {
        val dlna = controller(backgroundScope)
        dlna.setDiscoveryEnabled(true)
        engine.searchResult = listOf(DLNACast.Device("udn-1", "Living Room TV", "192.168.1.50", true))
        advanceTimeBy(5_000)
        val route = dlna.state.value.devices.single()
        assertEquals(DlnaController.ID_PREFIX + "udn-1", route.id)
        assertEquals(CastDevice.KIND_DLNA, route.kind)
        assertTrue(dlna.state.value.available)

        dlna.selectDevice(route.id)
        val started = dlna.startCasting(castSource, 5_000L, "http://phone:8080/stream/v")
        assertTrue(started)
        advanceTimeBy(100)
        assertTrue(dlna.state.value.session)
        assertEquals("Living Room TV", dlna.state.value.deviceName)
        assertEquals("udn-1", engine.castCalls.single().first)
        assertEquals("http://phone:8080/stream/v", engine.castCalls.single().second)
        assertEquals("Test video", engine.castCalls.single().third)
        // SetAVTransportURI starts at 0: the resume lands as a follow-up seek.
        assertEquals(listOf(5_000L), engine.seeks)
        assertTrue(engine.clearedProgress >= 1)
        dlna.endSession()
    }

    @Test
    fun `same-session recast reuses the live player instead of building a new one`() = runTest {
        val built = mutableListOf<FakePlayer>()
        val dlna = controller(backgroundScope) { _, _, _ -> FakePlayer().also { built += it } }
        dlna.setDiscoveryEnabled(true)
        engine.searchResult = listOf(DLNACast.Device("udn-1", "TV", "192.168.1.50", true))
        advanceTimeBy(5_000)
        dlna.selectDevice(dlna.state.value.devices.single().id)
        assertTrue(dlna.startCasting(castSource, 0L, "http://phone/stream/v"))
        // Recast must reuse the player the engine attached to: a rebuilt one orphans its status feed and remote-stop echo.
        assertTrue(dlna.startCasting(castSource, 0L, "http://phone:8080/stream/v"))
        assertEquals(1, built.size)
        assertEquals(2, built.single().loads)
        assertTrue(dlna.state.value.session)
        dlna.endSession()
    }

    @Test
    fun `renderer rejection keeps the session closed`() = runTest {
        val dlna = controller(backgroundScope)
        dlna.setDiscoveryEnabled(true)
        engine.searchResult = listOf(DLNACast.Device("udn-1", "TV", "192.168.1.50", true))
        advanceTimeBy(5_000)
        dlna.selectDevice(dlna.state.value.devices.single().id)
        engine.castResult = false
        var failures = 0
        dlna.onSessionFailed = { failures++ }
        val started = dlna.startCasting(castSource, 0L, "http://phone/stream/v")
        assertFalse(started)
        assertFalse(dlna.state.value.session)
        // The synchronous rejection is returned, not echoed as a session failure.
        assertEquals(0, failures)
        dlna.stopCasting()
        runCurrent()
        assertEquals(1, engine.stopCalls)
        dlna.endSession()
    }

    @Test
    fun `renderer-initiated stop echoes the session end once`() = runTest {
        val dlna = controller(backgroundScope)
        dlna.setDiscoveryEnabled(true)
        engine.searchResult = listOf(DLNACast.Device("udn-1", "TV", "192.168.1.50", true))
        advanceTimeBy(5_000)
        dlna.selectDevice(dlna.state.value.devices.single().id)
        assertTrue(dlna.startCasting(castSource, 0L, "http://phone/stream/v"))
        advanceTimeBy(DlnaController.POLL_FOR_TEST_MS + 1)
        var ended = 0
        dlna.onSessionEnded = { ended++ }
        engine.progress = 50_000L to 100_000L
        engine.transport = DLNACast.PlaybackState.STOPPED
        player.expects = true
        advanceTimeBy(DlnaController.POLL_FOR_TEST_MS * 3)
        assertEquals(1, ended)
        assertFalse(dlna.state.value.session)
        assertEquals(50_000L, dlna.stopCasting())
        assertEquals(0, engine.stopCalls)
        dlna.endSession()
    }

    @Test
    fun `stop during a pending load cannot reopen the session`() = runTest {
        val dlna = controller(backgroundScope)
        engine.searchResult = listOf(DLNACast.Device("udn-1", "TV", "192.168.1.50", true))
        dlna.setDiscoveryEnabled(true)
        advanceTimeBy(5_000)
        dlna.selectDevice(dlna.state.value.devices.single().id)
        val gate = CompletableDeferred<Boolean>().also { engine.castGate = it }
        val load = async { dlna.startCasting(castSource, 0L, "http://phone/stream/v") }
        runCurrent()
        dlna.stopCasting()
        gate.complete(true)
        runCurrent()
        assertTrue(load.isCancelled || !load.await())
        assertFalse(dlna.state.value.session)
        assertEquals(0, player.loads)
        assertEquals(1, engine.stopCalls)
    }

    @Test
    fun `startup stopped status waits for an actual playback sample`() = runTest {
        val dlna = controller(backgroundScope)
        engine.searchResult = listOf(DLNACast.Device("udn-1", "TV", "192.168.1.50", true))
        dlna.setDiscoveryEnabled(true)
        advanceTimeBy(5_000)
        dlna.selectDevice(dlna.state.value.devices.single().id)
        assertTrue(dlna.startCasting(castSource, 0L, "http://phone/stream/v"))
        engine.transport = DLNACast.PlaybackState.STOPPED
        advanceTimeBy(DlnaController.POLL_FOR_TEST_MS * 3)
        assertTrue(dlna.state.value.session)
        engine.transport = DLNACast.PlaybackState.PLAYING
        advanceTimeBy(DlnaController.POLL_FOR_TEST_MS + 1)
        engine.transport = DLNACast.PlaybackState.STOPPED
        advanceTimeBy(DlnaController.POLL_FOR_TEST_MS + 1)
        assertFalse(dlna.state.value.session)
    }

    @Test
    fun `startup buffering without playback eventually fails`() = runTest {
        val dlna = controller(backgroundScope)
        engine.searchResult = listOf(DLNACast.Device("udn-1", "TV", "192.168.1.50", true))
        dlna.setDiscoveryEnabled(true)
        advanceTimeBy(5_000)
        dlna.selectDevice(dlna.state.value.devices.single().id)
        assertTrue(dlna.startCasting(castSource, 0L, "http://phone/stream/v"))
        var failures = 0
        dlna.onSessionFailed = { failures++ }
        engine.transport = DLNACast.PlaybackState.BUFFERING
        advanceTimeBy(20_000)
        assertTrue(dlna.state.value.session)
        advanceTimeBy(15_000)
        assertFalse(dlna.state.value.session)
        assertEquals(1, failures)
    }

    @Test
    fun `a stopped sample at the media tail does not disconnect repeat playback`() = runTest {
        val dlna = controller(backgroundScope)
        engine.searchResult = listOf(DLNACast.Device("udn-1", "TV", "192.168.1.50", true))
        dlna.setDiscoveryEnabled(true)
        advanceTimeBy(5_000)
        dlna.selectDevice(dlna.state.value.devices.single().id)
        assertTrue(dlna.startCasting(castSource, 0L, "http://phone/stream/v"))
        advanceTimeBy(DlnaController.POLL_FOR_TEST_MS + 1)
        engine.progress = 100_000L to 100_000L
        engine.transport = DLNACast.PlaybackState.STOPPED
        advanceTimeBy(DlnaController.POLL_FOR_TEST_MS + 1)
        assertTrue(dlna.state.value.session)
        dlna.endSession()
    }

    @Test
    fun `renderer error echoes a session failure`() = runTest {
        val dlna = controller(backgroundScope)
        dlna.setDiscoveryEnabled(true)
        engine.searchResult = listOf(DLNACast.Device("udn-1", "TV", "192.168.1.50", true))
        advanceTimeBy(5_000)
        dlna.selectDevice(dlna.state.value.devices.single().id)
        assertTrue(dlna.startCasting(castSource, 0L, "http://phone/stream/v"))
        var failures = 0
        dlna.onSessionFailed = { failures++ }
        engine.transport = DLNACast.PlaybackState.ERROR
        advanceTimeBy(DlnaController.POLL_FOR_TEST_MS * 3)
        assertEquals(1, failures)
        assertFalse(dlna.state.value.session)
        dlna.endSession()
    }

    @Test
    fun `own stop does not echo the session end`() = runTest {
        val dlna = controller(backgroundScope)
        dlna.setDiscoveryEnabled(true)
        engine.searchResult = listOf(DLNACast.Device("udn-1", "TV", "192.168.1.50", true))
        advanceTimeBy(5_000)
        dlna.selectDevice(dlna.state.value.devices.single().id)
        assertTrue(dlna.startCasting(castSource, 0L, "http://phone/stream/v"))
        var ended = 0
        var failures = 0
        dlna.onSessionEnded = { ended++ }
        dlna.onSessionFailed = { failures++ }
        player.pos = 12_345L
        assertEquals(12_345L, dlna.stopCasting())
        runCurrent()
        assertEquals(1, engine.stopCalls)
        engine.transport = DLNACast.PlaybackState.STOPPED
        advanceTimeBy(DlnaController.POLL_FOR_TEST_MS * 3)
        assertEquals(0, ended)
        assertEquals(0, failures)
        assertFalse(dlna.state.value.session)
        dlna.endSession()
    }

    @Test
    fun `endSession clears selection and state without callbacks`() = runTest {
        val dlna = controller(backgroundScope)
        dlna.setDiscoveryEnabled(true)
        engine.searchResult = listOf(DLNACast.Device("udn-1", "TV", "192.168.1.50", true))
        advanceTimeBy(5_000)
        dlna.selectDevice(dlna.state.value.devices.single().id)
        assertTrue(dlna.startCasting(castSource, 0L, "http://phone/stream/v"))
        var callbacks = 0
        dlna.onSessionEnded = { callbacks++ }
        dlna.onSessionFailed = { callbacks++ }
        dlna.endSession()
        assertFalse(dlna.state.value.session)
        assertNull(dlna.state.value.deviceName)
        assertFalse(dlna.state.value.devices.single().selected)
        assertNull(dlna.remotePlayer())
        advanceTimeBy(DlnaController.POLL_FOR_TEST_MS * 2)
        assertEquals(0, callbacks)
    }
}
