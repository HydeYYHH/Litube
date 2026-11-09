package com.hhst.youtubelite.cast

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class DlnaDiscoveryTest {
    private fun renderer(name: String, model: String, host: String) = DlnaRenderer(
        "http://$host:1234/desc.xml".toHttpUrl(), name, "urn:schemas-upnp-org:service:AVTransport:1",
        "http://$host:1234/control".toHttpUrl(), model)

    @Test fun offlineCandidatesDoNotDelayVerifiedDevices() = runBlocking {
        val started = System.nanoTime()
        val found = withTimeout(15_000) {
            collectDescribed(listOf("fast", "slow-a", "slow-b", "slow-c"), 2, 3_000) { location ->
                when (location) {
                    "fast" -> renderer("Quick", "M1", "192.168.1.9")
                    else -> { delay(60_000); null }
                }
            }
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(listOf("Quick"), found.map { it.name })
        assertTrue("describe phase took ${elapsedMs}ms", elapsedMs < 6_000)
    }

    @Test fun allVerifiedDevicesReturnOnceEveryDescriptionFinishes() = runBlocking {
        val found = collectDescribed(listOf("a", "b", "c"), 4, 10_000) { location ->
            delay(50)
            renderer(location.uppercase(), "M1", "192.168.1.2")
        }
        assertEquals(3, found.size)
    }

    @Test fun identicalNamesAreSeparatedByModelThenHost() {
        val renderers = listOf(
            renderer("Living Room", "Projector X", "192.168.1.4"),
            renderer("Living Room", "Projector Y", "192.168.1.5"),
            renderer("Living Room", "", "192.168.1.6"),
            renderer("Study", "TV", "192.168.1.7"),
        )
        assertEquals(listOf(
            "Living Room (Projector X)", "Living Room (Projector Y)",
            "Living Room (192.168.1.6)", "Study",
        ), displayName(renderers))
        val twins = listOf(renderer("Box", "Same", "192.168.1.8"), renderer("Box", "Same", "192.168.1.9"))
        assertEquals(listOf("Box (192.168.1.8)", "Box (192.168.1.9)"), displayName(twins))
    }
}
