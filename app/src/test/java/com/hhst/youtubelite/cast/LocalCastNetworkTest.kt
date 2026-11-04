package com.hhst.youtubelite.cast

import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class LocalCastNetworkTest {
    private fun address(value: String) = InetAddress.getByName(value)
    private fun subnet(value: String, prefix: Int) = LocalCastNetwork.Subnet(address(value), prefix)
    private fun candidate(name: String, active: Boolean, vararg routes: LocalCastNetwork.Subnet) =
        LocalCastNetwork.Candidate(name, active, routes.toList())

    @Test fun receiverSubnetWinsOverTheFirstWlanAndDefaultInternetNetwork() {
        val networks = listOf(
            candidate("secondary-wifi", false, subnet("192.168.50.0", 24), subnet("0.0.0.0", 0)),
            candidate("ethernet", true, subnet("10.0.0.0", 8), subnet("0.0.0.0", 0)),
            candidate("home-wifi", false, subnet("192.168.1.0", 24), subnet("0.0.0.0", 0)),
        )
        assertEquals("home-wifi", LocalCastNetwork.choose(networks, address("192.168.1.8")))
        assertEquals("secondary-wifi", LocalCastNetwork.choose(networks, address("192.168.50.9")))
        assertEquals("ethernet", LocalCastNetwork.choose(networks, null))
    }

    @Test fun mostSpecificRouteWinsAndActiveNetworkBreaksTies() {
        val networks = listOf(
            candidate("broad", true, subnet("192.168.0.0", 16)),
            candidate("specific", false, subnet("192.168.1.0", 24)),
        )
        assertEquals("specific", LocalCastNetwork.choose(networks, address("192.168.1.8")))
        val twins = listOf(candidate("old", false, subnet("192.168.1.0", 24)),
            candidate("current", true, subnet("192.168.1.0", 24)))
        assertEquals("current", LocalCastNetwork.choose(twins, address("192.168.1.8")))
        assertNull(LocalCastNetwork.choose(emptyList<LocalCastNetwork.Candidate<String>>(), address("192.168.1.8")))
    }

    @Test fun subnetMatchingHandlesPartialBytesIpv6AndAddressFamilies() {
        assertTrue(subnet("192.168.1.128", 25).contains(address("192.168.1.200")))
        assertFalse(subnet("192.168.1.128", 25).contains(address("192.168.1.127")))
        assertTrue(subnet("fd12:3456::", 64).contains(address("fd12:3456::1234")))
        assertFalse(subnet("fd12:3456::", 64).contains(address("fd12:3457::1234")))
        assertFalse(subnet("0.0.0.0", 0).contains(address("fd12:3456::1234")))
    }
}
