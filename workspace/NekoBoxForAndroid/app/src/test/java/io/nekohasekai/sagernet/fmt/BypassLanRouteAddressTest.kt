package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.IPv6Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BypassLanRouteAddressTest {
    private val samplePublicRoutes = listOf("1.0.0.0/8", "2.0.0.0/7", " 4.0.0.0/6 ", "")

    @Test
    fun dualStackAppendsGatewayAndFakeDnsAndIpv6() {
        val routes = buildBypassLanRouteAddress(IPv6Mode.ENABLE, samplePublicRoutes)
        // public ranges trimmed and copied verbatim, blank entries skipped
        assertEquals("1.0.0.0/8", routes[0])
        assertEquals("2.0.0.0/7", routes[1])
        assertEquals("4.0.0.0/6", routes[2]) // trimmed
        // in-TUN gateway and FakeDNS range always claimed
        assertTrue(routes.contains(TunAddresses.INET4_ROUTER + "/32"))
        assertTrue(routes.contains(TunAddresses.FAKEDNS_V4 + "/15"))
        // IPv6 global unicast claimed when IPv6 is enabled
        assertTrue(routes.contains("2000::/3"))
    }

    @Test
    fun ipv4DisabledOmitsIpv6Route() {
        val routes = buildBypassLanRouteAddress(IPv6Mode.DISABLE, samplePublicRoutes)
        assertFalse("must not declare 2000::/3 when ipv6 disabled", routes.contains("2000::/3"))
        assertTrue(routes.contains(TunAddresses.INET4_ROUTER + "/32"))
    }

    @Test
    fun ipv6OnlyStillClaimsIpv6Route() {
        val routes = buildBypassLanRouteAddress(IPv6Mode.ONLY, samplePublicRoutes)
        assertEquals(listOf("2000::/3"), routes)
    }

    @Test
    fun emptyPublicRoutesStillClaimsGatewayAndFakeDns() {
        val routes = buildBypassLanRouteAddress(IPv6Mode.DISABLE, emptyList())
        assertEquals(listOf(TunAddresses.INET4_ROUTER + "/32", TunAddresses.FAKEDNS_V4 + "/15"), routes)
    }
}
